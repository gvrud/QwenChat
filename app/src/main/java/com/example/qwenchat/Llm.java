package com.example.qwenchat;

import java.util.ArrayList;
import java.util.List;

/** Builds the prompt inside a token budget and runs chat completion on the native engine. */
public final class Llm {

    public interface Listener {
        void onToken(String text);

        /** error == null on success, "cancelled" when the user stopped generation. */
        void onDone(String error);
    }

    private final long engine;

    public Llm(long engine) {
        this.engine = engine;
    }

    static String systemFor(boolean withDocs) {
        if (!withDocs) {
            return "Ты — краткий и точный помощник. Отвечай на языке пользователя.";
        }
        return "Ты — помощник по документам. Ниже текст документа. Отвечай только по этому тексту "
                + "и прямо говори, если ответа в нём нет. Отвечай на языке пользователя.";
    }

    /**
     * Rebuilds the whole prompt every request. The document gets at most two thirds of the
     * budget; when it does not fit, its head and tail are kept because questions usually
     * refer to the beginning or the end.
     */
    static String buildPrompt(long engine, List<Chat.Doc> docs, List<Chat.Msg> history,
                              String question, int ctx, int reserve) {
        int budget = ctx - reserve - 48;
        StringBuilder doc = new StringBuilder();
        for (Chat.Doc d : docs) {
            doc.append("\n\n[Документ: ").append(d.name).append("]\n").append(d.text);
        }
        if (doc.length() > 0) {
            int docTokens = Qwen.countTokens(engine, doc.toString());
            int docCap = Math.max(256, budget * 2 / 3);
            if (docTokens > docCap) {
                String t = doc.toString();
                int keep = Math.max(400, t.length() * docCap / docTokens - 200);
                doc.setLength(0);
                doc.append(t, 0, keep / 2)
                        .append("\n[...часть документа вырезана из-за длины...]\n")
                        .append(t, t.length() - keep / 2, t.length());
            }
        }

        int left = budget - Qwen.countTokens(engine, doc.toString());
        List<Chat.Msg> tail = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && left > 0; i--) {
            Chat.Msg m = history.get(i);
            int t = Qwen.countTokens(engine, m.text) + 8;
            if (t > left) break;
            left -= t;
            tail.add(0, m);
        }

        StringBuilder sb = new StringBuilder();
        // Qwen2.5 is ChatML-native: without <|im_start|>/<|im_end|> markers the model
        // treats the prompt as plain text and often stops before answering.
        sb.append("<|im_start|>system\n").append(systemFor(doc.length() > 0)).append("<|im_end|>\n");
        if (doc.length() > 0) {
            sb.append("<|im_start|>user\n").append(doc).append("<|im_end|>\n");
            sb.append("<|im_start|>assistant\nПринял, буду отвечать по этому тексту.<|im_end|>\n");
        }
        for (Chat.Msg m : tail) {
            sb.append("<|im_start|>").append(m.role).append('\n').append(m.text).append("<|im_end|>\n");
        }
        sb.append("<|im_start|>").append(Chat.Msg.USER).append('\n').append(question)
                .append("<|im_end|>\n<|im_start|>").append(Chat.Msg.BOT).append('\n');
        return sb.toString();
    }

    /**
     * Blocks the calling thread; callbacks arrive on it. Returns the whole answer, which is
     * the fallback when the streaming callback delivered nothing.
     */
    public String generate(String prompt, int maxTokens, float temp, boolean[] cancel, Listener l) {
        Qwen.reset(engine);
        String full = Qwen.generate(engine, prompt, maxTokens, temp, cancel, l::onToken);
        l.onDone(cancel[0] ? "cancelled" : null);
        return full == null ? "" : full;
    }
}
