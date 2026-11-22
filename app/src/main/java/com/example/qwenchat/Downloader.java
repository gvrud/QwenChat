package com.example.qwenchat;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Resumable HTTPS download of the GGUF file from Hugging Face into files/models. */
public final class Downloader {

    public interface Progress {
        void onProgress(long done, long total);
    }

    private Downloader() {
    }

    /** Downloads q into files/models/<name>. Returns true when the complete file is in place. */
    public static boolean download(Context ctx, ModelStore.Quant q, Progress cb, boolean[] cancel)
            throws IOException {
        ModelStore store = new ModelStore(ctx);
        File target = store.file(q);
        File part = store.partialFile(q);
        if (target.isFile() && target.length() == q.size) {
            cb.onProgress(q.size, q.size);
            return true;
        }
        target.delete();

        int failures = 0;
        while (failures < 4) {
            long have = part.exists() ? part.length() : 0;
            if (have > q.size) {
                part.delete();
                have = 0;
            }
            if (have == q.size) break;
            try {
                fetch(q, part, have, cb, cancel);
            } catch (IOException e) {
                if (cancel[0]) throw e;
                failures++;
                if (failures >= 4) throw e;
                try {
                    Thread.sleep(1500L * failures);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Прервано", ie);
                }
            }
        }
        if (!part.isFile() || part.length() != q.size) {
            throw new IOException("Размер файла не совпадает: " + (part.exists() ? part.length() : 0)
                    + " из " + q.size);
        }
        if (!part.renameTo(target)) {
            target.delete();
            if (!part.renameTo(target)) throw new IOException("Не удалось переименовать файл модели");
        }
        return true;
    }

    private static void fetch(ModelStore.Quant q, File part, long have, Progress cb, boolean[] cancel)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(ModelStore.REPO + q.fileName).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        try {
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            int code = c.getResponseCode();
            long total = q.size;
            long skip = 0;
            if (code == HttpURLConnection.HTTP_PARTIAL || code == 206) {
                String cr = c.getHeaderField("Content-Range");
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash > 0) total = Long.parseLong(cr.substring(slash + 1).trim());
                    int dash = cr.indexOf('-');
                    if (cr.startsWith("bytes ") && dash > 6) {
                        long start = Long.parseLong(cr.substring(6, dash).trim());
                        skip = have - start;
                    }
                }
            } else if (code == HttpURLConnection.HTTP_OK) {
                long len = c.getContentLengthLong();
                if (len > 0) total = len;
                have = 0;
            } else {
                throw new IOException("HTTP " + code);
            }
            if (total > 0 && total != q.size) {
                if (total < q.size) {
                    // A short file means the server answered with the wrong object.
                    c.disconnect();
                    throw new IOException("Сервер вернул файл другого размера: " + total);
                }
            }

            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(part, have > 0)) {
                byte[] buf = new byte[64 * 1024];
                long done = have;
                long skipLeft = skip;
                int n;
                long last = 0;
                while ((n = in.read(buf)) > 0) {
                    if (cancel[0]) throw new IOException("Отменено");
                    int off = 0;
                    if (skipLeft > 0) {
                        int drop = (int) Math.min(skipLeft, n);
                        skipLeft -= drop;
                        off = drop;
                        n -= drop;
                    }
                    if (n > 0) out.write(buf, off, n);
                    done += n + off;
                    long now = System.currentTimeMillis();
                    if (now - last > 200 || done >= q.size) {
                        last = now;
                        cb.onProgress(done, q.size);
                    }
                }
            }
        } finally {
            c.disconnect();
        }
    }

    /** SHA-256 of the GGUF file, purely informational (shown after download). */
    public static String sha256(File f) {
        try (InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(f))) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Quick sanity check that the file starts with the GGUF magic. */
    public static boolean isGguf(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] magic = new byte[4];
            raf.readFully(magic);
            return new String(magic, StandardCharsets.US_ASCII).equals("GGUF");
        } catch (IOException e) {
            return false;
        }
    }
}
