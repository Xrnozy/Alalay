package com.alalay.app.agent

import com.alalay.app.device.ScreenCompactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AgentLoop (04-§5 state machine, FR-02, FR-09, FR-13; T-15..T-18).
 * Drives the whole command cycle from transcript to outcome; all device
 * and speech access goes through the injected seams so the full loop is
 * JVM-testable.
 */
class AgentLoop(
    private val voice: VoiceCapture,
    private val screen: ScreenSource,
    private val executor: ActionPerformer,
    private val speaker: SpokenOutput,
    private val memory: MemoryAccess,
    private val planner: Planner,
    private val diagnostics: Diagnostics? = null,
    private val scope: CoroutineScope,
    private val maxSteps: Int = 5,
    private val confirmTimeoutMs: Long = 30_000,
    private val settleMs: Long = 600,
) {

    /** Minimal diagnostics seam so the loop stays JVM-testable. */
    interface Diagnostics {
        fun pipeline(state: AgentState)
        fun inference(ms: Long)
        fun actionType(name: String)
    }

    enum class AgentState {
        IDLE, LISTENING, ROUTING, CONTROL, PLANNING, VALIDATING,
        EXECUTING, VERIFYING, CONFIRMING, SENDING, ASKING,
        DONE, FAILED, CANCELLED,
    }

    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val gate = ConfirmationGate()
    private var taskJob: Job? = null
    private var lastActionSignature: String? = null
    private var consecutiveScrolls = 0
    private var pendingDraft = false

    private fun setState(s: AgentState) {
        _state.value = s
        diagnostics?.pipeline(s)
    }

    /** Last spoken line, re-said on "repeat". */
    var lastSpoken: String = ""
        private set

    private fun speak(tpl: Tpl, vararg args: Pair<String, String>) {
        lastSpoken = TemplateRenderer.render(tpl, speaker.language, *args)
        speaker.speak(tpl, *args)
    }

    private fun say(text: String) {
        lastSpoken = text
        speaker.say(text)
    }

    // ── Entry points ───────────────────────────────────────────────

    /** PTT pressed — marks LISTENING before capture starts (04-§5). */
    fun beginListening() {
        setState(AgentState.LISTENING)
    }

    /** Stop everything ≤1 s from any state (FR-09, NFR-07). */
    fun stop() {
        val hadDraft = pendingDraft
        voice.stop()
        speaker.stop()
        planner.cancel()
        taskJob?.cancel()
        taskJob = null
        pendingDraft = false
        gate.reset()
        setState(AgentState.CANCELLED)
        speak(Tpl.STOPPED)
        if (hadDraft) {
            scope.launch { executor.clearDraft() }
            say(TemplateRenderer.render(Tpl.CANCELLED_SEND, speaker.language))
        }
        setState(AgentState.IDLE)
    }

    /**
     * Command intake. The heavy work runs on the injected scope so the
     * caller stays on the UI thread; the returned Job is mainly for tests.
     */
    fun submitTranscript(transcript: String): Job {
        if (taskJob?.isActive == true) {
            // A new command while one runs behaves as "stop, then listen".
            stop()
        }
        val job = scope.launch(Dispatchers.Default) {
            setState(AgentState.ROUTING)
            handleUtterance(transcript)
        }
        taskJob = job
        return job
    }

    // ── Routing ────────────────────────────────────────────────────

    private suspend fun handleUtterance(transcript: String) {
        val routed = Router.route(transcript, RouterMode.COMMAND)
        when (routed) {
            RouteResult.Empty -> speak(Tpl.DIDNT_CATCH)
            RouteResult.Ignored -> speak(Tpl.DIDNT_CATCH)
            RouteResult.Stop -> cancelInternal()
            RouteResult.GoBack -> controlExec { executor.perform(goBackAction()) }
            RouteResult.Repeat -> speaker.repeat()
            RouteResult.Confirm, RouteResult.Deny,
            is RouteResult.AnswerOrdinal, is RouteResult.AnswerName,
            RouteResult.AnswerUnclear -> speak(Tpl.DIDNT_CATCH)
            is RouteResult.OpenApp -> runOpenShortcut(routed.appQuery)
            is RouteResult.Task -> {
                SafetyGate.checkTask(routed.utterance)?.let { domain ->
                    diagnostics?.actionType("Rejected(FORBIDDEN:${domain.name})")
                    speak(Tpl.FORBIDDEN, "domain" to domain.spoken)
                    setState(AgentState.IDLE)
                    return
                }
                runTask(routed.utterance)
            }
        }
        if (_state.value == AgentState.ROUTING) setState(AgentState.IDLE)
    }

    private fun goBackAction() =
        ActionV1(Tool.GO_BACK, ActionArgs.GoBack, "", 1.0)

    private suspend fun controlExec(block: suspend () -> ExecOutcome) {
        setState(AgentState.CONTROL)
        block()
        setState(AgentState.IDLE)
    }

    private suspend fun cancelInternal() {
        val hadDraft = pendingDraft
        pendingDraft = false
        if (hadDraft) executor.clearDraft()
        gate.reset()
        setState(AgentState.CANCELLED)
        speak(Tpl.STOPPED)
        if (hadDraft) {
            say(TemplateRenderer.render(Tpl.CANCELLED_SEND, speaker.language))
        }
        setState(AgentState.IDLE)
    }

    // ── CMD-01 shortcut ────────────────────────────────────────────

    private suspend fun runOpenShortcut(appQuery: String) {
        // The shortcut path still passes the forbidden-domain gate (AT-11).
        SafetyGate.checkTask(appQuery)?.let { domain ->
            diagnostics?.actionType("Rejected(FORBIDDEN:${domain.name})")
            speak(Tpl.FORBIDDEN, "domain" to domain.spoken)
            setState(AgentState.IDLE)
            return
        }
        setState(AgentState.CONTROL)
        speak(Tpl.OPENING, "app" to appQuery)
        val res = executor.perform(
            ActionV1(Tool.OPEN_APP, ActionArgs.OpenApp(appQuery), "", 1.0)
        )
        delay(settleMs)
        when (res) {
            is ExecOutcome.Ok -> {
                val opened = screen.dump().foregroundPackage
                if (opened != null && opened != "com.alalay.app") {
                    speak(Tpl.APP_OPEN, "app" to opened.substringAfterLast('.'))
                } else {
                    speak(Tpl.APP_OPEN, "app" to appQuery)
                }
            }
            ExecOutcome.ServiceUnavailable -> speak(Tpl.SERVICE_LOST)
            is ExecOutcome.Failed -> speak(Tpl.APP_NOT_FOUND, "app" to appQuery)
            else -> speak(Tpl.APP_NOT_FOUND, "app" to appQuery)
        }
        setState(AgentState.IDLE)
    }

    // ── Planning loop (PLANNING→VALIDATING→EXECUTING→VERIFYING) ────

    private suspend fun runTask(task: String) {
        val executed = mutableListOf<Tool>()
        var lastResult = ""
        var failureHint: String? = null
        var parseRetried = false
        var verifyRetried = false

        for (step in 1..maxSteps) {
            currentCoroutineContext().ensureActive()
            setState(AgentState.PLANNING)

            val screenModel = withTimeoutOrNull(3_000) { screen.dump() }
            if (screenModel == null || (screenModel.isEmpty && !executor.serviceUp())) {
                speak(Tpl.SERVICE_LOST)
                setState(AgentState.FAILED)
                setState(AgentState.IDLE)
                return
            }
            val model = screenModel ?: ScreenModel(null, emptyList())

            val ctx = PlanContext(
                task = task,
                aliasHints = memory.aliasHintsFor(task),
                memoryLine = memory.memoryLine(),
                screenDump = ScreenCompactor.compact(model, ScreenCompactor.taskKeywords(task)),
                lastResult = lastResult,
                step = step,
                maxSteps = maxSteps,
                failureHint = failureHint,
                executedTools = executed.toList(),
            )

            val t0 = System.nanoTime()
            val outcome = planner.planStep(ctx)
            diagnostics?.inference((System.nanoTime() - t0) / 1_000_000)

            val action = when (outcome) {
                is PlanOutcome.Action -> outcome.action
                is PlanOutcome.ParseFailed -> {
                    if (!parseRetried) {
                        parseRetried = true
                        failureHint = "previous reply was not valid JSON"
                        continue
                    }
                    failHonest("understand my own reply", "it didn't come out right")
                    return
                }
                is PlanOutcome.Unavailable -> {
                    failHonest("finish that", outcome.reason)
                    return
                }
            }
            parseRetried = false

            // Loop guards (07-§8): repeated identical action → reject.
            val sig = "${action.tool}:${action.args}"
            if (sig == lastActionSignature && action.tool != Tool.FINISH) {
                failureHint = "that action was already tried"
                lastResult = "rejected: LOOP ($sig)"
                diagnostics?.actionType("Rejected(LOOP)")
                continue
            }
            lastActionSignature = sig

            if (action.tool == Tool.SCROLL) {
                consecutiveScrolls++
                if (consecutiveScrolls > 3) {
                    failureHint = "choose from what's visible"
                    lastResult = "rejected: too many scrolls"
                    continue
                }
            } else {
                consecutiveScrolls = 0
            }

            setState(AgentState.VALIDATING)
            when (val verdict = SafetyGate.validate(action, model, speaker.language)) {
                is Verdict.Approved -> {
                    if (action.tool == Tool.FINISH) {
                        setState(AgentState.DONE)
                        if (action.say.isNotBlank()) say(action.say)
                        val sum = (action.args as ActionArgs.Finish).summary
                        if (sum.isNotBlank() && sum != action.say) {
                            speak(Tpl.DONE, "summary" to sum)
                        }
                        memory.setRolling("last_task_kind", taskKindOf(task))
                        setState(AgentState.IDLE)
                        return
                    }
                    if (action.say.isNotBlank()) say(action.say)
                    val exec = executeStep(action)
                    diagnostics?.actionType(action.tool.wireName)
                    executed += action.tool
                    lastResult = resultLine(action, exec)

                    when (exec) {
                        is ExecOutcome.Read -> {
                            speakReadResult(exec, task)
                            lastResult = "read ok"
                        }
                        ExecOutcome.ServiceUnavailable -> {
                            speak(Tpl.SERVICE_LOST); setState(AgentState.IDLE); return
                        }
                        is ExecOutcome.Failed -> {
                            if (!verifyRetried) {
                                verifyRetried = true
                                failureHint = exec.reason
                                continue
                            }
                            failHonest(describeTool(task), exec.reason)
                            return
                        }
                        is ExecOutcome.Ok -> Unit
                    }
                    verifyRetried = false
                    failureHint = null
                }

                is Verdict.NeedsConfirmation -> {
                    if (action.say.isNotBlank()) say(action.say)
                    val exec = executeStep(action)
                    diagnostics?.actionType(action.tool.wireName)
                    when (exec) {
                        is ExecOutcome.Failed -> { failHonest(describeTool(task), exec.reason); return }
                        ExecOutcome.ServiceUnavailable -> { speak(Tpl.SERVICE_LOST); setState(AgentState.IDLE); return }
                        else -> Unit
                    }
                    executed += action.tool
                    pendingDraft = true

                    // Verify the draft landed before opening the gate (07-§6).
                    delay(settleMs)
                    val draft = (action.args as ActionArgs.DraftReply).text
                    val verified = screen.dump().editableElements()
                        .any { it.text.contains(draft.take(20)) || it.text == draft }
                    if (!verified) {
                        if (!verifyRetried) {
                            verifyRetried = true
                            failureHint = "draft did not land in the compose field"
                            continue
                        }
                        failHonest("write the reply", "the text field didn't take it")
                        return
                    }
                    verifyRetried = false

                    val sent = runConfirmationGate(task, draft)
                    // Gate resolution is terminal for this task (03-§4).
                    setState(AgentState.IDLE)
                    return
                }

                is Verdict.AskUser -> {
                    setState(AgentState.ASKING)
                    val resolved = runAmbiguity(verdict)
                    when (resolved) {
                        is AmbiguityResult.ResolvedElement -> {
                            executed += Tool.CLICK
                            lastResult = "click ok (resolved by user)"
                        }
                        is AmbiguityResult.ConfirmedYes -> {
                            // Low-confidence action approved by user.
                            verdict.pendingAction?.let {
                                val exec = executeStep(it)
                                executed += it.tool
                                lastResult = resultLine(it, exec)
                            }
                        }
                        AmbiguityResult.Denied -> { setState(AgentState.IDLE); return }
                        AmbiguityResult.Failed -> {
                            speak(Tpl.AMBIGUITY_FAIL)
                            setState(AgentState.FAILED); setState(AgentState.IDLE); return
                        }
                        AmbiguityResult.Stopped -> { return }
                    }
                }

                is Verdict.Rejected -> {
                    diagnostics?.actionType("Rejected(${verdict.reason})")
                    when (verdict.reason) {
                        RejectReason.FORBIDDEN -> {
                            speak(Tpl.FORBIDDEN, "domain" to (verdict.domain?.spoken ?: "that"))
                            setState(AgentState.IDLE); return
                        }
                        RejectReason.NO_TARGET, RejectReason.PARSE, RejectReason.UNKNOWN_TOOL -> {
                            if (!verifyRetried) {
                                verifyRetried = true
                                failureHint = verdict.retryHint ?: "no such target"
                                continue
                            }
                            failHonest(describeTool(task), verdict.retryHint ?: "no such target")
                            return
                        }
                        RejectReason.LOOP -> {
                            failureHint = "that action was already tried"
                            continue
                        }
                        RejectReason.POLICY -> {
                            failHonest(describeTool(task), "not allowed")
                            return
                        }
                    }
                }
            }
        }

        // Step cap reached without finish (AT-07): report honestly.
        setState(AgentState.DONE)
        speak(Tpl.FAILED,
            "action" to describeTool(task),
            "reason" to "I reached my step limit.")
        setState(AgentState.IDLE)
    }

    private suspend fun executeStep(action: ActionV1): ExecOutcome {
        setState(AgentState.EXECUTING)
        val res = executor.perform(action)
        setState(AgentState.VERIFYING)
        delay(settleMs)
        return res
    }

    // ── Confirmation gate (04-§6, FR-07) ───────────────────────────

    /** Returns true when the message verifiably sent; false otherwise. */
    private suspend fun runConfirmationGate(task: String, draft: String): Boolean {
        val recipient = resolveRecipient(task)
        gate.open(recipient, draft)
        setState(AgentState.CONFIRMING)
        speak(Tpl.CONFIRM_SEND, "recipient" to recipient, "text" to draft)

        val answer = listenForAnswer(confirmTimeoutMs, RouterMode.AWAITING_CONFIRMATION)
        return when (answer) {
            is Answer.Heard -> when (answer.route) {
                RouteResult.Confirm -> {
                    if (!gate.confirm()) return finishDenied()
                    setState(AgentState.SENDING)
                    gate.beginSend()
                    executor.confirmSend()
                    delay(settleMs)
                    val ok = verifySent(draft)
                    gate.completeSend(ok)
                    if (ok) {
                        pendingDraft = false
                        speak(Tpl.SENT)
                        memory.setRolling("last_contact", recipient)
                        setState(AgentState.DONE)
                        true
                    } else {
                        pendingDraft = false
                        executor.clearDraft()
                        gate.reset()
                        failHonest("send the message", "the send button didn't respond")
                        false
                    }
                }
                RouteResult.Deny, RouteResult.Stop -> {
                    if (answer.route == RouteResult.Stop) {
                        // "stop" mid-confirmation: deny + stopped speech (03-§8).
                        gate.deny()
                        executor.clearDraft()
                        pendingDraft = false
                        gate.reset()
                        setState(AgentState.CANCELLED)
                        speak(Tpl.STOPPED)
                        false
                    } else finishDenied()
                }
                else -> finishDenied()
            }
            Answer.Timeout -> finishDenied()
            Answer.Cancelled -> {
                executor.clearDraft(); pendingDraft = false; gate.reset(); false
            }
        }
    }

    private suspend fun finishDenied(): Boolean {
        gate.deny()
        executor.clearDraft()
        pendingDraft = false
        gate.reset()
        speak(Tpl.CANCELLED_SEND)
        return false
    }

    private suspend fun verifySent(draft: String): Boolean {
        // Post-send: compose field cleared AND a bubble with the draft (07-§6.4).
        val scr = screen.dump()
        val fieldEmpty = scr.editableElements().all { it.text.isBlank() }
        val bubble = scr.elements.any { !it.isEditable && it.text.contains(draft.take(20)) }
        return fieldEmpty && bubble
    }

    private fun resolveRecipient(task: String): String {
        val intent = IntentParser.parse(task)
        val raw = when (intent) {
            is TaskIntent.SendMessage -> intent.contact
            is TaskIntent.ReadMessage -> intent.contact
            else -> ""
        }
        val aliases = if (raw.isNotEmpty()) memory.resolveAlias(raw) else emptyList()
        return aliases.firstOrNull() ?: raw.ifEmpty { "the contact" }
    }

    // ── Ambiguity (FR-08, 07-§7) ───────────────────────────────────

    private sealed interface AmbiguityResult {
        data class ResolvedElement(val element: UiElement) : AmbiguityResult
        data object ConfirmedYes : AmbiguityResult
        data object Denied : AmbiguityResult
        data object Failed : AmbiguityResult
        data object Stopped : AmbiguityResult
    }

    private suspend fun runAmbiguity(verdict: Verdict.AskUser): AmbiguityResult {
        say(verdict.question)
        val mode = if (verdict.yesNo) RouterMode.AWAITING_CONFIRMATION else RouterMode.AWAITING_ANSWER
        var failures = 0
        while (failures < 2) {
            currentCoroutineContext().ensureActive()
            when (val answer = listenForAnswer(confirmTimeoutMs, mode)) {
                is Answer.Heard -> when (val r = answer.route) {
                    RouteResult.Stop -> { cancelOnly(); return AmbiguityResult.Stopped }
                    RouteResult.Deny -> return AmbiguityResult.Denied
                    RouteResult.Confirm ->
                        return if (verdict.yesNo) AmbiguityResult.ConfirmedYes
                        else { failures++; continue }
                    is RouteResult.AnswerOrdinal -> {
                        val el = verdict.candidates.getOrNull(r.index)
                        if (el != null) {
                            executeClickElement(el)
                            return AmbiguityResult.ResolvedElement(el)
                        }
                        failures++
                    }
                    is RouteResult.AnswerName -> {
                        val el = verdict.candidates.firstOrNull {
                            matchesLabel(it.label, r.name)
                        }
                        if (el != null) {
                            executeClickElement(el)
                            return AmbiguityResult.ResolvedElement(el)
                        }
                        failures++
                    }
                    else -> failures++
                }
                Answer.Timeout -> return AmbiguityResult.Failed
                Answer.Cancelled -> return AmbiguityResult.Stopped
            }
        }
        return AmbiguityResult.Failed
    }

    private suspend fun executeClickElement(el: UiElement) {
        say("Got it.")
        setState(AgentState.EXECUTING)
        executor.perform(
            ActionV1(Tool.CLICK, ActionArgs.Click(null, el.bounds.centerX, el.bounds.centerY), "", 1.0)
        )
        setState(AgentState.VERIFYING)
        delay(settleMs)
    }

    private sealed interface Answer {
        data class Heard(val route: RouteResult) : Answer
        data object Timeout : Answer
        data object Cancelled : Answer
    }

    /**
     * Listen→route until a classifiable answer arrives or timeout hits.
     * During CONFIRMING an unclassifiable utterance keeps the window open
     * (only yes/no/stop/timeout resolve a send — 07-§6).
     */
    private suspend fun listenForAnswer(timeoutMs: Long, mode: RouterMode): Answer =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val text = voice.capture()
                val r = Router.route(text, mode)
                when {
                    r == RouteResult.Empty -> continue
                    r == RouteResult.AnswerUnclear &&
                        mode == RouterMode.AWAITING_CONFIRMATION -> continue
                    else -> return@withTimeoutOrNull Answer.Heard(r)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Answer.Timeout
        } ?: Answer.Timeout

    // ── Helpers ────────────────────────────────────────────────────

    private fun cancelOnly() {
        setState(AgentState.CANCELLED)
        speak(Tpl.STOPPED)
        setState(AgentState.IDLE)
    }

    private suspend fun failHonest(actionDesc: String, reason: String) {
        if (pendingDraft) { executor.clearDraft(); pendingDraft = false }
        setState(AgentState.FAILED)
        speak(Tpl.FAILED, "action" to actionDesc, "reason" to reason)
        setState(AgentState.IDLE)
    }

    private fun speakReadResult(exec: ExecOutcome.Read, task: String) {
        if (exec.messageText != null) {
            speak(Tpl.MESSAGE_FROM,
                "name" to (exec.speaker ?: "They"),
                "message" to exec.messageText)
        } else {
            speak(Tpl.SCREEN_DESC,
                "app" to exec.appLabel,
                "items" to exec.items.joinToString(", ").ifEmpty { "nothing readable" })
        }
    }

    private fun describeTool(task: String): String = when (val i = IntentParser.parse(task)) {
        is TaskIntent.ReadMessage -> "read ${i.contact}'s message"
        is TaskIntent.SendMessage -> "send the message"
        TaskIntent.DescribeScreen -> "read the screen"
        is TaskIntent.Scroll -> "scroll"
        is TaskIntent.OpenApp -> "open ${i.app}"
        TaskIntent.Unknown -> "do that"
    }

    private fun taskKindOf(task: String): String = when (IntentParser.parse(task)) {
        is TaskIntent.ReadMessage -> "read_message"
        is TaskIntent.SendMessage -> "send_message"
        TaskIntent.DescribeScreen -> "describe"
        is TaskIntent.Scroll -> "scroll"
        is TaskIntent.OpenApp -> "open_app"
        TaskIntent.Unknown -> "unknown"
    }

    private fun resultLine(action: ActionV1, exec: ExecOutcome): String =
        "${action.tool.wireName}: ${when (exec) {
            is ExecOutcome.Ok -> "ok"
            is ExecOutcome.Failed -> "failed (${exec.reason.take(40)})"
            is ExecOutcome.Read -> "read ok"
            ExecOutcome.ServiceUnavailable -> "service lost"
        }}"
}
