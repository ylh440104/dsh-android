package com.deepseek.harness;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.tukaani.xz.XZInputStream;

public class NodeLauncher {
    private final Context context;
    private Process process;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final String RUNTIME_VERSION = "0.2.0-rc.4";
    private static final String BASE_URL = "https://github.com/ylh440104/dsh-android/releases/download/v0.2.0-rc.4/";

    public interface Callback {
        void onReady(String url);
        void onError(String message);
        void onLog(String line);
        void onProgress(String msg);
    }

    public NodeLauncher(Context context) {
        this.context = context;
    }

    private static String abiTag() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi.equals("arm64-v8a")) return "arm64";
            if (abi.equals("x86_64")) return "x86_64";
        }
        return "arm64";
    }

    public void start(final Callback callback) {
        executor.execute(() -> {
            try {
                File filesDir = context.getFilesDir();
                File homeDir = new File(filesDir, "dsh-home");
                if (!homeDir.exists()) homeDir.mkdirs();

                String abi = abiTag();
                String stamp = RUNTIME_VERSION + "-" + abi;

                File nodeDir = new File(filesDir, "node");
                File nodeBin = new File(nodeDir, "bin/node");
                File runtimeDir = new File(homeDir, "runtime");

                SharedPreferences prefs = context.getSharedPreferences("dsh", Context.MODE_PRIVATE);
                boolean installed = prefs.getBoolean("installed", false);
                String installedStamp = prefs.getString("stamp", "");

                if (!installed || !stamp.equals(installedStamp) || !nodeBin.exists() || !runtimeDir.exists()) {
                    callback.onProgress("Downloading Node.js (" + abi + ")...");
                    deleteRecursive(nodeDir);
                    downloadAndExtract(BASE_URL + "node-" + abi + ".tar.xz", "node-" + abi + ".tar.xz", nodeDir, callback);

                    callback.onProgress("Downloading Harness runtime...");
                    deleteRecursive(runtimeDir);
                    downloadAndExtract(BASE_URL + "runtime.tar.xz", "runtime.tar.xz", homeDir, callback);

                    prefs.edit().putBoolean("installed", true).putString("stamp", stamp).apply();
                }

                if (!nodeBin.exists()) {
                    callback.onError("node binary not found at " + nodeBin.getAbsolutePath());
                    return;
                }
                nodeBin.setExecutable(true, true);

                setupProfile(homeDir);
                linkOfficeNode(homeDir, nodeBin);

                String nodeBinPath = nodeDir.getAbsolutePath() + "/bin";
                String ldLibPath = nodeDir.getAbsolutePath() + "/lib:/system/lib64:/system/lib:/vendor/lib64:/vendor/lib";

                callback.onProgress("Verifying node...");
                ProcessBuilder testPb = new ProcessBuilder(nodeBin.getAbsolutePath(), "-v");
                testPb.redirectErrorStream(true);
                testPb.environment().put("LD_LIBRARY_PATH", ldLibPath);
                testPb.environment().put("HOME", filesDir.getAbsolutePath());
                testPb.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
                Process testProc = testPb.start();
                BufferedReader testReader = new BufferedReader(new InputStreamReader(testProc.getInputStream()));
                StringBuilder testOut = new StringBuilder();
                String testLine;
                while ((testLine = testReader.readLine()) != null) testOut.append(testLine).append("\n");
                int testCode = testProc.waitFor();
                if (testCode != 0) {
                    callback.onError("node test failed (code " + testCode + "): " + testOut.toString());
                    return;
                }
                callback.onLog("node version: " + testOut.toString().trim());

                callback.onProgress("Starting DeepSeek Harness...");

                File entryFile = new File(runtimeDir, "node_modules/@deepseek-ai/dsh-desktop-host/lib/index.js");
                if (!entryFile.exists()) {
                    callback.onError("host entry not found: " + entryFile.getAbsolutePath());
                    return;
                }

                String primaryRuntime = new File(homeDir, "primary-runtime").getAbsolutePath();
                String pnpmEntry = new File(runtimeDir, "pnpm/bin/pnpm.mjs").getAbsolutePath();

                ProcessBuilder pb = new ProcessBuilder(
                    nodeBin.getAbsolutePath(),
                    "--expose-internals",
                    entryFile.getAbsolutePath(),
                    runtimeDir.getAbsolutePath(),
                    new File(homeDir, "profiles/desktop").getAbsolutePath(),
                    primaryRuntime,
                    pnpmEntry,
                    nodeBinPath
                );
                pb.directory(new File(homeDir, "profiles/desktop"));
                pb.redirectErrorStream(true);
                pb.environment().put("DSH_HOME", homeDir.getAbsolutePath());
                pb.environment().put("DSH_CLIENT_VERSION", "0.2.0-rc.1");
                pb.environment().put("DSH_DESKTOP_NODE_EXECUTABLE", nodeBin.getAbsolutePath());
                pb.environment().put("HOME", filesDir.getAbsolutePath());
                pb.environment().put("PATH", nodeBinPath + ":/system/bin:/vendor/bin");
                pb.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
                pb.environment().put("LD_LIBRARY_PATH", ldLibPath);
                pb.environment().put("NODE_OPTIONS", "--max-old-space-size=1024");

                process = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                Pattern urlPattern = Pattern.compile("https?://127\\.0\\.0\\.1:\\d+");
                long startTime = System.currentTimeMillis();
                long timeout = 180000;

                while ((line = reader.readLine()) != null) {
                    callback.onLog(line);
                    Matcher m = urlPattern.matcher(line);
                    if (m.find()) {
                        callback.onReady(m.group());
                        return;
                    }
                    if (System.currentTimeMillis() - startTime > timeout) {
                        callback.onError("Timeout waiting for server to start");
                        process.destroy();
                        return;
                    }
                }

                int exitCode = process.waitFor();
                callback.onError("Process exited with code " + exitCode);
            } catch (Throwable e) {
                callback.onError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }

    private void setupProfile(File homeDir) throws Exception {
        File profileDir = new File(homeDir, "profiles/desktop");
        if (!profileDir.exists()) profileDir.mkdirs();
        File pkgJson = new File(profileDir, "package.json");
        if (!pkgJson.exists()) {
            String json = "{\"name\":\"desktop\",\"private\":true,\"type\":\"module\",\"dsh\":{\"profile\":{\"bundles\":[\"@deepseek-ai/dsh-base\",\"@deepseek-ai/dsh-web-app\"]}}}";
            FileOutputStream fos = new FileOutputStream(pkgJson);
            fos.write(json.getBytes());
            fos.close();
        }
        File patch = new File(profileDir, "cordis.patch.yml");
        if (!patch.exists()) {
            FileOutputStream fos = new FileOutputStream(patch);
            fos.write("[]\n".getBytes());
            fos.close();
        } else if (patch.length() == 0) {
            FileOutputStream fos = new FileOutputStream(patch);
            fos.write("[]\n".getBytes());
            fos.close();
        }
    }

    private void linkOfficeNode(File homeDir, File nodeBin) {
        File officeNode = new File(homeDir, "primary-runtime/dependencies/node/bin/node");
        if (officeNode.exists()) return;
        try {
            File parent = officeNode.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            java.nio.file.Files.createSymbolicLink(officeNode.toPath(), nodeBin.toPath());
        } catch (Exception e) {
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(nodeBin);
                FileOutputStream out = new FileOutputStream(officeNode);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                out.close();
                officeNode.setExecutable(true, false);
            } catch (Exception ignored) {
            }
        }
    }

    public void stop() {
        if (process != null) {
            process.destroy();
            process = null;
        }
        executor.shutdownNow();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    private void downloadAndExtract(String url, String localName, File destDir, Callback callback) throws Exception {
        File archive = new File(context.getCacheDir(), "download.tar.xz");
        if (archive.exists()) archive.delete();
        if (!destDir.exists()) destDir.mkdirs();

        File local = new File(context.getExternalFilesDir(null), localName);
        if (local.exists() && local.length() > 0) {
            callback.onProgress("Using local " + localName + "...");
            copyFile(local, archive);
            extractArchive(archive, destDir, callback);
            return;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(600000);
        conn.setRequestProperty("User-Agent", "DeepSeekHarness/0.2.0");
        conn.setInstanceFollowRedirects(true);

        int totalSize = conn.getContentLength();
        InputStream is = conn.getInputStream();
        FileOutputStream fos = new FileOutputStream(archive);
        byte[] buf = new byte[65536];
        int len;
        int downloaded = 0;
        while ((len = is.read(buf)) > 0) {
            fos.write(buf, 0, len);
            downloaded += len;
            if (totalSize > 0) callback.onProgress("Downloading... " + (downloaded * 100 / totalSize) + "%");
        }
        fos.close();
        is.close();
        conn.disconnect();

        if (archive.length() == 0) throw new RuntimeException("download failed, empty file: " + url);
        extractArchive(archive, destDir, callback);
    }

    private void extractArchive(File archive, File destDir, Callback callback) throws Exception {
        callback.onProgress("Extracting...");
        InputStream xz = new XZInputStream(new BufferedInputStream(new java.io.FileInputStream(archive), 65536));
        try {
            Tar.extract(xz, destDir);
        } finally {
            xz.close();
        }
        archive.delete();
    }

    private void copyFile(File src, File dst) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Exception e) {}
            try { out.close(); } catch (Exception e) {}
        }
    }
}
