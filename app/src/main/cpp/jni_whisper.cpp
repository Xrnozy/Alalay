// JNI bridge to vendored whisper.cpp (DEC-07). One handle = one loaded
// ggml model; transcribe() takes 16 kHz mono float PCM and honors the
// ggml abort callback for FR-09 cancellation.
#include <jni.h>
#include <atomic>
#include <string>
#include <android/log.h>
#include "whisper.h"

#define TAG "alalay_whisper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct WhisperHandle {
    whisper_context * ctx = nullptr;
    std::atomic<bool> abort{false};
};

bool abortCb(void * data) {
    auto * h = reinterpret_cast<WhisperHandle*>(data);
    return h && h->abort.load();
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_alalay_app_speech_WhisperNative_nativeInit(JNIEnv * env, jobject, jstring path) {
    const char * p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    whisper_context * ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    if (!ctx) { LOGE("model load failed"); return 0; }
    auto * h = new WhisperHandle();
    h->ctx = ctx;
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL
Java_com_alalay_app_speech_WhisperNative_nativeTranscribe(
        JNIEnv * env, jobject, jlong ptr, jfloatArray jpcm, jint nThreads, jstring jlang) {
    auto * h = reinterpret_cast<WhisperHandle*>(ptr);
    if (!h || !h->ctx) return nullptr;
    h->abort = false;

    jsize n = env->GetArrayLength(jpcm);
    if (n <= 0) return env->NewStringUTF("");
    jfloat * pcm = env->GetFloatArrayElements(jpcm, nullptr);
    if (!pcm) return nullptr;

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads = nThreads;
    wp.translate = false;
    wp.no_context = true;
    wp.no_timestamps = true;
    wp.single_segment = true;
    wp.print_realtime = false;
    wp.print_progress = false;
    wp.print_timestamps = false;
    wp.suppress_nst = true;
    wp.abort_callback = abortCb;
    wp.abort_callback_user_data = h;
    const char * lang = jlang ? env->GetStringUTFChars(jlang, nullptr) : nullptr;
    wp.language = (lang && lang[0]) ? lang : nullptr; // nullptr -> auto-detect (Taglish)

    int rc = whisper_full(h->ctx, wp, pcm, n);
    env->ReleaseFloatArrayElements(jpcm, pcm, JNI_ABORT);
    if (lang) env->ReleaseStringUTFChars(jlang, lang);
    if (rc != 0 || h->abort.load()) { if (rc != 0) LOGE("whisper_full rc=%d", rc); return nullptr; }

    std::string out;
    int ns = whisper_full_n_segments(h->ctx);
    for (int i = 0; i < ns; i++) {
        const char * t = whisper_full_get_segment_text(h->ctx, i);
        if (t) out += t;
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_alalay_app_speech_WhisperNative_nativeAbort(JNIEnv *, jobject, jlong ptr) {
    auto * h = reinterpret_cast<WhisperHandle*>(ptr);
    if (h) h->abort = true;
}

JNIEXPORT void JNICALL
Java_com_alalay_app_speech_WhisperNative_nativeFree(JNIEnv *, jobject, jlong ptr) {
    auto * h = reinterpret_cast<WhisperHandle*>(ptr);
    if (!h) return;
    if (h->ctx) whisper_free(h->ctx);
    delete h;
}

} // extern "C"
