package com.alalay.app.agent

import com.alalay.app.el
import com.alalay.app.screenOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGateTest {

    private fun act(tool: Tool, args: ActionArgs, conf: Double = 0.9) =
        ActionV1(tool, args, "", conf)

    // ── Task-level blocklist (AT-11) ────────────────────────────────

    @Test
    fun `payment tasks refused`() {
        for (u in listOf(
            "send money to mama",
            "open gcash",
            "buy load for my phone",
            "magpadala ng pera",
        )) {
            assertEquals(u, ForbiddenDomain.PAYMENTS, SafetyGate.checkTask(u))
        }
    }

    @Test
    fun `auth tasks refused`() {
        for (u in listOf("type my password", "enter the OTP", "what's my mpin")) {
            assertEquals(u, ForbiddenDomain.AUTHENTICATION, SafetyGate.checkTask(u))
        }
    }

    @Test
    fun `account deletion refused`() {
        assertEquals(
            ForbiddenDomain.ACCOUNT_DELETION,
            SafetyGate.checkTask("delete my account please"),
        )
    }

    @Test
    fun `public posting refused`() {
        assertEquals(
            ForbiddenDomain.PUBLIC_POSTING,
            SafetyGate.checkTask("post this on facebook"),
        )
    }

    @Test
    fun `benign tasks pass`() {
        for (u in listOf(
            "read mama's latest message",
            "open messages",
            "what's on my screen",
            "spin the wheel of names",       // "pin" inside "spin" must not fire
            "reply to rosa: see you there",
        )) {
            assertNull(u, SafetyGate.checkTask(u))
        }
    }

    // ── Action validation (07-§5) ───────────────────────────────────

    @Test
    fun `click with one matching target approved`() {
        val screen = screenOf(el(0, "Mama Remedios", clickable = true))
        val v = SafetyGate.validate(act(Tool.CLICK, ActionArgs.Click("mama", null, null)), screen)
        assertTrue(v is Verdict.Approved)
    }

    @Test
    fun `click with no match rejected NO_TARGET`() {
        val screen = screenOf(el(0, "Settings"))
        val v = SafetyGate.validate(act(Tool.CLICK, ActionArgs.Click("telegram", null, null)), screen)
        assertEquals(RejectReason.NO_TARGET, (v as Verdict.Rejected).reason)
    }

    @Test
    fun `click with two matches asks the user`() {
        val screen = screenOf(
            el(0, "Rosa Cruz", clickable = true),
            el(1, "Rosa Diaz", clickable = true),
        )
        val v = SafetyGate.validate(act(Tool.CLICK, ActionArgs.Click("rosa", null, null)), screen)
        val ask = v as Verdict.AskUser
        assertEquals(2, ask.candidates.size)
        assertTrue(ask.question.contains("Rosa"))
    }

    @Test
    fun `nested same-label elements count as one target`() {
        // A clickable row containing a same-named TextView is one match.
        val screen = screenOf(
            el(0, "Mama Remedios", clickable = true, bounds = RectI(0, 0, 1000, 200)),
            el(1, "Mama Remedios", clickable = false, bounds = RectI(20, 20, 400, 100)),
        )
        val v = SafetyGate.validate(act(Tool.CLICK, ActionArgs.Click("mama", null, null)), screen)
        assertTrue(v is Verdict.Approved)
    }

    @Test
    fun `click coordinates outside display rejected`() {
        val screen = screenOf()
        val v = SafetyGate.validate(act(Tool.CLICK, ActionArgs.Click(null, 9999, 50)), screen)
        assertEquals(RejectReason.NO_TARGET, (v as Verdict.Rejected).reason)
    }

    @Test
    fun `click coordinates inside display approved`() {
        val screen = screenOf()
        val v = SafetyGate.validate(act(Tool.CLICK, ActionArgs.Click(null, 500, 500)), screen)
        assertTrue(v is Verdict.Approved)
    }

    @Test
    fun `draft_reply needs confirmation never direct approval`() {
        val screen = screenOf()
        val v = SafetyGate.validate(act(Tool.DRAFT_REPLY, ActionArgs.DraftReply("on my way")), screen)
        assertTrue(v is Verdict.NeedsConfirmation)
    }

    @Test
    fun `draft containing an otp rejected`() {
        val screen = screenOf()
        val v = SafetyGate.validate(
            act(Tool.DRAFT_REPLY, ActionArgs.DraftReply("your OTP is 123456")), screen
        )
        val r = v as Verdict.Rejected
        assertEquals(RejectReason.FORBIDDEN, r.reason)
        assertEquals(ForbiddenDomain.AUTHENTICATION, r.domain)
    }

    @Test
    fun `open_app with a payment target refused`() {
        val screen = screenOf()
        val v = SafetyGate.validate(act(Tool.OPEN_APP, ActionArgs.OpenApp("gcash")), screen)
        assertEquals(ForbiddenDomain.PAYMENTS, (v as Verdict.Rejected).domain)
    }

    @Test
    fun `foreground payment app blocks everything`() {
        val screen = screenOf(el(0, "Send"), pkg = "com.globe.gcash.android")
        val v = SafetyGate.validate(act(Tool.CLICK, ActionArgs.Click("send", null, null)), screen)
        assertEquals(ForbiddenDomain.PAYMENTS, (v as Verdict.Rejected).domain)
    }

    @Test
    fun `low confidence navigation asks yes or no`() {
        val screen = screenOf(el(0, "Gallery", clickable = true))
        val v = SafetyGate.validate(
            act(Tool.CLICK, ActionArgs.Click("gallery", null, null), conf = 0.3), screen
        )
        val ask = v as Verdict.AskUser
        assertTrue(ask.yesNo)
        assertTrue(ask.question.contains("gallery"))
    }
}
