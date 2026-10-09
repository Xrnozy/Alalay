package com.alalay.app.agent

import java.io.File

/**
 * Orchestrator (04-§3): owns the prompt assembly per 07-§4 and delegates
 * one-step planning to the active Planner. Output never reaches Executor
 * directly — everything goes through SafetyGate (hard boundary).
 */
class Orchestrator(
    private val llm: Planner?,
    private val fallback: Planner,
) : Planner {

    private var active: Planner = if (llm?.isReady == true) llm else fallback

    override val identity: String get() = active.identity
    override val isReady get() = true

    override suspend fun planStep(ctx: PlanContext): PlanOutcome {
        val out = active.planStep(ctx)
        // Mid-task LLM loss degrades to rules for the rest of this task.
        if (out is PlanOutcome.Unavailable && active !== fallback) {
            active = fallback
            return fallback.planStep(ctx)
        }
        return out
    }

    override fun cancel() {
        llm?.cancel()
        fallback.cancel()
    }

    companion object {
        const val SYSTEM_PROMPT = """You are Alalay, an offline accessibility agent on an Android phone.
The user is blind or low-vision and speaks English or Taglish.

You receive: the TASK, the CURRENT SCREEN (a numbered list of UI elements),
the LAST ACTION RESULT, and your progress STEP n/5.

Reply with EXACTLY ONE JSON object, nothing else:
{"v":"alalay.action.v1","tool":"<name>","args":{...},"say":"<short spoken update>","confidence":<0.0-1.0>}

Tools:
- open_app {"app":"<name>"} — launch an installed app
- click {"target":"<text or description>"} or {"x":<int>,"y":<int>} — tap an element
- read_text {} — read the screen aloud to the user
- scroll {"direction":"up"|"down"} — scroll a list
- go_back {} — navigate back one screen
- draft_reply {"text":"<message>"} — type a reply into the compose field. NEVER sends.
- ask_user {"question":"<question>","options":["...","..."]} — ask a clarifying question
- finish {"summary":"<what happened>"} — end the task

Rules:
1. Plan ONE step only. You will see the new screen after each action.
2. Prefer click by target text; use x,y only for unlabeled elements.
3. If 2+ elements match the requested target, use ask_user — never guess.
4. You cannot send messages, pay, authenticate, enter passwords or OTPs,
   or change security settings. If asked, use finish and say you can't.
5. If stuck after a retry, finish with an honest explanation.
6. Keep "say" under 12 words. Never include message content in "say"
   except when the task is to read it aloud via read_text."""

        /** Prompt assembly per 07-§4 — slot order and content are fixed. */
        fun assemblePrompt(ctx: PlanContext): String = buildString {
            append(SYSTEM_PROMPT).append("\n\n")
            append("TASK: ").append(ctx.task)
            if (ctx.aliasHints.isNotEmpty()) append(" (").append(ctx.aliasHints).append(")")
            append("\n")
            if (ctx.memoryLine.isNotEmpty()) {
                append("MEMORY: ").append(ctx.memoryLine).append("\n")
            }
            append("SCREEN:\n").append(ctx.screenDump).append("\n")
            if (ctx.lastResult.isNotEmpty()) {
                append("LAST: ").append(ctx.lastResult).append("\n")
            }
            append("STEP ").append(ctx.step).append('/').append(ctx.maxSteps)
            if (ctx.failureHint != null) {
                append(" — previous approach failed (").append(ctx.failureHint)
                    .append(") — choose differently")
            }
        }
    }
}

/**
 * Backend seam for on-device LLM runtimes (LiteRT-LM primary, llama.cpp
 * fallback — 06-TRD §2). Implementations wrap native inference; the
 * planner treats a missing runtime honestly instead of faking output.
 */
interface LlmRuntime {
    val label: String get() = "LLM"
    fun load(modelPath: String): Boolean
    suspend fun infer(prompt: String): String
    fun abort()
}

/**
 * LiteRT-LM planner (DEC-06). Becomes ready only when both a model file
 * and a runtime are present; the strict JSON contract + 1 parse retry
 * (07-§8) live here so any conforming runtime can plug in.
 */
class LiteRtLmPlanner(
    private val modelDir: File,
    private val runtime: LlmRuntime?,
) : Planner {

    private var loaded = false
    private var modelFile: File? = null

    private fun findModel(): File? =
        modelDir.listFiles()?.firstOrNull {
            it.extension.lowercase() in setOf("litertlm", "gguf", "task")
        }

    override val identity: String
        get() = "${runtime?.label ?: "no runtime"} (${modelFile?.name ?: "no model"})"

    override val isReady: Boolean
        get() {
            if (!loaded) {
                modelFile = findModel()
                loaded = modelFile != null && runtime?.load(modelFile!!.absolutePath) == true
            }
            return loaded && runtime != null
        }

    override suspend fun planStep(ctx: PlanContext): PlanOutcome {
        val rt = runtime ?: return PlanOutcome.Unavailable("no LLM runtime bundled")
        if (!isReady) return PlanOutcome.Unavailable("model not loaded")
        return try {
            val raw = rt.infer(Orchestrator.assemblePrompt(ctx))
            when (val parsed = ActionContract.parse(raw)) {
                is ParseResult.Ok -> PlanOutcome.Action(parsed.action)
                is ParseResult.Rejected -> PlanOutcome.ParseFailed(parsed.detail)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            PlanOutcome.Unavailable(e.message ?: "inference failed")
        }
    }

    override fun cancel() {
        runtime?.abort()
    }
}
