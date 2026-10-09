package com.alalay.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alalay.app.agent.AgentLoop
import com.alalay.app.diagnostics.DiagnosticsBus
import com.alalay.app.speech.Speaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Screen { HOME, DIAGNOSTICS, SETUP }

/**
 * UI state + the agent. The hold-to-talk button calls press/release;
 * every screen reads real state, nothing is mocked (R-38).
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val agent = AlalayAgent(app, viewModelScope)

    private val _screen = MutableStateFlow(Screen.SETUP)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _typedCommand = MutableStateFlow("")
    val typedCommand: StateFlow<String> = _typedCommand.asStateFlow()

    private val _aliasForm = MutableStateFlow(AliasForm())
    val aliasForm: StateFlow<AliasForm> = _aliasForm.asStateFlow()

    private val _teachBusy = MutableStateFlow(false)
    val teachBusy: StateFlow<Boolean> = _teachBusy.asStateFlow()

    private val _micGranted = MutableStateFlow(false)
    val micGranted: StateFlow<Boolean> = _micGranted.asStateFlow()

    private val _modelMessage = MutableStateFlow("")
    val modelMessage: StateFlow<String> = _modelMessage.asStateFlow()

    fun onMicResult(granted: Boolean) {
        _micGranted.value = granted
    }

    /** SAF import: copy a picked model file into app-private storage (06-TRD §6). */
    fun importModel(uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dir = java.io.File(getApplication<Application>().filesDir, "models")
                dir.mkdirs()
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "model.bin"
                val out = java.io.File(dir, name.substringAfterLast(':'))
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { ins ->
                    out.outputStream().use { ins.copyTo(it) }
                } ?: error("couldn't open file")
                _modelMessage.value = "Model saved: ${out.name}"
            } catch (e: Exception) {
                _modelMessage.value = "Import failed: ${e.message ?: "unknown"}"
            }
            agent.refreshDiagnostics()
        }
    }

    val diag = DiagnosticsBus.state
    val pipeline: StateFlow<AgentLoop.AgentState> = loop_state()

    private fun loop_state() = agent.loop.state
    val lastSpoken get() = agent.loop.lastSpoken

    init {
        // First-run: if prerequisites are missing, land on setup (FR-14).
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            agent.refreshDiagnostics()
            if (setupComplete()) _screen.value = Screen.HOME
        }
    }

    fun navigate(to: Screen) {
        _screen.value = to
        agent.refreshDiagnostics()
    }

    fun onPttDown() {
        agent.onPttDown()
    }

    fun onPttUp() {
        agent.onPttUp()
    }

    fun onStop() = agent.stop()

    fun onTypedCommandChange(t: String) {
        _typedCommand.value = t
    }

    fun submitTyped() {
        val t = _typedCommand.value.trim()
        if (t.isEmpty()) return
        _transcript.value = t
        _typedCommand.value = ""
        agent.submitText(t)
    }

    fun setupComplete(): Boolean {
        val d = diag.value
        return d.accessibilityOn && d.ttsReady
    }

    // ── Alias teach flow (FR-10; write only after spoken yes) ──────

    data class AliasForm(val alias: String = "", val target: String = "", val message: String = "")

    fun onAliasChange(alias: String, target: String) {
        _aliasForm.value = _aliasForm.value.copy(alias = alias, target = target)
    }

    fun teachAlias() {
        val form = _aliasForm.value
        if (form.alias.isBlank() || form.target.isBlank()) {
            _aliasForm.value = form.copy(message = "Enter a name and who it means first.")
            return
        }
        _teachBusy.value = true
        viewModelScope.launch {
            val ok = agent.askYesNo("Save ${form.alias} as ${form.target}? Say yes or no.")
            if (ok) {
                agent.saveAlias(form.alias, form.target, "contact")
                _aliasForm.value = AliasForm(message = "Saved: ${form.alias} is ${form.target}.")
                agent.speaker.speak(com.alalay.app.agent.Tpl.DONE, "summary" to "saved ${form.alias}")
            } else {
                _aliasForm.value = form.copy(message = "Not saved.")
            }
            _teachBusy.value = false
        }
    }

    override fun onCleared() {
        agent.onDestroy()
        super.onCleared()
    }
}
