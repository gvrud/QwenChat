package com.example.qwenchat;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Chat history: all chats in one JSON file (files/chats.json), rewritten atomically.
 * History lives in internal storage, so it does not depend on source file URIs.
 */
public final class ChatStore {

    private final Context ctx;
    private final File file;
    private final List<Chat> chats = new ArrayList<>();

    public ChatStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.file = new File(this.ctx.getFilesDir(), "chats.json");
        load();
    }

    public List<Chat> chats() {
        List<Chat> copy = new ArrayList<>(chats);
        Collections.sort(copy, (a, b) -> Long.compare(b.updated, a.updated));
        return copy;
    }

    public Chat get(String id) {
        for (Chat c : chats) if (c.id.equals(id)) return c;
        return null;
    }

    public Chat create() {
        Chat c = new Chat();
        c.id = UUID.randomUUID().toString();
        chats.add(c);
        return c;
    }

    public void delete(Chat c) {
        chats.remove(c);
        save();
    }

    /** Marks the chat as changed and persists it (history is rewritten atomically). */
    public void touch(Chat c) {
        c.updated = System.currentTimeMillis();
        save();
    }

    // ---- persistence ----

    private void load() {
        if (!file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            JSONObject root = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("chats");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Chat c = new Chat();
                c.id = o.optString("id", UUID.randomUUID().toString());
                c.title = o.optString("title", "Чат");
                c.updated = o.optLong("updated", 0);
                JSONArray ms = o.optJSONArray("messages");
                for (int j = 0; ms != null && j < ms.length(); j++) {
                    JSONObject mo = ms.getJSONObject(j);
                    c.messages.add(new Chat.Msg(mo.optString("role", Chat.Msg.USER), mo.optString("text", "")));
                }
                JSONArray ds = o.optJSONArray("docs");
                for (int j = 0; ds != null && j < ds.length(); j++) {
                    JSONObject d = ds.getJSONObject(j);
                    c.docs.add(new Chat.Doc(d.optString("name", "документ"), d.optString("text", "")));
                }
                chats.add(c);
            }
        } catch (IOException | JSONException e) {
            // A broken history must not lock the app; keep it aside.
            file.renameTo(new File(ctx.getFilesDir(), "chats.corrupt.json"));
        }
    }

    public void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Chat c : chats) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("title", c.title);
                o.put("updated", c.updated);
                JSONArray ms = new JSONArray();
                for (Chat.Msg m : c.messages) {
                    JSONObject mo = new JSONObject();
                    mo.put("role", m.role);
                    mo.put("text", m.text);
                    ms.put(mo);
                }
                o.put("messages", ms);
                JSONArray ds = new JSONArray();
                for (Chat.Doc d : c.docs) {
                    JSONObject dj = new JSONObject();
                    dj.put("name", d.name);
                    dj.put("text", d.text);
                    ds.put(dj);
                }
                o.put("docs", ds);
                arr.put(o);
            }
            root.put("chats", arr);
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                if (!tmp.renameTo(file)) throw new IOException("rename failed");
            }
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("Не удалось сохранить историю чатов", e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
