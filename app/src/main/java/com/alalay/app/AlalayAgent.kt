package com.alalay.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.alalay.app.agent.AgentLoop
import com.alalay.app.agent.LiteRtLmPlanner
import com.alalay.app.agent.LlamaCppRuntime
import com.alalay.app.agent.LlmRuntime
import com.alalay.app.agent.Orchestrator
import com.alalay.app.agent.RouteResult
import com.alalay.app.agent.Router
import com.alalay.app.agent.RouterMode
import com.alalay.app.agent.RulesPlanner
import com.alalay.app.agent.Tpl
import com.alalay.app.device.Executor
import com.alalay.app.device.ScreenInspector
import com.alalay.app.diagnostics.DiagnosticsBus
import com.alalay.app.memory.MemoryStore
import com.alalay.app.models.ModelStore
import com.alalay.app.speech.Speaker
import com.alalay.app.speech.SpeechRecognizerVoiceInput
import com.alalay.app.speech.WhisperVoiceInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * App-level wiring: VoiceInput → Router → AgentLoop → SafetyGate →
 * Executor, with Speaker feedback and diagnostics throughout. All work
 * runs on the injected scope; the UI never blocks (04-§4).
 */
class AlalayAgent(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    val speaker = Speaker(context, onReady = { DiagnosticsBus.setTtsReady(it) })
    private val memory = MemoryStore(context)
    val inspector = ScreenInspector()
    private val executor = Executor(context, inspector)
    private val modelStore = ModelStore(
        context,
        expectedSha256 = mapOf(
            "MiniCPM5-1B-Q4_K_M.gguf" to
                "81b64d05a23b17b34c475f42b3e72fbde62d4b92cc34541f7a8031d0752deafa",
            "ggml-small-q5_1.bin" to
                "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        ),
    )
    private val stagingDone = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val voice = pickStt()

    private val llm = LiteRtLmPlanner(modelStore.modelsDir, runtime = llamaRuntimeOrNull())
    private val orchestrator = Orchestrator(llm, RulesPlanner())

    val loop = AgentLoop(
        voice = voice,
        screen = inspector,
        executor = executor,
        speaker = speaker,
        memory = memory,
        planner = orchestrator,
        diagnostics = object : AgentLoop.Diagnostics {
            override fun pipeline(state: AgentLoop.AgentState) =
                DiagnosticsBus.setPipeline(state.name)
            override fun inference(ms: Long) = DiagnosticsBus.recordInference(ms)
            override fun actionType(name: String) = DiagnosticsBus.log(name)
        },
        scope = scope,
    )

    private var pttJob: Job? = null

    init {
        DiagnosticsBus.setModel(orchestrator.identity, modelFilePresent())
        DiagnosticsBus.setSttEngine(
            if (voice is WhisperVoiceInput) "whisper.cpp" else "SpeechRecognizer (preferOffline)"
        )
        refreshDiagnostics()
        scope.launch {
            // One-time: extract bundled weights from assets → files/models/.
            try {
                modelStore.stageAll()
            } finally {
                stagingDone.complete(Unit)
            }
            memory.refreshFromDb()
            refreshDiagnostics()
        }
    }

    private fun llamaRuntimeOrNull(): LlmRuntime? = try {
        LlamaCppRuntime()
    } catch (t: Throwable) {
        null
    }

    private fun pickStt() = WhisperVoiceInput(
        modelFileProvider = { modelStore.sttModel() },
        stagingDone = stagingDone,
        bundledModel = { modelStore.bundledAssetNames().any { it.endsWith(".bin", true) } },
    )
        .takeIf { it.isAvailable }
        ?: SpeechRecognizerVoiceInput(context)

    private fun modelFilePresent(): Boolean =
        modelStore.plannerModel() != null || modelStore.sttModel() != null

    /** Poll cheap status signals; called on resume and after actions. */
    fun refreshDiagnostics() {
        DiagnosticsBus.setAccessibility(inspector.isAvailable())
        DiagnosticsBus.setNetworkOnline(isOnline())
        DiagnosticsBus.setTtsReady(speaker.isReady() && speaker.hasOfflineVoice())
        DiagnosticsBus.setModel(orchestrator.identity, modelFilePresent())
        DiagnosticsBus.setSttEngine(
            if (voice is WhisperVoiceInput) "whisper.cpp" else "SpeechRecognizer (preferOffline)"
        )
        speaker.language = memory.language()
    }

    private fun isOnline(): Boolean {
        // Diagnostics probe — must never take the app down (OEM ROMs
        // have thrown here even with the permission declared).
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: SecurityException) {
            false
        }
    }

    // ── Push-to-talk (FR-01, DEC-10 on-screen hold button) ─────────

    fun onPttDown() {
        if (loop.state.value != AgentLoop.AgentState.IDLE) return
        loop.beginListening()
        speaker.chime(true)
        speaker.speak(Tpl.LISTENING)
        pttJob = scope.launch {
            val t0 = System.nanoTime()
            val transcript = voice.capture()
            DiagnosticsBus.recordStt((System.nanoTime() - t0) / 1_000_000)
            speaker.chime(false)
            loop.submitTranscript(transcript)
        }
    }

    fun onPttUp() {
        // Release ends the utterance; SpeechRecognizer delivers the result.
        voice.finish()
    }

    /** Typed-command path (dev/tests; also the no-mic fallback). */
    fun submitText(text: String) = loop.submitTranscript(text)

    /** Spoken yes/no gate — used by the alias teach flow (FR-10, 07-§9). */
    suspend fun askYesNo(question: String): Boolean {
        speaker.say(question)
        val answer = voice.capture()
        return Router.route(answer, RouterMode.AWAITING_CONFIRMATION) == RouteResult.Confirm
    }

    suspend fun saveAlias(alias: String, target: String, kind: String) =
        memory.saveAlias(alias, target, kind)

    fun stop() {
        pttJob?.cancel()
        loop.stop()
    }

    fun route(text: String) = Router.route(text)

    fun onDestroy() {
        stop()
        speaker.shutdown()
    }
}
