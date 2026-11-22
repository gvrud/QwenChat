package com.example.qwenchat;

/** JNI bridge to libqwen.so (llama.cpp). All heavy calls block the calling thread. */
public final class Qwen {

    public interface TokenSink {
        void onToken(String text);
    }

    private static boolean loaded;
    private static String loadError;

    // Loaded once at class init: calling a native method before loadLibrary is an
    // UnsatisfiedLinkError, and an uncaught one on a worker thread kills the app.
    static {
        try {
            System.loadLibrary("qwen");
            loaded = true;
        } catch (Throwable e) {
            loadError = String.valueOf(e);
        }
    }

    public static String info() {
        if (!loaded) return "нативная библиотека не загружена: " + loadError;
        try {
            return nativeInfo();
        } catch (Throwable e) {
            return String.valueOf(e);
        }
    }

    public static long load(String path, int nCtx, int threads, boolean mmap) {
        if (!loaded) throw new IllegalStateException("нативная библиотека не загружена: " + loadError);
        return nativeLoad(path, nCtx, threads, mmap);
    }

    public static int ctxSize(long h) {
        return loaded ? nativeCtxSize(h) : 0;
    }

    public static int countTokens(long h, String text) {
        return loaded ? nativeCountTokens(h, text) : 0;
    }

    public static void reset(long h) {
        if (loaded) nativeReset(h);
    }

    public static String generate(long h, String prompt, int maxTokens, float temp,
                                  boolean[] cancel, TokenSink sink) {
        if (!loaded) throw new IllegalStateException("нативная библиотека не загружена: " + loadError);
        return nativeGenerate(h, prompt, maxTokens, temp, cancel, sink);
    }

    public static void free(long h) {
        if (loaded && h != 0) nativeFree(h);
    }

    private static native String nativeInfo();
    private static native long nativeLoad(String path, int nCtx, int threads, boolean useMmap);
    private static native int nativeCtxSize(long h);
    private static native int nativeCountTokens(long h, String text);
    private static native void nativeReset(long h);
    private static native String nativeGenerate(long h, String prompt, int maxTokens, float temp,
                                                boolean[] cancel, TokenSink sink);
    private static native void nativeFree(long h);

    private Qwen() {
    }
}
