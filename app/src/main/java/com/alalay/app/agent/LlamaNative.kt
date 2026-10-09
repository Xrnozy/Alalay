package com.alalay.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** JNI entry points for libalalay_llama (vendored llama.cpp, DEC-06). */
object LlamaNative {
    external fun nativeInit(path: String, nCtx: Int, nThreads: Int): Long
    external fun nativeGenerate(handle: Long, system: String?, user: String, maxTokens: Int): String?
    external fun nativeAbort(handle: Long)
    external fun nativeFree(handle: Long)
}

/**
 * LlmRuntime backed by vendored llama.cpp — loads a GGUF planner model
 * (MiniCPM5-1B Q4_K_M per DEC-06) and runs bounded greedy generation.
 * The strict alalay.action.v1 contract check happens in the planner.
 */
class LlamaCppRuntime(
    private val nCtx: Int = 2048,
    private val nThreads: Int = 4,
    private val maxTokens: Int = 160,
) : LlmRuntime {

    private var handle: Long = 0
    override val label: String get() = "llama.cpp"

    override fun load(modelPath: String): Boolean {
        if (handle != 0L) return true
        return try {
            System.loadLibrary("alalay_llama")
            handle = LlamaNative.nativeInit(modelPath, nCtx, nThreads)
            handle != 0L
        } catch (t: Throwable) {
            false
        }
    }

    override suspend fun infer(prompt: String): String = withContext(Dispatchers.Default) {
        check(handle != 0L) { "llama model not loaded" }
        // Whole prompt goes in the user turn; the JNI layer applies the
        // model's chat template (or ChatML fallback) around it.
        LlamaNative.nativeGenerate(handle, null, prompt, maxTokens)
            ?: throw IllegalStateException("llama inference failed")
    }

    override fun abort() {
        if (handle != 0L) LlamaNative.nativeAbort(handle)
    }
}
