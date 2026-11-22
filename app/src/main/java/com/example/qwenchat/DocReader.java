package com.example.qwenchat;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;

/**
 * Text extraction for TXT, DOCX and PDF without third-party libraries.
 * DOCX: unzip word/document.xml (raw inflate), keep w:t runs, break paragraphs.
 * PDF: inflate FlateDecode streams, read Tj/TJ strings from the last-page-first text.
 * Scanned PDFs without a text layer yield an empty string; the caller must say so.
 */
public final class DocReader {

    public static final int MAX_CHARS = 400_000;

    public static String read(Context ctx, Uri uri, String name) throws IOException {
        String lower = name == null ? "" : name.toLowerCase();
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Не удалось открыть файл");
            if (lower.endsWith(".pdf")) return pdf(in);
            if (lower.endsWith(".docx")) return docx(in);
            return text(in);
        }
    }

    private static byte[] all(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > 40 << 20) throw new IOException("Файл слишком большой (лимит 40 МБ)");
        }
        return bos.toByteArray();
    }

    private static String clip(String s) {
        s = s.replace('\u0000', ' ').replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n");
        return s.length() > MAX_CHARS ? s.substring(0, MAX_CHARS) : s;
    }

    // ---------------- TXT ----------------

    private static String text(InputStream in) throws IOException {
        byte[] b = all(in);
        String s;
        if (b.length > 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            s = new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        } else if (b.length > 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            s = new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        } else {
            int from = b.length > 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF ? 3 : 0;
            s = new String(b, from, b.length - from, StandardCharsets.UTF_8);
            // U+FFFD means it was not UTF-8 (old Windows files are usually CP1251).
            if (s.indexOf('\uFFFD') >= 0) {
                try {
                    s = new String(b, from, b.length - from, Charset.forName("windows-1251"));
                } catch (Exception ignored) {
                }
            }
        }
        return clip(s.trim());
    }

    // ---------------- DOCX ----------------

    private static String docx(InputStream in) throws IOException {
        Map<String, byte[]> entries = unzip(all(in));
        byte[] xml = entries.get("word/document.xml");
        if (xml == null) throw new IOException("Это не DOCX (нет word/document.xml)");
        return clip(docxText(new String(xml, StandardCharsets.UTF_8)));
    }

    private static String docxText(String xml) {
        StringBuilder out = new StringBuilder(xml.length() / 3);
        for (int i = 0; i < xml.length(); ) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) break;
            int gt = xml.indexOf('>', lt);
            if (gt < 0) break;
            String tag = xml.substring(lt + 1, gt);
            i = gt + 1;
            if (tag.startsWith("w:t")) {
                if (tag.startsWith("w:tab")) {
                    out.append('\t');
                    continue;
                }
                int end = xml.indexOf("</w:t>", i);
                if (end < 0) break;
                out.append(unescape(xml.substring(i, end)));
                i = end + 6;
            } else if (tag.equals("w:tab") || tag.equals("w:tab/")) {
                out.append('\t');
            } else if (tag.startsWith("w:p ") || tag.startsWith("w:p>") || tag.startsWith("w:p/")) {
                out.append('\n');
            } else if (tag.startsWith("w:br") || tag.startsWith("w:cr")) {
                out.append('\n');
            } else if (tag.startsWith("w:tc ") || tag.startsWith("w:tc>")) {
                out.append(' ');
            }
        }
        return out.toString();
    }

    private static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    /** Minimal ZIP reader: local headers only, stored and deflated entries. */
    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        int p = 0;
        while (p + 30 <= zip.length) {
            if (le32(zip, p) != 0x04034b50) break;
            int method = le16(zip, p + 8);
            int nameLen = le16(zip, p + 26);
            int extraLen = le16(zip, p + 28);
            int nameEnd = p + 30 + nameLen;
            if (nameEnd > zip.length) break;
            String name = new String(zip, p + 30, nameLen, StandardCharsets.UTF_8);
            int start = nameEnd + extraLen;

            int size;
            int dataStart = start;
            if ((le16(zip, p + 6) & 0x08) != 0) {
                // Data descriptor: sizes follow the data; find it by scanning for the signature.
                int next = -1;
                for (int q = start; q + 4 <= zip.length; q++) {
                    if (le32(zip, q) == 0x08074b50) {
                        next = q;
                        break;
                    }
                }
                if (next < 0) next = zip.length;
                size = next - start;
            } else {
                size = le32(zip, p + 18);
            }
            if (start + size > zip.length) size = zip.length - start;

            if (method == 0) {
                byte[] data = new byte[size];
                System.arraycopy(zip, dataStart, data, 0, size);
                out.put(name, data);
            } else if (method == 8) {
                out.put(name, inflate(zip, dataStart, size, 32 << 20));
            }
            p = dataStart + size;
        }
        return out;
    }

    private static int le16(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }

    /** Raw inflate of a deflate stream that has no zlib header. */
    private static byte[] inflate(byte[] src, int off, int len, int limit) throws IOException {
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(src, off, len);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1 << 14, len * 3));
            byte[] buf = new byte[1 << 16];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                bos.write(buf, 0, n);
                if (bos.size() > limit) break;
            }
            return bos.toByteArray();
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("Повреждённый ZIP в DOCX", e);
        } finally {
            inf.end();
        }
    }

    // ---------------- PDF ----------------

    private static String pdf(InputStream in) throws IOException {
        byte[] b = all(in);
        List<String> chunks = new ArrayList<>();
        int p = 0;
        while ((p = indexOf(b, "stream", p)) >= 0) {
            int dataStart = p + 6;
            if (dataStart < b.length && b[dataStart] == '\r') dataStart++;
            if (dataStart < b.length && b[dataStart] == '\n') dataStart++;
            int end = indexOf(b, "endstream", dataStart);
            if (end < 0) end = b.length;
            int head = Math.max(0, p - 400);
            String dict = new String(b, head, p - head, StandardCharsets.ISO_8859_1);
            byte[] data = null;
            if (dict.contains("FlateDecode")) {
                try {
                    data = inflate(b, dataStart, end - dataStart, 8 << 20);
                } catch (IOException ignored) {
                }
            } else if (!dict.contains("/Filter")) {
                data = new byte[end - dataStart];
                System.arraycopy(b, dataStart, data, 0, data.length);
            }
            if (data != null && data.length > 0) {
                String s = pdfText(new String(data, StandardCharsets.ISO_8859_1));
                if (!s.isEmpty()) chunks.add(s);
            }
            p = end + 9;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            sb.append(chunks.get(i));
            sb.append('\n');
        }
        String out = clip(cbToString(sb.toString()));
        if (out.trim().isEmpty()) {
            throw new IOException("В PDF нет текстового слоя (это скан или картинки). "
                    + "Распознавание по картинкам приложение не делает.");
        }
        return out;
    }

    private static int indexOf(byte[] b, String needle, int from) {
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = Math.max(0, from); i + n.length <= b.length; i++) {
            for (int j = 0; j < n.length; j++) if (b[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** Pulls strings from Tj, TJ and ' operators of a content stream. */
    private static String pdfText(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        boolean array = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '(') {
                StringBuilder lit = new StringBuilder();
                i++;
                int depth = 1;
                while (i < s.length() && depth > 0) {
                    char d = s.charAt(i);
                    if (d == '\\' && i + 1 < s.length()) {
                        char e = s.charAt(++i);
                        switch (e) {
                            case 'n': lit.append('\n'); break;
                            case 'r': lit.append('\r'); break;
                            case 't': lit.append('\t'); break;
                            case 'b': case 'f': break;
                            case '\n': break;
                            default:
                                if (e >= '0' && e <= '7') {
                                    int v = 0, k = 0;
                                    while (k < 3 && i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '7') {
                                        v = v * 8 + (s.charAt(i) - '0');
                                        i++; k++;
                                    }
                                    i--;
                                    lit.append((char) v);
                                } else {
                                    lit.append(e);
                                }
                        }
                    } else if (d == '(') {
                        depth++;
                        lit.append(d);
                    } else if (d == ')') {
                        depth--;
                        if (depth > 0) lit.append(d);
                    } else {
                        lit.append(d);
                    }
                    i++;
                }
                out.append(lit);
            } else if (c == '[') {
                array = true;
                i++;
            } else if (c == ']') {
                array = false;
                i++;
            } else if (c == 'T' && i + 1 < s.length() && s.charAt(i + 1) == 'd') {
                out.append('\n');
                i += 2;
            } else if (c == 'T' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                out.append('\n');
                i += 2;
            } else if ((c == 'T' && i + 1 < s.length() && (s.charAt(i + 1) == 'j' || s.charAt(i + 1) == 'J'))
                    || c == '\'') {
                out.append(array ? "" : " ");
                i += c == '\'' ? 1 : 2;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    /** Rebuilds UTF-16BE strings that were split into bytes by the ISO-8859-1 view. */
    private static String cbToString(String s) {
        if (s.indexOf("\u00fe\u00ff") < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '\u00fe' && i + 1 < s.length() && s.charAt(i + 1) == '\u00ff') {
                i += 2;
                while (i + 1 < s.length() && !(s.charAt(i) == '\u00fe' && s.charAt(i + 1) == '\u00ff')) {
                    out.append((char) (((s.charAt(i) & 0xFF) << 8) | (s.charAt(i + 1) & 0xFF)));
                    i += 2;
                }
            } else {
                out.append(s.charAt(i));
                i++;
            }
        }
        return out.toString();
    }
}
