package com.alalay.app.agent

/**
 * Everything a planner gets to see for one step (07-§4 prompt slots).
 * Deliberately narrow: no history, no message bodies beyond the current
 * read result, no screens from earlier commands (D-7).
 */
data class PlanContext(
    val task: String,
    val aliasHints: String,
    val memoryLine: String,
    val screenDump: String,
    val lastResult: String,
    val step: Int,
    val maxSteps: Int,
    val failureHint: String?,
    /** Tool types executed so far — types only, never content (D-7). */
    val executedTools: List<Tool> = emptyList(),
)

sealed interface PlanOutcome {
    data class Action(val action: ActionV1) : PlanOutcome

    /** Model replied but the contract parse failed — worth one retry. */
    data class ParseFailed(val detail: String) : PlanOutcome

    /** Planner backend missing/failed (no model file, native lib absent). */
    data class Unavailable(val reason: String) : PlanOutcome
}

interface Planner {
    /** Human-readable identity shown on the diagnostics screen (FR-11). */
    val identity: String

    /** True when the backend can actually produce steps. */
    val isReady: Boolean

    suspend fun planStep(ctx: PlanContext): PlanOutcome

    /** Cooperative abort for mid-inference cancel (NFR-07). */
    fun cancel()
}
