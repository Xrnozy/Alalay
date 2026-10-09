package com.alalay.app.speech

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.alalay.app.agent.SpokenOutput
import com.alalay.app.agent.TemplateRenderer
import com.alalay.app.agent.Tpl
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaker (FR-06): Android TextToSpeech with an installed offline voice,
 * barge-in stop, and the TPL template table from 07-§10.
 */
class Speaker(
    context: Context,
    override var language: String = "en",
    private val onReady: ((Boolean) -> Unit)? = null,
) : SpokenOutput {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile var lastUtterance: String = ""
        private set

    private val ids = AtomicInteger(0)
    private var completion: CompletableDeferred<Unit>? = null

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) { completion?.complete(Unit) }
        @Deprecated("deprecated in favor of onError(String, Int)")
        override fun onError(id: String?) { completion?.complete(Unit) }
        override fun onError(id: String?, errorCode: Int) { completion?.complete(Unit) }
    }

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val t = tts ?: return@TextToSpeech
                // Prefer an installed (offline) voice for the session language.
                val fil = Locale("fil", "PH")
                val eng = Locale.ENGLISH
                val voiceSet = t.voices?.filter { !it.isNetworkConnectionRequired }
                val target = if (language.startsWith("tl") || language.startsWith("fil")) {
                    voiceSet?.firstOrNull { it.locale.language == "fil" || it.locale.language == "tl" }
                        ?: voiceSet?.firstOrNull { it.locale.language == "en" }
                } else {
                    voiceSet?.firstOrNull { it.locale.language == "en" }
                        ?: voiceSet?.firstOrNull()
                }
                target?.let { t.voice = it }
                if (t.setLanguage(if (language.startsWith("tl")) fil else eng)
                    == TextToSpeech.LANG_MISSING_DATA
                ) ready = false
                t.setOnUtteranceProgressListener(listener)
            }
            onReady?.invoke(ready)
        }
    }

    fun isReady(): Boolean = ready

    /** An installed (non-network) voice exists — the DEC-08 check. */
    fun hasOfflineVoice(): Boolean =
        tts?.voices?.any { !it.isNetworkConnectionRequired } == true

    private var tones: ToneGenerator? = null

    /** Short cue tones — PTT listening start/end (03-§reading convention). */
    fun chime(start: Boolean) {
        try {
            val t = tones ?: ToneGenerator(AudioManager.STREAM_MUSIC, 60).also { tones = it }
            t.startTone(
                if (start) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, 120
            )
        } catch (_: Exception) { /* chime is best-effort */ }
    }

    override fun speak(template: Tpl, vararg args: Pair<String, String>) {
        say(TemplateRenderer.render(template, language, *args))
    }

    override fun say(text: String) {
        lastUtterance = text
        val engine = tts ?: return
        if (!ready) return
        val id = "alalay-${ids.incrementAndGet()}"
        val params = Bundle()
        engine.speak(text, TextToSpeech.QUEUE_ADD, params, id)
    }

    /** Speak and suspend until the utterance finishes (ordered speech). */
    suspend fun sayAndAwait(text: String) {
        val engine = tts
        if (engine == null || !ready) { lastUtterance = text; return }
        lastUtterance = text
        val done = CompletableDeferred<Unit>()
        completion = done
        val id = "alalay-${ids.incrementAndGet()}"
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        try { done.await() } finally { completion = null }
    }

    /** Barge-in stop (FR-09): cuts playback immediately. */
    override fun stop() {
        tts?.stop()
        completion?.complete(Unit)
    }

    override fun repeat() {
        if (lastUtterance.isNotEmpty()) say(lastUtterance)
    }

    fun shutdown() {
        tones?.release()
        tones = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
