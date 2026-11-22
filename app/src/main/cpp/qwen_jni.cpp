#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "qwen"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    const char * tmpl = nullptr;
    int n_ctx = 0;
    int pos = 0;
    std::atomic<bool> cancel{false};
};

JNIEnv * g_env = nullptr;
jobject g_cb = nullptr;
jmethodID g_on_token = nullptr;

std::string to_string(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    env->ReleaseStringUTFChars(s, c);
    return out;
}

jstring to_jstring(JNIEnv * env, const std::string & s) {
    return env->NewStringUTF(s.c_str());
}

void emit(Engine * e, const std::string & s) {
    if (s.empty() || g_cb == nullptr || g_on_token == nullptr) return;
    if (e->cancel.load()) return;
    jstring js = to_jstring(g_env, s);
    g_env->CallVoidMethod(g_cb, g_on_token, js);
    g_env->DeleteLocalRef(js);
}

std::vector<llama_token> tokenize(Engine * e, const std::string & text, bool add_special) {
    int n = -llama_tokenize(e->vocab, text.data(), (int32_t) text.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> out(n > 0 ? n : 0);
    if (n > 0) llama_tokenize(e->vocab, text.data(), (int32_t) text.size(), out.data(), n, add_special, true);
    return out;
}

int count_tokens(Engine * e, const std::string & text) {
    return (int) tokenize(e, text, false).size();
}

void reset_context(Engine * e) {
    if (e->ctx) llama_memory_clear(llama_get_memory(e->ctx), true);
    e->pos = 0;
}

// Runs one batch of tokens at explicit positions; the last token gets logits.
bool decode_chunk(Engine * e, const llama_token * tokens, int n, int pos0, bool want_logits) {
    llama_batch_ext * b = llama_batch_ext_init(e->ctx);
    for (int i = 0; i < n; i++) {
        const int idx = llama_batch_ext_add_token(b, 0, tokens[i]);
        if (idx < 0) {
            llama_batch_ext_free(b);
            return false;
        }
        const llama_pos pos = pos0 + i;
        llama_batch_ext_set_pos(b, idx, &pos);
        if (want_logits && i == n - 1) llama_batch_ext_set_output_logits(b, idx, true);
    }
    const int rc = llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, b);
    llama_batch_ext_free(b);
    return rc == 0;
}

bool decode_tokens(Engine * e, const std::vector<llama_token> & tokens, int pos0) {
    const int batch = 512;
    for (size_t i = 0; i < tokens.size(); i += batch) {
        const int n = (int) std::min<size_t>(batch, tokens.size() - i);
        if (!decode_chunk(e, tokens.data() + i, n, pos0 + (int) i, i + n == tokens.size())) {
            LOGE("llama_process failed at %zu", i);
            return false;
        }
    }
    return true;
}

std::string piece(Engine * e, llama_token t) {
    char buf[64];
    int n = llama_token_to_piece(e->vocab, t, buf, sizeof(buf), 0, false);
    if (n <= 0) return {};
    return std::string(buf, n);
}

struct Utf8Guard {
    std::string pending;
    static size_t complete_len(const std::string & s) {
        if (s.empty()) return 0;
        size_t i = s.size();
        while (i > 0 && ((unsigned char) s[i - 1] & 0xC0) == 0x80) i--;
        if (i == 0) return 0;
        unsigned char c = (unsigned char) s[i - 1];
        size_t need = c < 0x80 ? 1 : c < 0xE0 ? 2 : c < 0xF0 ? 3 : 4;
        if (s.size() - (i - 1) >= need) return s.size();
        return i - 1;
    }
    // Returns true and fills out with the complete UTF-8 text; a trailing partial
    // character stays in pending until its remaining bytes arrive.
    bool push(const std::string & p, std::string & out) {
        pending += p;
        size_t ok = complete_len(pending);
        if (ok < pending.size()) return false;
        out = pending;
        pending.clear();
        return true;
    }
};

} // namespace

