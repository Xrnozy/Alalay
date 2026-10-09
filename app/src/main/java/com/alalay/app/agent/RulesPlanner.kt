package com.alalay.app.agent

/**
 * Rules-only planner — the T-23 contingency (RISK-01): deterministic
 * step sequences covering CMD-01..05 when no LLM runtime is available.
 * Emits the same `alalay.action.v1` actions through the same SafetyGate;
 * the model-level safety story is unchanged, it just isn't stochastic.
 */
class RulesPlanner(
    private val targetAppLabel: String = DEFAULT_TARGET_APP,
) : Planner {

    companion object {
        /** DEC-02 default demo target. */
        const val DEFAULT_TARGET_APP = "Alalay Demo Messenger"
    }

    override val identity = "Rules planner (deterministic, on-device)"
    override val isReady = true

    private var intent: TaskIntent = TaskIntent.Unknown
    private var parsed = false
    private var retried = false

    private fun action(tool: Tool, args: ActionArgs, say: String): PlanOutcome =
        PlanOutcome.Action(ActionV1(tool, args, say, confidence = 0.95))

    override suspend fun planStep(ctx: PlanContext): PlanOutcome {
        if (!parsed) {
            intent = IntentParser.parse(ctx.task)
            parsed = true
        }
        val done = ctx.executedTools

        return when (val i = intent) {
            is TaskIntent.ReadMessage -> when {
                Tool.OPEN_APP !in done ->
                    action(Tool.OPEN_APP, ActionArgs.OpenApp(targetAppLabel),
                        "Opening the app.")
                Tool.CLICK !in done && ctx.failureHint == null ->
                    action(Tool.CLICK, ActionArgs.Click(i.contact, null, null),
                        "Opening the conversation with ${i.contact}.")
                Tool.CLICK !in done ->
                    // Retry once after a failed click, then give up honestly.
                    if (!retried) {
                        retried = true
                        action(Tool.CLICK, ActionArgs.Click(i.contact, null, null),
                            "Trying again.")
                    } else {
                        action(Tool.FINISH,
                            ActionArgs.Finish("I couldn't find ${i.contact} on this screen."),
                            "Sorry, I couldn't find ${i.contact}.")
                    }
                Tool.READ_TEXT !in done ->
                    action(Tool.READ_TEXT, ActionArgs.ReadText, "Let me look.")
                else ->
                    action(Tool.FINISH, ActionArgs.Finish("That's the latest one."),
                        "That's the latest one.")
            }

            is TaskIntent.SendMessage -> when {
                Tool.OPEN_APP !in done ->
                    action(Tool.OPEN_APP, ActionArgs.OpenApp(targetAppLabel),
                        "Okay, a message for ${i.contact}. Opening the app.")
                Tool.CLICK !in done && ctx.failureHint == null ->
                    action(Tool.CLICK, ActionArgs.Click(i.contact, null, null),
                        "Opening the conversation with ${i.contact}.")
                Tool.CLICK !in done ->
                    if (!retried) {
                        retried = true
                        action(Tool.CLICK, ActionArgs.Click(i.contact, null, null),
                            "Trying again.")
                    } else {
                        action(Tool.FINISH,
                            ActionArgs.Finish("I couldn't find ${i.contact}; nothing was sent."),
                            "Sorry, I couldn't find ${i.contact}.")
                    }
                Tool.DRAFT_REPLY !in done ->
                    action(Tool.DRAFT_REPLY, ActionArgs.DraftReply(i.text),
                        "I've written a draft.")
                else ->
                    action(Tool.FINISH, ActionArgs.Finish("Done."), "Done.")
            }

            TaskIntent.DescribeScreen ->
                if (Tool.READ_TEXT !in done) {
                    action(Tool.READ_TEXT, ActionArgs.ReadText, "Let me look.")
                } else {
                    action(Tool.FINISH, ActionArgs.Finish("That's what's on screen."),
                        "That's what's on screen.")
                }

            is TaskIntent.Scroll ->
                if (Tool.SCROLL !in done) {
                    action(Tool.SCROLL, ActionArgs.Scroll(i.direction), "")
                } else {
                    action(Tool.FINISH, ActionArgs.Finish("Scrolled."), "Scrolled.")
                }

            is TaskIntent.OpenApp ->
                if (Tool.OPEN_APP !in done) {
                    action(Tool.OPEN_APP, ActionArgs.OpenApp(i.app), "Opening ${i.app}.")
                } else {
                    action(Tool.FINISH, ActionArgs.Finish("${i.app} is open."),
                        "${i.app} is open.")
                }

            TaskIntent.Unknown ->
                action(
                    Tool.FINISH,
                    ActionArgs.Finish(
                        "I only know how to open apps, read messages, reply, describe the screen, and scroll."
                    ),
                    "Sorry, I don't know how to do that yet.",
                )
        }
    }

    override fun cancel() { /* synchronous planner — nothing to abort */ }
}
