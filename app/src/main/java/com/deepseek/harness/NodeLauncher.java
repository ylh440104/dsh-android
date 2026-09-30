package com.deepseek.harness;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NodeLauncher {
    private final Context context;
    private Process process;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final String RUNTIME_VERSION = "0.2.0-rc.1";
    private static final String RUNTIME_URL = "https://github.com/ylh440104/dsh-android/releases/download/v0.2.0-rc.1/runtime-arm64.tar.xz";
    private static final String NODE_URL = "https://github.com/ylh440104/dsh-android/releases/download/v0.2.0-rc.1/node-arm64.tar.xz";

    public interface Callback {
        void onReady(String url);
        void onError(String message);
        void onLog(String line);
        void onProgress(String msg);
    }

    public NodeLauncher(Context context) {
        this.context = context;
    }

    public void start(final Callback callback) {
        executor.execute(() -> {
            try {
                File homeDir = new File(context.getFilesDir(), "dsh-home");
                if (!homeDir.exists()) homeDir.mkdirs();
                
                File nodeDir = new File(context.getFilesDir(), "node");
                File nodeBin = new File(nodeDir, "bin/node");
                File runtimeDir = new File(homeDir, "runtime");
                
                SharedPreferences prefs = context.getSharedPreferences("dsh", Context.MODE_PRIVATE);
                boolean installed = prefs.getBoolean("installed", false);
                
                if (!installed || !nodeBin.exists() || !runtimeDir.exists()) {
                    callback.onProgress("Downloading Node.js runtime...");
                    downloadAndExtract(NODE_URL, nodeDir, callback);
                    
                    callback.onProgress("Downloading DeepSeek Harness runtime...");
                    downloadAndExtract(RUNTIME_URL, homeDir, callback);
                    
                    prefs.edit().putBoolean("installed", true).apply();
                }
                
                nodeBin.setExecutable(true, true);
                setupProfile(homeDir);
                
                callback.onProgress("Starting DeepSeek Harness...");
                
                String primaryRuntime = new File(homeDir, "primary-runtime").getAbsolutePath();
                String pnpmEntry = new File(runtimeDir, "pnpm/bin/pnpm.mjs").getAbsolutePath();
                String nodeBinPath = nodeDir.getAbsolutePath() + "/bin";
                
                ProcessBuilder pb = new ProcessBuilder(
                    nodeBin.getAbsolutePath(),
                    "--expose-internals",
                    new File(runtimeDir, "node_modules/@deepseek-ai/dsh-desktop-host/lib/index.js").getAbsolutePath(),
                    runtimeDir.getAbsolutePath(),
                    new File(homeDir, "profiles/desktop").getAbsolutePath(),
                    primaryRuntime,
                    pnpmEntry,
                    nodeBinPath
                );
                
                pb.directory(new File(homeDir, "profiles/desktop"));
                pb.redirectErrorStream(true);
                
                pb.environment().put("DSH_HOME", homeDir.getAbsolutePath());
                pb.environment().put("DSH_CLIENT_VERSION", RUNTIME_VERSION);
                pb.environment().put("DSH_DESKTOP_NODE_EXECUTABLE", nodeBin.getAbsolutePath());
                pb.environment().put("HOME", context.getFilesDir().getAbsolutePath());
                pb.environment().put("PATH", nodeBinPath + ":/system/bin:/vendor/bin");
                pb.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
                
                process = pb.start();
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                Pattern urlPattern = Pattern.compile("https?://127\\.0\\.0\\.1:\\d+");
                
                long startTime = System.currentTimeMillis();
                long timeout = 120000;
                
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
                if (exitCode != 0) {
                    callback.onError("Process exited with code " + exitCode);
                } else {
                    checkServerReady(callback);
                }
            } catch (Exception e) {
                callback.onError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }
    
    private void checkServerReady(Callback callback) {
        new Thread(() -> {
            for (int i = 0; i < 30; i++) {
                try {
                    Thread.sleep(1000);
                    HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:19387").openConnection();
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    conn.setRequestMethod("HEAD");
                    int code = conn.getResponseCode();
                    if (code > 0) {
                        callback.onReady("http://127.0.0.1:19387");
                        return;
                    }
                } catch (Exception e) {
                }
            }
            callback.onError("Server did not become ready");
        }).start();
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
            patch.createNewFile();
        }
    }
    
    public void stop() {
        if (process != null) {
            process.destroy();
            process = null;
        }
        executor.shutdownNow();
    }
    
    private void downloadAndExtract(String url, File destDir, Callback callback) throws Exception {
        File archive = new File(context.getCacheDir(), "download.tar.xz");
        if (!destDir.exists()) destDir.mkdirs();
        
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(300000);
        conn.setRequestProperty("User-Agent", "DeepSeekHarness/0.2.0");
        
        int totalSize = conn.getContentLength();
        InputStream is = conn.getInputStream();
        FileOutputStream fos = new FileOutputStream(archive);
        byte[] buf = new byte[65536];
        int len;
        int downloaded = 0;
        while ((len = is.read(buf)) > 0) {
            fos.write(buf, 0, len);
            downloaded += len;
            if (totalSize > 0) {
                int pct = downloaded * 100 / totalSize;
                callback.onProgress("Downloading... " + pct + "%");
            }
        }
        fos.close();
        is.close();
        conn.disconnect();
        
        callback.onProgress("Extracting...");
        ProcessBuilder pb = new ProcessBuilder("/system/bin/tar", "-xf", archive.getAbsolutePath(), "-C", destDir.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        while (reader.readLine() != null) {}
        int code = p.waitFor();
        archive.delete();
        if (code != 0) throw new RuntimeException("Extraction failed with code " + code);
    }
}