// JNI bridge to vendored llama.cpp (DEC-06). One handle = one loaded
// model + context; generate() runs a bounded greedy decode and honors
// the abort flag for FR-09 cancellation.
#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define TAG "alalay_llama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct LlamaHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::atomic<bool> abort{false};
};

std::string formatPrompt(LlamaHandle * h, const std::string & sys, const std::string & user) {
    std::vector<llama_chat_message> msgs;
    if (!sys.empty()) msgs.push_back({ "system", sys.c_str() });
    msgs.push_back({ "user", user.c_str() });
    const char * tmpl = llama_model_chat_template(h->model, nullptr);
    if (tmpl) {
        int32_t n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, nullptr, 0);
        if (n > 0) {
            std::string buf(n, '\0');
            if (llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), n) > 0) {
                return buf;
            }
        }
    }
    // No usable built-in template: ChatML fallback (MiniCPM5 family format).
    std::string out;
    if (!sys.empty()) out += "<|im_start|>system\n" + sys + "<|im_end|>\n";
    out += "<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
    return out;
}

bool abortCb(void * data) {
    auto * h = reinterpret_cast<LlamaHandle*>(data);
    return h && h->abort.load();
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_alalay_app_agent_LlamaNative_nativeInit(
        JNIEnv * env, jobject, jstring path, jint nCtx, jint nThreads) {
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    const char * p = env->GetStringUTFChars(path, nullptr);
    llama_model * model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    if (!model) { LOGE("model load failed"); return 0; }

    auto * h = new LlamaHandle();
    h->model = model;
    h->vocab = llama_model_get_vocab(model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_batch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    // Abort mid-decode (incl. prompt eval), not just between tokens — FR-09.
    cp.abort_callback = abortCb;
    cp.abort_callback_data = h;
    h->ctx = llama_init_from_model(model, cp);
    if (!h->ctx) { LOGE("context init failed"); llama_model_free(model); delete h; return 0; }

    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL
Java_com_alalay_app_agent_LlamaNative_nativeGenerate(
        JNIEnv * env, jobject, jlong ptr, jstring jsys, jstring juser, jint maxTokens) {
    auto * h = reinterpret_cast<LlamaHandle*>(ptr);
    if (!h) return nullptr;
    h->abort = false;

    const char * sys = jsys ? env->GetStringUTFChars(jsys, nullptr) : nullptr;
    const char * usr = env->GetStringUTFChars(juser, nullptr);
    std::string formatted = formatPrompt(h, sys ? sys : "", usr ? usr : "");
    if (sys) env->ReleaseStringUTFChars(jsys, sys);
    env->ReleaseStringUTFChars(juser, usr);

    // Fresh KV state per call: planning calls are independent (07-§8).
    llama_memory_clear(llama_get_memory(h->ctx), true);

    int32_t cap = (int32_t) formatted.size() + 16;
    std::vector<llama_token> tokens(cap);
    int32_t ntok = llama_tokenize(h->vocab, formatted.c_str(), (int32_t) formatted.size(),
                                  tokens.data(), cap, true, true);
    if (ntok < 0) {
        tokens.resize(-ntok);
        ntok = llama_tokenize(h->vocab, formatted.c_str(), (int32_t) formatted.size(),
                              tokens.data(), -ntok, true, true);
        if (ntok < 0) { LOGE("tokenize failed"); return nullptr; }
    }
    tokens.resize(ntok);
    if (ntok == 0) return nullptr;

    if (llama_decode(h->ctx, llama_batch_get_one(tokens.data(), ntok)) != 0) {
        LOGE("prompt decode failed");
        return nullptr;
    }

    llama_sampler * smpl = llama_sampler_init_greedy();
    std::string out;
    char piece[256];
    for (int i = 0; i < maxTokens && !h->abort.load(); i++) {
        llama_token t = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, t)) break;
        int n = llama_token_to_piece(h->vocab, t, piece, sizeof(piece), 0, true);
        if (n > 0) out.append(piece, n);
        llama_sampler_accept(smpl, t);
        if (llama_decode(h->ctx, llama_batch_get_one(&t, 1)) != 0) break;
    }
    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_alalay_app_agent_LlamaNative_nativeAbort(JNIEnv *, jobject, jlong ptr) {
    auto * h = reinterpret_cast<LlamaHandle*>(ptr);
    if (h) h->abort = true;
}

JNIEXPORT void JNICALL
Java_com_alalay_app_agent_LlamaNative_nativeFree(JNIEnv *, jobject, jlong ptr) {
    auto * h = reinterpret_cast<LlamaHandle*>(ptr);
    if (!h) return;
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

} // extern "C"
