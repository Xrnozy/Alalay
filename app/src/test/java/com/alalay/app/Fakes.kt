package com.alalay.app

import com.alalay.app.agent.ActionPerformer
import com.alalay.app.agent.ActionV1
import com.alalay.app.agent.ExecOutcome
import com.alalay.app.agent.MemoryAccess
import com.alalay.app.agent.PlanContext
import com.alalay.app.agent.PlanOutcome
import com.alalay.app.agent.Planner
import com.alalay.app.agent.ScreenModel
import com.alalay.app.agent.ScreenSource
import com.alalay.app.agent.SpokenOutput
import com.alalay.app.agent.TemplateRenderer
import com.alalay.app.agent.Tpl
import com.alalay.app.agent.VoiceCapture
import kotlinx.coroutines.delay

fun el(
    index: Int,
    label: String,
    clickable: Boolean = false,
    editable: Boolean = false,
    scrollable: Boolean = false,
    bounds: com.alalay.app.agent.RectI =
        com.alalay.app.agent.RectI(0, index * 100 + 1, 500, index * 100 + 80),
    viewId: String = "",
    className: String = "android.widget.TextView",
    textOverride: String? = null,
) = com.alalay.app.agent.UiElement(
    index = index,
    text = textOverride ?: label,
    contentDesc = if (textOverride == null) "" else label,
    className = className,
    viewId = viewId,
    isClickable = clickable,
    isEditable = editable,
    isScrollable = scrollable,
    isCheckable = false,
    bounds = bounds,
    depth = 0,
)

fun screenOf(vararg elements: com.alalay.app.agent.UiElement, pkg: String = "com.alalay.demomessenger") =
    ScreenModel(pkg, elements.toList())

/** Queued answers; an empty queue returns "" (silence). */
class FakeVoice : VoiceCapture {
    val answers = ArrayDeque<String>()
    var stopped = false
    var captureCalls = 0

    override suspend fun capture(): String {
        captureCalls++
        if (answers.isEmpty()) delay(5)
        return answers.removeFirstOrNull() ?: ""
    }

    override fun finish() {}
    override fun stop() {
        stopped = true
        answers.clear()
    }
}

/** Screen content is a var — tests mutate it to simulate transitions. */
class FakeScreen(var model: ScreenModel) : ScreenSource {
    var dumps = 0
    override suspend fun dump(): ScreenModel {
        dumps++
        return model
    }
}

class FakeExecutor(
    var outcome: ExecOutcome = ExecOutcome.Ok("ok"),
    var sendOutcome: ExecOutcome = ExecOutcome.Ok("sent"),
    var onConfirmSend: () -> Unit = {},
) : ActionPerformer {
    val performed = mutableListOf<ActionV1>()
    var confirmSendCalls = 0
    var clearDraftCalls = 0
    var up = true

    override suspend fun perform(action: ActionV1): ExecOutcome {
        performed += action
        return outcome
    }

    override suspend fun confirmSend(): ExecOutcome {
        confirmSendCalls++
        onConfirmSend()
        return sendOutcome
    }

    override suspend fun clearDraft() {
        clearDraftCalls++
    }

    override fun serviceUp() = up
}

class FakeSpeaker(override val language: String = "en") : SpokenOutput {
    val lines = mutableListOf<String>()
    var stopped = false

    override fun speak(template: Tpl, vararg args: Pair<String, String>) {
        lines += TemplateRenderer.render(template, language, *args)
    }

    override fun say(text: String) {
        lines += text
    }

    override fun stop() {
        stopped = true
    }

    override fun repeat() {
        lines.lastOrNull()?.let { lines += it }
    }
}

class FakeMemory(
    private val aliases: Map<String, String> = emptyMap(),
    private val lang: String = "en",
) : MemoryAccess {
    val rolling = mutableMapOf<String, String>()
    val savedAliases = mutableListOf<Pair<String, String>>()

    override fun resolveAlias(name: String): List<String> =
        aliases.filterKeys { it.equals(name, true) }.values.toList()

    override fun aliasHintsFor(task: String): String =
        aliases.entries.filter { task.contains(it.key, true) }
            .joinToString(", ") { "${it.key} → ${it.value}" }

    override fun memoryLine() = "lang=$lang"
    override fun language() = lang

    override suspend fun saveAlias(alias: String, target: String, kind: String) {
        savedAliases += alias to target
    }

    override suspend fun setRolling(key: String, value: String) {
        rolling[key] = value
    }
}

/** Returns scripted outcomes in order; repeats the last one when drained. */
class ScriptedPlanner(script: List<PlanOutcome>) : Planner {
    private val queue = ArrayDeque(script)
    private var last: PlanOutcome? = null
    val contexts = mutableListOf<PlanContext>()
    var cancelled = false
    var calls = 0

    override val identity = "scripted"
    override val isReady = true

    override suspend fun planStep(ctx: PlanContext): PlanOutcome {
        calls++
        contexts += ctx
        queue.removeFirstOrNull()?.let { last = it }
        return last ?: PlanOutcome.Unavailable("script drained")
    }

    override fun cancel() {
        cancelled = true
    }
}
