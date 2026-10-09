package com.alalay.app.speech

/**
 * JNI wrapper for libalalay_whisper (vendored whisper.cpp, DEC-07).
 * `load()` returns null when the .so or the model can't initialize, so
 * callers degrade to SpeechRecognizerVoiceInput honestly.
 */
object WhisperNative {
    external fun nativeInit(path: String): Long
    external fun nativeTranscribe(handle: Long, pcm: FloatArray, nThreads: Int, lang: String?): String?
    external fun nativeAbort(handle: Long)
    external fun nativeFree(handle: Long)
}

class WhisperEngine private constructor(private val handle: Long) : AutoCloseable {

    val isLoaded get() = handle != 0L

    /** pcm: 16 kHz mono float samples in [-1, 1]. lang null = auto-detect. */
    fun transcribe(pcm: FloatArray, nThreads: Int = 4, lang: String? = null): String? =
        if (handle == 0L) null else WhisperNative.nativeTranscribe(handle, pcm, nThreads, lang)

    fun abort() {
        if (handle != 0L) WhisperNative.nativeAbort(handle)
    }

    override fun close() {
        if (handle != 0L) WhisperNative.nativeFree(handle)
    }

    companion object {
        fun load(modelPath: String): WhisperEngine? = try {
            System.loadLibrary("alalay_whisper")
            WhisperEngine(WhisperNative.nativeInit(modelPath)).takeIf { it.isLoaded }
        } catch (t: Throwable) {
            null
        }
    }
}
