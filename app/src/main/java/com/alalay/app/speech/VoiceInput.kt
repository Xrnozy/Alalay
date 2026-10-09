package com.alalay.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Handler
import android.os.Looper
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.alalay.app.agent.VoiceCapture
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * VoiceInput (FR-01): push-to-talk capture returning a transcript.
 *
 * Primary path per spec is whisper.cpp; this build ships the spec's
 * documented fallback (DEC-07, 06-TRD §2): Android SpeechRecognizer with
 * `preferOffline`, which uses the on-device recognition pack. The Whisper
 * seam stays in `WhisperVoiceInput` for when the JNI lib is bundled.
 */
class SpeechRecognizerVoiceInput(
    private val context: Context,
    private val maxSeconds: Int = 10,
) : VoiceCapture {

    @Volatile private var recognizer: SpeechRecognizer? = null
    @Volatile private var cancelled = false
    private val main = Handler(Looper.getMainLooper())

    // SpeechRecognizer may only be created/driven on the main thread —
    // the agent loop calls capture() from a worker dispatcher.
    override suspend fun capture(): String = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext ""
        cancelled = false
        suspendCancellableCoroutine { cont ->
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = sr
            var best = ""

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en_PH")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1_500L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1_500L
                )
            }

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val part = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!part.isNullOrBlank()) best = part
                }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: best
                    finish(sr, text.orEmpty())
                }
                override fun onError(error: Int) {
                    finish(sr, if (error == SpeechRecognizer.ERROR_CLIENT && cancelled) "" else best)
                }

                private fun finish(r: SpeechRecognizer, text: String) {
                    r.destroy()
                    recognizer = null
                    if (cont.isActive) cont.resume(if (cancelled) "" else text)
                }
            })

            cont.invokeOnCancellation {
                cancelled = true
                sr.cancel()
                sr.destroy()
                recognizer = null
            }

            sr.startListening(intent)
        }
    }

    override fun finish() {
        main.post { recognizer?.stopListening() }
    }

    override fun stop() {
        cancelled = true
        main.post {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        }
    }
}

/**
 * whisper.cpp input (DEC-07 primary). Records PCM via AudioRecord while
 * PTT is held, then transcribes the whole utterance with the bundled
 * ggml model. Falls back upstream to SpeechRecognizerVoiceInput when the
 * native lib or model is absent.
 */
class WhisperVoiceInput(
    private val modelFileProvider: () -> java.io.File?,
    private val stagingDone: Deferred<Unit>? = null,
    private val bundledModel: () -> Boolean = { false },
    private val maxSeconds: Int = 10,
) : VoiceCapture {

    @Volatile private var engine: WhisperEngine? = null
    @Volatile private var engineTried = false
    @Volatile private var finishing = false
    @Volatile private var cancelled = false
    @Volatile private var recorder: AudioRecord? = null

    /** True when the .so loads and a model is staged now or bunded for staging. */
    val isAvailable: Boolean
        get() = nativeLibPresent() && (modelFileProvider() != null || bundledModel())

    private fun nativeLibPresent(): Boolean = try {
        System.loadLibrary("alalay_whisper")
        true
    } catch (t: Throwable) {
        false
    }

    private fun engine(): WhisperEngine? {
        if (engine == null && !engineTried) {
            engineTried = true
            engine = modelFileProvider()?.absolutePath?.let { WhisperEngine.load(it) }
        }
        return engine
    }

    override suspend fun capture(): String = withContext(Dispatchers.IO) {
        // First launch: wait for ModelStore to finish extracting assets so
        // the whisper model file exists before we look for it.
        try {
            stagingDone?.let { kotlinx.coroutines.withTimeoutOrNull(120_000) { it.await() } }
        } catch (t: Throwable) { /* staging failure is handled below by absent model */ }
        val eng = engine() ?: return@withContext ""

        cancelled = false
        finishing = false
        val pcm = recordPcm()
        if (cancelled || pcm.isEmpty()) return@withContext ""
        try {
            eng.transcribe(pcm, nThreads = 4)?.trim().orEmpty()
        } finally {
            // Keep the model resident: reload cost dwarfs its ~190 MB RSS.
        }
    }

    /** Block until PTT release / stop / [maxSeconds], then return PCM floats. */
    private fun recordPcm(): FloatArray {
        val rate = 16_000
        val cap = rate * maxSeconds
        val bufSz = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSz,
            )
        } catch (e: SecurityException) {
            return FloatArray(0)
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return FloatArray(0)
        }
        recorder = rec
        val shorts = ShortArray(cap)
        var total = 0
        val chunk = ShortArray(2048)
        try {
            rec.startRecording()
            while (!finishing && !cancelled && total < cap) {
                val n = rec.read(chunk, 0, minOf(chunk.size, cap - total))
                if (n <= 0) continue
                chunk.copyInto(shorts, destinationOffset = total, startIndex = 0, endIndex = n)
                total += n
            }
        } finally {
            try { rec.stop() } catch (t: Throwable) {}
            rec.release()
            recorder = null
        }
        if (cancelled || total < rate / 4) return FloatArray(0) // <250 ms = tap, not speech
        return FloatArray(total) { i -> shorts[i] / 32768f }
    }

    override fun finish() {
        finishing = true
    }

    override fun stop() {
        cancelled = true
        finishing = true
        engine?.abort()
        try { recorder?.stop() } catch (t: Throwable) {}
    }
}
