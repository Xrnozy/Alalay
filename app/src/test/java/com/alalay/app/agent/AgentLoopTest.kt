package com.alalay.app.agent

import com.alalay.app.FakeExecutor
import com.alalay.app.FakeMemory
import com.alalay.app.FakeScreen
import com.alalay.app.FakeSpeaker
import com.alalay.app.FakeVoice
import com.alalay.app.ScriptedPlanner
import com.alalay.app.el
import com.alalay.app.screenOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {

    private fun testScope() = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> e.printStackTrace() }
    )

    private fun loop(
        voice: FakeVoice = FakeVoice(),
        screen: FakeScreen = FakeScreen(screenOf()),
        exec: FakeExecutor = FakeExecutor(),
        speaker: FakeSpeaker = FakeSpeaker(),
        memory: FakeMemory = FakeMemory(),
        planner: Planner = ScriptedPlanner(emptyList()),
    ) = AgentLoop(
        voice = voice,
        screen = screen,
        executor = exec,
        speaker = speaker,
        memory = memory,
        planner = planner,
        diagnostics = null,
        scope = testScope(),
        confirmTimeoutMs = 300,
        settleMs = 1,
    )

    private fun run(loop: AgentLoop, transcript: String) = runBlocking {
        loop.submitTranscript(transcript).join()
    }

    private fun action(tool: Tool, args: ActionArgs, say: String = "") =
        PlanOutcome.Action(ActionV1(tool, args, say, 0.95))

    // ── Send confirmation (AT-05) ──────────────────────────────────

    private val mamaRow = el(0, "Mama Remedios", clickable = true)
    private fun composeField(text: String) =
        el(1, "compose", editable = true, viewId = "compose_field", textOverride = text)
    private fun bubble(text: String) =
        el(2, "", viewId = "message_text", textOverride = text)

    private fun sendScript(draft: String = "on my way") = ScriptedPlanner(
        listOf(
            action(Tool.OPEN_APP, ActionArgs.OpenApp("alalay messages")),
            action(Tool.CLICK, ActionArgs.Click("mama", null, null)),
            action(Tool.DRAFT_REPLY, ActionArgs.DraftReply(draft)),
        )
    )

    @Test
    fun `confirmed send delivers - AT-05`() {
        val screen = FakeScreen(screenOf(mamaRow, composeField("on my way")))
        val exec = FakeExecutor(
            onConfirmSend = {
                // The app accepted the send: field cleared, bubble appeared.
                screen.model = screenOf(mamaRow, composeField(""), bubble("on my way"))
            },
        )
        val voice = FakeVoice().apply { answers += "yes" }
        val speaker = FakeSpeaker()
        val l = loop(
            voice, screen, exec, speaker,
            FakeMemory(mapOf("mama" to "Mama Remedios")), sendScript(),
        )

        run(l, "reply to mama: on my way")

        assertEquals(1, exec.confirmSendCalls)
        assertTrue(speaker.lines.any { it == "Sent." })
        assertTrue(speaker.lines.any {
            it.contains("Send this to Mama Remedios") && it.contains("on my way")
        })
    }

    @Test
    fun `denied send transmits nothing - AT-05`() {
        val screen = FakeScreen(screenOf(mamaRow, composeField("on my way")))
        val exec = FakeExecutor()
        val voice = FakeVoice().apply { answers += "no" }
        val speaker = FakeSpeaker()
        val l = loop(voice, screen, exec, speaker, FakeMemory(), sendScript())

        run(l, "reply to mama: on my way")

        assertEquals(0, exec.confirmSendCalls)
        assertEquals(1, exec.clearDraftCalls)
        assertTrue(speaker.lines.any { it.contains("Cancelled") })
    }

    @Test
    fun `confirmation timeout sends nothing - AT-05`() {
        val screen = FakeScreen(screenOf(mamaRow, composeField("on my way")))
        val exec = FakeExecutor()
        val speaker = FakeSpeaker()
        val l = loop(FakeVoice(), screen, exec, speaker, FakeMemory(), sendScript())

        run(l, "reply to mama: on my way")

        assertEquals(0, exec.confirmSendCalls)
        assertEquals(1, exec.clearDraftCalls)
        assertTrue(speaker.lines.any { it.contains("Cancelled") })
    }

    @Test
    fun `stop during confirmation cancels the send - AT-05 AT-06`() {
        val screen = FakeScreen(screenOf(mamaRow, composeField("on my way")))
        val exec = FakeExecutor()
        val voice = FakeVoice().apply { answers += "stop" }
        val speaker = FakeSpeaker()
        val l = loop(voice, screen, exec, speaker, FakeMemory(), sendScript())

        run(l, "reply to mama: on my way")

        assertEquals(0, exec.confirmSendCalls)
        assertEquals(1, exec.clearDraftCalls)
        assertTrue(speaker.lines.any { it == "Stopped." })
    }

    // ── Ambiguity (AT-04) ──────────────────────────────────────────

    @Test
    fun `two matching contacts ask then resolve by ordinal - AT-04`() {
        val cruz = el(0, "Rosa Cruz", clickable = true)
        val diaz = el(1, "Rosa Diaz", clickable = true)
        val screen = FakeScreen(screenOf(cruz, diaz))
        val exec = FakeExecutor()
        val voice = FakeVoice().apply { answers += "second" }
        val speaker = FakeSpeaker()
        val l = loop(
            voice, screen, exec, speaker, FakeMemory(),
            ScriptedPlanner(
                listOf(
                    action(Tool.CLICK, ActionArgs.Click("rosa", null, null)),
                    action(Tool.FINISH, ActionArgs.Finish("Opened it."), "Done."),
                )
            ),
        )

        run(l, "read rosa's latest message")

        assertTrue(speaker.lines.any { it.contains("2 matches for rosa") })
        val click = exec.performed.filter { it.tool == Tool.CLICK }.last()
        val args = click.args as ActionArgs.Click
        assertEquals(diaz.bounds.centerX, args.x)
        assertEquals(diaz.bounds.centerY, args.y)
    }

    // ── Honest failure & guards (AT-07) ────────────────────────────

    @Test
    fun `repeated missing target fails honestly - AT-07`() {
        val screen = FakeScreen(screenOf(el(0, "Juan")))
        val speaker = FakeSpeaker()
        val l = loop(
            FakeVoice(), screen, FakeExecutor(), speaker, FakeMemory(),
            ScriptedPlanner(
                listOf(
                    action(Tool.CLICK, ActionArgs.Click("nonexistent", null, null)),
                    action(Tool.CLICK, ActionArgs.Click("still not there", null, null)),
                )
            ),
        )

        run(l, "read the latest message from nonexistent")

        assertTrue(speaker.lines.any { it.contains("Sorry, I couldn't") })
        assertTrue(speaker.lines.any { it.contains("no element matches") })
    }

    @Test
    fun `step cap stops and reports honestly - AT-07`() {
        val screen = FakeScreen(screenOf(el(0, "Juan")))
        val speaker = FakeSpeaker()
        // Alternating signatures dodge the loop guard; the 5-step cap is the bound.
        val l = loop(
            FakeVoice(), screen, FakeExecutor(), speaker, FakeMemory(),
            ScriptedPlanner(
                listOf(
                    action(Tool.SCROLL, ActionArgs.Scroll(ScrollDirection.DOWN)),
                    action(Tool.SCROLL, ActionArgs.Scroll(ScrollDirection.UP)),
                )
            ),
        )

        run(l, "scroll down")

        assertTrue(speaker.lines.any { it.contains("step limit") })
    }

    @Test
    fun `identical action signature rejected as a loop`() {
        val screen = FakeScreen(screenOf(el(0, "Juan")))
        val exec = FakeExecutor()
        val speaker = FakeSpeaker()
        val l = loop(
            FakeVoice(), screen, exec, speaker, FakeMemory(),
            ScriptedPlanner(List(5) { action(Tool.GO_BACK, ActionArgs.GoBack) }),
        )

        run(l, "keep doing the thing")

        // Only the first GO_BACK executed; the rest were loop-rejected.
        assertEquals(1, exec.performed.count { it.tool == Tool.GO_BACK })
        assertTrue(speaker.lines.any { it.contains("step limit") })
    }

    // ── Forbidden domains (AT-11) ──────────────────────────────────

    @Test
    fun `payment request refused before planning - AT-11`() {
        val planner = ScriptedPlanner(emptyList())
        val speaker = FakeSpeaker()
        val l = loop(speaker = speaker, planner = planner)

        run(l, "send money to mama on gcash")

        assertEquals(0, planner.calls)
        assertTrue(speaker.lines.any { it.contains("never do") })
    }

    @Test
    fun `open app shortcut still checks the blocklist - AT-11`() {
        val planner = ScriptedPlanner(emptyList())
        val speaker = FakeSpeaker()
        val exec = FakeExecutor()
        val l = loop(exec = exec, speaker = speaker, planner = planner)

        run(l, "open gcash")

        assertTrue(exec.performed.none { it.tool == Tool.OPEN_APP })
        assertEquals(0, planner.calls)
        assertTrue(speaker.lines.any { it.contains("never do") })
    }

    // ── Misc pipeline ──────────────────────────────────────────────

    @Test
    fun `parse failure retries once then recovers`() {
        val speaker = FakeSpeaker()
        val planner = ScriptedPlanner(
            listOf(
                PlanOutcome.ParseFailed("malformed JSON"),
                action(Tool.FINISH, ActionArgs.Finish("all done"), "Done."),
            )
        )
        val l = loop(
            screen = FakeScreen(screenOf(el(0, "Juan"))),
            speaker = speaker, planner = planner,
        )

        run(l, "what's on my screen")

        assertTrue(speaker.lines.any { it == "Done." })
        assertEquals("previous reply was not valid JSON", planner.contexts[1].failureHint)
    }

    @Test
    fun `read_text speaks the message - AT-03 pipeline`() {
        val screen = FakeScreen(screenOf(el(0, "Juan")))
        val exec = FakeExecutor(
            outcome = ExecOutcome.Read(
                speaker = "Mama Remedios",
                messageText = "Nasaan ka na? Dinner is ready.",
                items = emptyList(),
                appLabel = "Messages",
            )
        )
        val speaker = FakeSpeaker()
        val l = loop(
            screen = screen, exec = exec, speaker = speaker,
            planner = ScriptedPlanner(
                listOf(
                    action(Tool.READ_TEXT, ActionArgs.ReadText),
                    action(Tool.FINISH, ActionArgs.Finish("done"), "Done."),
                )
            ),
        )

        run(l, "read the latest message from mama")

        assertTrue(speaker.lines.any { it.contains("Nasaan ka na? Dinner is ready.") })
        assertTrue(speaker.lines.any { it.contains("Mama Remedios says") })
    }

    @Test
    fun `stop utterance cancels`() {
        val speaker = FakeSpeaker()
        val l = loop(speaker = speaker)

        run(l, "stop")

        assertTrue(speaker.lines.any { it == "Stopped." })
        assertEquals(AgentLoop.AgentState.IDLE, l.state.value)
    }
}
