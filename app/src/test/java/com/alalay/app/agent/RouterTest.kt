package com.alalay.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {

    @Test
    fun `stop phrases - english and tagalog`() {
        for (t in listOf("stop", "Stop!", "cancel", "tigil", "tumigil ka", "huwag na", "wag na", "tama na")) {
            assertEquals("$t must be Stop", RouteResult.Stop, Router.route(t))
        }
    }

    @Test
    fun `stop wins inside a pending confirmation`() {
        assertEquals(
            RouteResult.Stop,
            Router.route("stop", RouterMode.AWAITING_CONFIRMATION),
        )
    }

    @Test
    fun `yes and no while confirming`() {
        for (t in listOf("yes", "oo", "sige", "send it", "ipadala mo", "okay")) {
            assertEquals("$t must Confirm", RouteResult.Confirm, Router.route(t, RouterMode.AWAITING_CONFIRMATION))
        }
        for (t in listOf("no", "hindi", "nope", "ayaw ko", "don't")) {
            assertEquals("$t must Deny", RouteResult.Deny, Router.route(t, RouterMode.AWAITING_CONFIRMATION))
        }
    }

    @Test
    fun `unrelated utterance while confirming is unclear not deny`() {
        // "don't guess" rule: gibberish must not resolve the send gate.
        assertEquals(
            RouteResult.AnswerUnclear,
            Router.route("what's the weather", RouterMode.AWAITING_CONFIRMATION),
        )
    }

    @Test
    fun `bare yes or no with nothing pending is ignored`() {
        assertEquals(RouteResult.Ignored, Router.route("yes"))
        assertEquals(RouteResult.Ignored, Router.route("hindi"))
    }

    @Test
    fun `ordinals while awaiting answer`() {
        assertEquals(RouteResult.AnswerOrdinal(0), Router.route("the first one", RouterMode.AWAITING_ANSWER))
        assertEquals(RouteResult.AnswerOrdinal(0), Router.route("una", RouterMode.AWAITING_ANSWER))
        assertEquals(RouteResult.AnswerOrdinal(1), Router.route("second", RouterMode.AWAITING_ANSWER))
        assertEquals(RouteResult.AnswerOrdinal(1), Router.route("ang pangalawa", RouterMode.AWAITING_ANSWER))
        assertEquals(RouteResult.AnswerOrdinal(2), Router.route("number three", RouterMode.AWAITING_ANSWER))
        assertEquals(RouteResult.AnswerOrdinal(3), Router.route("ikaapat", RouterMode.AWAITING_ANSWER))
    }

    @Test
    fun `name answer while awaiting answer`() {
        val r = Router.route("Rosa Cruz", RouterMode.AWAITING_ANSWER)
        assertEquals(RouteResult.AnswerName("rosa cruz"), r)
        val si = Router.route("si Mama", RouterMode.AWAITING_ANSWER)
        assertEquals(RouteResult.AnswerName("mama"), si)
    }

    @Test
    fun `back and repeat controls`() {
        for (t in listOf("go back", "back", "balik", "bumalik")) {
            assertEquals("$t", RouteResult.GoBack, Router.route(t))
        }
        for (t in listOf("repeat", "ulitin", "say again")) {
            assertEquals("$t", RouteResult.Repeat, Router.route(t))
        }
    }

    @Test
    fun `open app extraction`() {
        assertEquals(RouteResult.OpenApp("gcash"), Router.route("open GCash"))
        assertEquals(RouteResult.OpenApp("messenger"), Router.route("buksan mo ang messenger"))
        assertEquals(RouteResult.OpenApp("camera"), Router.route("i-open camera"))
        assertEquals(RouteResult.OpenApp("messenger"), Router.route("open the messenger app"))
    }

    @Test
    fun `task passes through to planner`() {
        val r = Router.route("read mama's latest message")
        assertTrue(r is RouteResult.Task)
    }

    @Test
    fun `empty and noise`() {
        assertEquals(RouteResult.Empty, Router.route(""))
        assertEquals(RouteResult.Empty, Router.route("   "))
        assertEquals(RouteResult.Empty, Router.route("?!?!"))
    }
}
