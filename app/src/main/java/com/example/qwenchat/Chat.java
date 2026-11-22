package com.example.qwenchat;

import java.util.ArrayList;
import java.util.List;

/** Chat history in memory, saved as one JSON file by ChatStore. */
public final class Chat {

    public static final class Msg {
        public static final String USER = "user";
        public static final String BOT = "assistant";

        public final String role;
        public final String text;

        public Msg(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    /** Extracted document text; stored with the chat, so history works offline after restart. */
    public static final class Doc {
        public final String name;
        public final String text;

        public Doc(String name, String text) {
            this.name = name;
            this.text = text;
        }
    }

    public String id;
    public String title = "Новый чат";
    public long updated = System.currentTimeMillis();
    public final List<Msg> messages = new ArrayList<>();
    public final List<Doc> docs = new ArrayList<>();
}
