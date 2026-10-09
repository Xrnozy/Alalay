package com.alalay.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionContractTest {

    private fun rej(raw: String): ParseResult.Rejected =
        ActionContract.parse(raw) as ParseResult.Rejected

    @Test
    fun `valid open_app parses`() {
        val r = ActionContract.parse(
            """{"v":"alalay.action.v1","tool":"open_app","args":{"app":"messages"},"say":"Opening.","confidence":0.9}"""
        )
        val ok = r as ParseResult.Ok
        assertEquals(Tool.OPEN_APP, ok.action.tool)
        assertEquals(ActionArgs.OpenApp("messages"), ok.action.args)
        assertEquals(0.9, ok.action.confidence, 0.001)
    }

    @Test
    fun `non-json text rejected`() {
        assertEquals(ParseRejection.NOT_JSON, rej("let me open that for you").reason)
    }

    @Test
    fun `trailing prose rejected`() {
        assertEquals(
            ParseRejection.NOT_JSON,
            rej("""{"v":"alalay.action.v1","tool":"go_back","args":{},"confidence":1.0} done""").reason,
        )
    }

    @Test
    fun `bad version rejected`() {
        assertEquals(
            ParseRejection.BAD_VERSION,
            rej("""{"v":"v2","tool":"go_back","args":{},"confidence":1.0}""").reason,
        )
    }

    @Test
    fun `internal tools rejected - model can never send or stop`() {
        for (t in listOf("confirm_send", "emergency_stop", "memory_write")) {
            val r = rej("""{"v":"alalay.action.v1","tool":"$t","args":{},"confidence":1.0}""")
            assertEquals("$t must be INTERNAL_TOOL", ParseRejection.INTERNAL_TOOL, r.reason)
        }
    }

    @Test
    fun `unknown tool rejected`() {
        assertEquals(
            ParseRejection.UNKNOWN_TOOL,
            rej("""{"v":"alalay.action.v1","tool":"send_message","args":{},"confidence":1.0}""").reason,
        )
    }

    @Test
    fun `missing args rejected`() {
        assertEquals(
            ParseRejection.BAD_ARGS,
            rej("""{"v":"alalay.action.v1","tool":"click","confidence":0.8}""").reason,
        )
    }

    @Test
    fun `click needs target or coordinates`() {
        assertEquals(
            ParseRejection.BAD_ARGS,
            rej("""{"v":"alalay.action.v1","tool":"click","args":{},"confidence":0.8}""").reason,
        )
    }

    @Test
    fun `click coordinate args parse`() {
        val r = ActionContract.parse(
            """{"v":"alalay.action.v1","tool":"click","args":{"x":100,"y":200},"confidence":0.9}"""
        ) as ParseResult.Ok
        assertEquals(ActionArgs.Click(null, 100, 200), r.action.args)
    }

    @Test
    fun `draft_reply over limit rejected`() {
        val long = "x".repeat(501)
        assertEquals(
            ParseRejection.BAD_ARGS,
            rej("""{"v":"alalay.action.v1","tool":"draft_reply","args":{"text":"$long"},"confidence":0.9}""").reason,
        )
    }

    @Test
    fun `ask_user too many options rejected`() {
        val opts = (1..5).joinToString(",") { "\"o$it\"" }
        assertEquals(
            ParseRejection.BAD_ARGS,
            rej("""{"v":"alalay.action.v1","tool":"ask_user","args":{"question":"which?","options":[$opts]},"confidence":0.9}""").reason,
        )
    }

    @Test
    fun `missing confidence rejected`() {
        assertEquals(
            ParseRejection.BAD_ARGS,
            rej("""{"v":"alalay.action.v1","tool":"go_back","args":{}}""").reason,
        )
    }

    @Test
    fun `injected extra keys are stripped`() {
        val r = ActionContract.parse(
            """{"v":"alalay.action.v1","tool":"draft_reply","args":{"text":"hi","send":true},"confidence":0.9,"role":"system"}"""
        ) as ParseResult.Ok
        // No `send` field can survive parsing — args is exactly DraftReply.
        assertEquals(ActionArgs.DraftReply("hi"), r.action.args)
    }

    @Test
    fun `scroll direction validated`() {
        assertEquals(
            ParseRejection.BAD_ARGS,
            rej("""{"v":"alalay.action.v1","tool":"scroll","args":{"direction":"sideways"},"confidence":0.9}""").reason,
        )
        val ok = ActionContract.parse(
            """{"v":"alalay.action.v1","tool":"scroll","args":{"direction":"down"},"confidence":0.9}"""
        ) as ParseResult.Ok
        assertEquals(ActionArgs.Scroll(ScrollDirection.DOWN), ok.action.args)
    }
}
