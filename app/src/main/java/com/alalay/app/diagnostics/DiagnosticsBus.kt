package com.alalay.app.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque

/**
 * DiagnosticsBus (FR-11): model identity, inference latency, network
 * status, pipeline state. NFR-03: event log records action TYPES and
 * element counts only — never message text or contact names.
 */
object DiagnosticsBus {

    data class State(
        val modelIdentity: String = "not loaded",
        val modelFilePresent: Boolean = false,
        val lastInferenceMs: Long? = null,
        val lastSttMs: Long? = null,
        val lastRouterMs: Long? = null,
        val sttEngine: String = "SpeechRecognizer (on-device)",
        val networkOnline: Boolean = false,
        val accessibilityOn: Boolean = false,
        val ttsReady: Boolean = false,
        val pipeline: String = "IDLE",
        val lastElementCount: Int = 0,
        val events: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private const val MAX_EVENTS = 30
    private val ring = ArrayDeque<String>()

    fun recordInference(ms: Long) = _state.update { it.copy(lastInferenceMs = ms) }
    fun recordStt(ms: Long) = _state.update { it.copy(lastSttMs = ms) }
    fun recordRouter(ms: Long) = _state.update { it.copy(lastRouterMs = ms) }

    fun setModel(identity: String, filePresent: Boolean) =
        _state.update { it.copy(modelIdentity = identity, modelFilePresent = filePresent) }

    fun setNetworkOnline(online: Boolean) =
        _state.update { it.copy(networkOnline = online) }

    fun setAccessibility(on: Boolean) =
        _state.update { it.copy(accessibilityOn = on) }

    fun setTtsReady(ready: Boolean) = _state.update { it.copy(ttsReady = ready) }
    fun setPipeline(name: String) = _state.update { it.copy(pipeline = name) }
    fun setElementCount(n: Int) = _state.update { it.copy(lastElementCount = n) }
    fun setSttEngine(name: String) = _state.update { it.copy(sttEngine = name) }

    /** Type-only event log — pass action/verdict names, never content. */
    fun log(event: String) {
        synchronized(ring) {
            ring.addFirst(event.take(80))
            while (ring.size > MAX_EVENTS) ring.removeLast()
            _state.update { it.copy(events = ring.toList()) }
        }
    }
}
