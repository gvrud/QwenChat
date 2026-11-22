package com.example.qwenchat;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Model file + settings stored in SharedPreferences. */
public final class ModelStore {

    public static final class Quant {
        public final String id;
        public final String fileName;
        public final long size;
        public final String note;

        Quant(String id, String fileName, long size, String note) {
            this.id = id;
            this.fileName = fileName;
            this.size = size;
            this.note = note;
        }
    }

    public static final String REPO = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/";

    public static final Quant[] QUANTS = {
            new Quant("Q2_K", "qwen2.5-0.5b-instruct-q2_k.gguf", 415182688L, "415 МБ, минимум ОЗУ"),
            new Quant("Q3_K_M", "qwen2.5-0.5b-instruct-q3_k_m.gguf", 432041824L, "432 МБ, для 2 ГБ ОЗУ"),
            new Quant("Q4_K_M", "qwen2.5-0.5b-instruct-q4_k_m.gguf", 491400032L, "491 МБ, лучшее качество"),
            new Quant("Q5_K_M", "qwen2.5-0.5b-instruct-q5_k_m.gguf", 522186592L, "522 МБ"),
            new Quant("Q8_0", "qwen2.5-0.5b-instruct-q8_0.gguf", 675710816L, "676 МБ, почти без потерь"),
    };

    public static final Map<String, Integer> CTX_PRESETS = new LinkedHashMap<>();
    public static final int[] THREADS = {2, 3, 4, 6, 8};

    static {
        CTX_PRESETS.put("1024", 1024);
        CTX_PRESETS.put("1536 (2 ГБ ОЗУ)", 1536);
        CTX_PRESETS.put("2048", 2048);
        CTX_PRESETS.put("3072", 3072);
        CTX_PRESETS.put("4096", 4096);
    }

    private final SharedPreferences sp;
    private final File modelsDir;

    public ModelStore(Context ctx) {
        this.sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        this.modelsDir = new File(ctx.getFilesDir(), "models");
        modelsDir.mkdirs();
    }

    public File modelsDir() {
        return modelsDir;
    }

    public File partialFile(Quant q) {
        return new File(modelsDir, q.fileName + ".part");
    }

    public File file(Quant q) {
        return new File(modelsDir, q.fileName);
    }

    /** Installed quants: files that exist and have the expected size. */
    public String installedQuants() {
        StringBuilder sb = new StringBuilder();
        for (Quant q : QUANTS) {
            File f = file(q);
            if (f.isFile() && f.length() == q.size) {
                if (sb.length() > 0) sb.append(',');
                sb.append(q.id);
            }
        }
        return sb.toString();
    }

    public Quant selectedQuant() {
        String id = sp.getString("quant", "Q3_K_M");
        for (Quant q : QUANTS) if (q.id.equals(id)) {
            if (file(q).isFile()) return q;
            // The pref can outlive the file (download replaced or failed); never report
            // "not downloaded" while an installed quant is sitting on disk.
            for (Quant other : QUANTS) if (file(other).isFile()) return other;
            return q;
        }
        return QUANTS[1];
    }

    public void setQuant(String id) {
        // commit(): the choice must survive an immediate process death right after a download.
        sp.edit().putString("quant", id).commit();
    }

    public File modelFile() {
        return file(selectedQuant());
    }

    public int ctx() {
        return sp.getInt("ctx", 1536);
    }

    public void setCtx(int v) {
        sp.edit().putInt("ctx", v).apply();
    }

    public int threads() {
        int def = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        return sp.getInt("threads", def);
    }

    public void setThreads(int v) {
        sp.edit().putInt("threads", v).apply();
    }

    public float temperature() {
        return sp.getFloat("temp", 0.3f);
    }

    public void setTemperature(float v) {
        sp.edit().putFloat("temp", v).apply();
    }

    /** mmap saves RAM on 2 GB devices; only disabled as a fallback if loading fails. */
    public boolean noMmap() {
        return sp.getBoolean("nommap", false);
    }

    public void setNoMmap(boolean v) {
        sp.edit().putBoolean("nommap", v).apply();
    }

    public void setCustom() {
        sp.edit().putString("quant", "custom.gguf").apply();
    }
    public boolean darkTheme() {
        return sp.getBoolean("dark", false);
    }

    public void setDarkTheme(boolean v) {
        sp.edit().putBoolean("dark", v).apply();
    }
}