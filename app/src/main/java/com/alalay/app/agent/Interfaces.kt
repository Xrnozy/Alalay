package com.alalay.app.agent

/**
 * Seams between the agent loop and the device. Implementations live in
 * `device/` and `speech/`; unit tests substitute fakes — this is what
 * keeps the whole pipeline JVM-testable.
 */

interface ScreenSource {
    suspend fun dump(): ScreenModel
}

interface ActionPerformer {
    suspend fun perform(action: ActionV1): ExecOutcome
    suspend fun confirmSend(): ExecOutcome
    suspend fun clearDraft()
    fun serviceUp(): Boolean
}

sealed interface ExecOutcome {
    data class Ok(val summary: String) : ExecOutcome
    data class Read(
        val speaker: String?,
        val messageText: String?,
        val items: List<String>,
        val appLabel: String,
    ) : ExecOutcome
    data class Failed(val reason: String) : ExecOutcome
    data object ServiceUnavailable : ExecOutcome
}

interface SpokenOutput {
    val language: String
    fun speak(template: Tpl, vararg args: Pair<String, String>)
    fun say(text: String)
    fun stop()
    fun repeat()
}

interface VoiceCapture {
    /** Record ≤10 s of speech and return the transcript ("" on silence). */
    suspend fun capture(): String

    /** PTT released: end the utterance now and deliver the transcript. */
    fun finish() {}

    /** Hard cancel — discard everything (FR-09). */
    fun stop()
}

interface MemoryAccess {
    fun resolveAlias(name: String): List<String>
    fun aliasHintsFor(task: String): String
    fun memoryLine(): String
    fun language(): String
    suspend fun saveAlias(alias: String, target: String, kind: String)
    suspend fun setRolling(key: String, value: String)
}