extern "C" __attribute__((visibility("default"))) JNIEXPORT jstring JNICALL Java_com_example_qwenchat_Qwen_nativeInfo(JNIEnv * env, jobject) {
    char buf[128] = {0};
    snprintf(buf, sizeof(buf), "build=" __DATE__);
    return to_jstring(env, buf);
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT jlong JNICALL Java_com_example_qwenchat_Qwen_nativeLoad(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint threads, jboolean use_mmap) {
    llama_backend_init();

    auto * e = new Engine();
    e->n_ctx = n_ctx;

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = use_mmap == JNI_TRUE ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;

    std::string path = to_string(env, jpath);
    LOGI("loading %s ctx=%d threads=%d mmap=%d", path.c_str(), (int) n_ctx, (int) threads, (int) (use_mmap == JNI_TRUE));
    e->model = llama_model_load_from_file(path.c_str(), mp);
    if (!e->model) {
        LOGE("cannot load model %s", path.c_str());
        delete e;
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 256;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.type_k = GGML_TYPE_F16;
    cp.type_v = GGML_TYPE_F16;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) {
        LOGE("cannot create context");
        llama_model_free(e->model);
        delete e;
        return 0;
    }
    e->vocab = llama_model_get_vocab(e->model);
    e->tmpl = llama_model_chat_template(e->model, nullptr);
    if (e->tmpl == nullptr) e->tmpl = "chatml";
    LOGI("model loaded, n_ctx=%d", (int) llama_n_ctx(e->ctx));
    return (jlong) (intptr_t) e;
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT jint JNICALL Java_com_example_qwenchat_Qwen_nativeCtxSize(JNIEnv *, jobject, jlong h) {
    auto * e = (Engine *) (intptr_t) h;
    return e->ctx ? (jint) llama_n_ctx(e->ctx) : 0;
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT jint JNICALL Java_com_example_qwenchat_Qwen_nativeCountTokens(JNIEnv * env, jobject, jlong h, jstring text) {
    auto * e = (Engine *) (intptr_t) h;
    if (!e || !e->vocab) return 0;
    return count_tokens(e, to_string(env, text));
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT jstring JNICALL Java_com_example_qwenchat_Qwen_nativeApplyTemplate(JNIEnv * env, jobject, jlong h,
        jobjectArray roles, jobjectArray contents, jboolean add_ass) {
    auto * e = (Engine *) (intptr_t) h;
    if (!e) return to_jstring(env, "");

    jsize n = env->GetArrayLength(roles);
    std::vector<std::string> rs, cs;
    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; i++) {
        rs.push_back(to_string(env, (jstring) env->GetObjectArrayElement(roles, i)));
        cs.push_back(to_string(env, (jstring) env->GetObjectArrayElement(contents, i)));
    }
    size_t total = 0;
    for (auto & c : cs) total += c.size();
    for (jsize i = 0; i < n; i++) {
        msgs[i].role = rs[i].c_str();
        msgs[i].content = cs[i].c_str();
    }
    std::vector<char> buf(total * 2 + 256);
    int len = llama_chat_apply_template(e->tmpl, msgs.data(), (size_t) n, add_ass == JNI_TRUE, buf.data(), (int) buf.size());
    if (len < 0) {
        LOGE("apply_template failed");
        return to_jstring(env, "");
    }
    return to_jstring(env, std::string(buf.data(), (size_t) len));
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL Java_com_example_qwenchat_Qwen_nativeReset(JNIEnv *, jobject, jlong h) {
    auto * e = (Engine *) (intptr_t) h;
    if (!e) return;
    e->cancel.store(false);
    reset_context(e);
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL Java_com_example_qwenchat_Qwen_nativeCancel(JNIEnv *, jobject, jlong h) {
    auto * e = (Engine *) (intptr_t) h;
    if (e) e->cancel.store(true);
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT jstring JNICALL Java_com_example_qwenchat_Qwen_nativeGenerate(JNIEnv * env, jobject, jlong h,
        jstring jprompt, jint max_tokens, jfloat temp, jbooleanArray jcancel, jobject sink) {
    auto * e = (Engine *) (intptr_t) h;
    if (!e || !e->ctx) return nullptr;

    g_env = env;
    g_cb = sink;
    g_on_token = nullptr;
    if (sink != nullptr) {
        jclass cls = env->GetObjectClass(sink);
        g_on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (g_on_token == nullptr) {
            LOGE("sink has no onToken(String)");
            env->ExceptionClear();
        }
    }

    auto cancelled = [&]() {
        if (jcancel != nullptr) {
            jboolean c = JNI_FALSE;
            env->GetBooleanArrayRegion(jcancel, 0, 1, &c);
            if (c == JNI_TRUE) return true;
        }
        return e->cancel.load();
    };

    e->cancel.store(false);

    std::string prompt = to_string(env, jprompt);
    std::vector<llama_token> toks = tokenize(e, prompt, true);
    const int ctx = (int) llama_n_ctx(e->ctx);
    if (ctx <= 0) {
        LOGE("no context");
        return nullptr;
    }

    if ((int) toks.size() > ctx - max_tokens - 8) {
        const int keep = std::max(32, ctx - max_tokens - 8);
        toks.erase(toks.begin(), toks.end() - keep);
    }
    reset_context(e);

    if (!decode_tokens(e, toks, 0)) {
        LOGE("prompt decode failed, n=%d ctx=%d", (int) toks.size(), ctx);
        g_cb = nullptr;
        g_on_token = nullptr;
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "llama.cpp: не удалось обработать запрос (проверьте квантование)");
        return nullptr;
    }
    e->pos = (int) toks.size();

    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(llama_vocab_n_tokens(e->vocab), 64, 1.1f, 0.0f, 0.0f));
    if (temp <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    Utf8Guard guard;
    std::string acc;
    int generated = 0;
    while (generated < max_tokens && e->pos < ctx) {
        if (cancelled()) break;
        llama_token t = llama_sampler_sample(chain, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, t)) break;
        llama_sampler_accept(chain, t);

        std::string p = piece(e, t);
        std::string chunk;
        if (!p.empty() && guard.push(p, chunk)) {
            acc += chunk;
            emit(e, chunk);
        }

        if (!decode_chunk(e, &t, 1, e->pos, true)) {
            LOGE("decode failed at token %d", generated);
            break;
        }
        e->pos++;
        generated++;
    }
    if (!guard.pending.empty()) {
        acc += guard.pending;
        emit(e, guard.pending);
        guard.pending.clear();
    }
    llama_sampler_free(chain);
    g_cb = nullptr;
    g_on_token = nullptr;
    LOGI("generated %d tokens, %d bytes", generated, (int) acc.size());
    // A callback that threw would block the return value below, while the text is what matters.
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (generated == 0 && !cancelled()) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "модель сразу завершила ответ (0 токенов)");
        return nullptr;
    }
    if (acc.empty() && !cancelled()) {
        std::string msg = "модель выдала " + std::to_string(generated) +
                          " токенов, но текст пустой";
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), msg.c_str());
        return nullptr;
    }
    return to_jstring(env, acc);
}
extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL Java_com_example_qwenchat_Qwen_nativeFree(JNIEnv *, jobject, jlong h) {
    auto * e = (Engine *) (intptr_t) h;
    if (!e) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}
