package com.alalay.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RulesPlannerTest {

    private fun ctx(task: String, done: List<Tool> = emptyList(), hint: String? = null) =
        PlanContext(
            task = task,
            aliasHints = "",
            memoryLine = "lang=en",
            screenDump = "app:x",
            lastResult = "",
            step = done.size + 1,
            maxSteps = 5,
            failureHint = hint,
            executedTools = done,
        )

    private suspend fun next(p: RulesPlanner, task: String, done: List<Tool>, hint: String? = null): ActionV1 =
        (p.planStep(ctx(task, done, hint)) as PlanOutcome.Action).action

    @Test
    fun `read message plans open then click then read then finish`() = runTest {
        val p = RulesPlanner()
        val task = "read the latest message from mama"

        assertEquals(Tool.OPEN_APP, next(p, task, emptyList()).tool)
        assertEquals(Tool.CLICK, next(p, task, listOf(Tool.OPEN_APP)).tool)
        assertEquals(Tool.READ_TEXT, next(p, task, listOf(Tool.OPEN_APP, Tool.CLICK)).tool)
        assertEquals(
            Tool.FINISH,
            next(p, task, listOf(Tool.OPEN_APP, Tool.CLICK, Tool.READ_TEXT)).tool,
        )
    }

    @Test
    fun `send message drafts but can never send - no send tool exists`() = runTest {
        val p = RulesPlanner()
        val task = "sabihin kay mama na papunta na ako"

        assertEquals(Tool.OPEN_APP, next(p, task, emptyList()).tool)
        assertEquals(Tool.CLICK, next(p, task, listOf(Tool.OPEN_APP)).tool)
        val draft = next(p, task, listOf(Tool.OPEN_APP, Tool.CLICK))
        assertEquals(Tool.DRAFT_REPLY, draft.tool)
        assertEquals(
            "papunta na ako",
            (draft.args as ActionArgs.DraftReply).text,
        )
    }

    @Test
    fun `unknown task finishes honestly`() = runTest {
        val p = RulesPlanner()
        val a = next(p, "xyzzy plugh", emptyList())
        assertEquals(Tool.FINISH, a.tool)
        assertEquals(
            "I only know how to open apps, read messages, reply, describe the screen, and scroll.",
            (a.args as ActionArgs.Finish).summary,
        )
    }

    @Test
    fun `describe screen reads once then finishes`() = runTest {
        val p = RulesPlanner()
        val task = "what's on my screen"
        assertEquals(Tool.READ_TEXT, next(p, task, emptyList()).tool)
        assertEquals(Tool.FINISH, next(p, task, listOf(Tool.READ_TEXT)).tool)
    }
}
