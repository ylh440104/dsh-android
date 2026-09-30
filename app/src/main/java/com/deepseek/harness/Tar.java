package com.deepseek.harness;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class Tar {
    private Tar() {
    }

    public static void extract(InputStream in, File destDir) throws IOException {
        if (!destDir.exists()) destDir.mkdirs();
        byte[] hdr = new byte[512];
        String pendingName = null;
        String pendingLink = null;
        while (true) {
            if (!readFully(in, hdr, 512)) break;
            if (isZero(hdr)) {
                if (!readFully(in, hdr, 512)) break;
                if (isZero(hdr)) break;
            }
            String name = str(hdr, 0, 100);
            long size = octal(hdr, 124, 12);
            int type = hdr[156] & 0xFF;
            String prefix = str(hdr, 345, 155);
            String link = str(hdr, 157, 100);
            long mode = octal(hdr, 100, 8);

            if (type == 'L') {
                pendingName = dataString(in, size);
                skipPad(in, size);
                continue;
            }
            if (type == 'x' || type == 'g') {
                byte[] d = data(in, size);
                if (type == 'x') {
                    String p = pax(d, "path");
                    String l = pax(d, "linkpath");
                    if (p != null && p.length() > 0) pendingName = p;
                    if (l != null && l.length() > 0) pendingLink = l;
                }
                skipPad(in, size);
                continue;
            }

            String full = pendingName != null ? pendingName : (prefix.length() > 0 ? prefix + "/" + name : name);
            String target = pendingLink != null ? pendingLink : link;
            pendingName = null;
            pendingLink = null;
            full = clean(full);

            if (full.length() == 0) {
                skip(in, size);
                skipPad(in, size);
                continue;
            }

            File out = new File(destDir, full);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            if (type == '5') {
                out.mkdirs();
            } else if (type == '2') {
                try {
                    if (Files.exists(out.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) out.delete();
                    Files.createSymbolicLink(out.toPath(), Paths.get(target));
                } catch (Exception e) {
                }
                skip(in, size);
            } else if (type == '1') {
                try {
                    if (out.exists()) out.delete();
                    Files.createLink(out.toPath(), new File(destDir, clean(target)).toPath());
                } catch (Exception e) {
                }
                skip(in, size);
            } else {
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    copy(in, fos, size);
                } finally {
                    fos.close();
                }
                if ((mode & 0111) != 0) out.setExecutable(true, false);
            }
            skipPad(in, size);
        }
    }

    private static boolean readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static boolean isZero(byte[] b) {
        for (int i = 0; i < b.length; i++) if (b[i] != 0) return false;
        return true;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        int max = off + len;
        while (end < max && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8).trim();
    }

    private static long octal(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) {
            long v = b[off] & 0x7F;
            for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xFF);
            return v;
        }
        long v = 0;
        for (int i = 0; i < len; i++) {
            int c = b[off + i] & 0xFF;
            if (c == 0 || c == ' ') continue;
            if (c < '0' || c > '7') break;
            v = (v << 3) + (c - '0');
        }
        return v;
    }

    private static byte[] data(InputStream in, long size) throws IOException {
        if (size <= 0) return new byte[0];
        byte[] buf = new byte[(int) size];
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return buf;
    }

    private static String dataString(InputStream in, long size) throws IOException {
        byte[] d = data(in, size);
        int end = d.length;
        while (end > 0 && d[end - 1] == 0) end--;
        return new String(d, 0, end, StandardCharsets.UTF_8);
    }

    private static void copy(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buf = new byte[65536];
        long left = size;
        while (left > 0) {
            int want = (int) Math.min(left, buf.length);
            int n = in.read(buf, 0, want);
            if (n < 0) break;
            out.write(buf, 0, n);
            left -= n;
        }
    }

    private static void skip(InputStream in, long size) throws IOException {
        long left = size;
        byte[] buf = new byte[8192];
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(left, buf.length));
            if (n < 0) break;
            left -= n;
        }
    }

    private static void skipPad(InputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        if (pad > 0) skip(in, pad);
    }

    private static String clean(String p) {
        String s = p.replace('\\', '/');
        while (s.startsWith("./")) s = s.substring(2);
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String pax(byte[] d, String key) {
        String text = new String(d, StandardCharsets.UTF_8);
        int i = 0;
        while (i < text.length()) {
            int sp = text.indexOf(' ', i);
            if (sp < 0) break;
            int len;
            try {
                len = Integer.parseInt(text.substring(i, sp).trim());
            } catch (Exception e) {
                break;
            }
            if (len <= 0 || i + len > text.length()) break;
            String rec = text.substring(sp + 1, i + len);
            int eq = rec.indexOf('=');
            if (eq > 0) {
                String k = rec.substring(0, eq).trim();
                String v = rec.substring(eq + 1);
                if (v.endsWith("\n")) v = v.substring(0, v.length() - 1);
                if (k.equals(key)) return v;
            }
            i += len;
        }
        return null;
    }
}
