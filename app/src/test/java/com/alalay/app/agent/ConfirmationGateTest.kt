package com.alalay.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationGateTest {

    @Test
    fun `send is reachable only through confirm`() {
        val g = ConfirmationGate()
        // SEND_EXEC cannot be entered from NONE...
        assertFalse(g.beginSend())
        g.open("Mama", "hello")
        assertEquals(ConfirmationGate.State.PENDING, g.state)
        // ...nor directly from PENDING — CONFIRMED is mandatory (D-4).
        assertFalse(g.beginSend())
        assertTrue(g.confirm())
        assertTrue(g.beginSend())
        assertEquals(ConfirmationGate.State.SEND_EXEC, g.state)
    }

    @Test
    fun `confirmed send completes`() {
        val g = ConfirmationGate()
        g.open("Mama", "hello")
        g.confirm()
        g.beginSend()
        assertTrue(g.completeSend(true))
        assertEquals(ConfirmationGate.State.SENT, g.state)
    }

    @Test
    fun `failed send verification lands in FAIL`() {
        val g = ConfirmationGate()
        g.open("Mama", "hello")
        g.confirm()
        g.beginSend()
        assertTrue(g.completeSend(false))
        assertEquals(ConfirmationGate.State.FAIL, g.state)
    }

    @Test
    fun `deny and expire only from pending`() {
        val g = ConfirmationGate()
        assertFalse(g.deny())
        assertFalse(g.expire())
        g.open("Mama", "hello")
        assertTrue(g.deny())
        assertEquals(ConfirmationGate.State.DENIED, g.state)

        val g2 = ConfirmationGate()
        g2.open("Mama", "hi")
        assertTrue(g2.expire())
        assertEquals(ConfirmationGate.State.EXPIRED, g2.state)
    }

    @Test
    fun `confirm after deny does nothing`() {
        val g = ConfirmationGate()
        g.open("Mama", "hi")
        g.deny()
        assertFalse(g.confirm())
        assertFalse(g.beginSend())
    }

    @Test
    fun `reset clears pending draft`() {
        val g = ConfirmationGate()
        g.open("Mama", "hi")
        g.reset()
        assertEquals(ConfirmationGate.State.NONE, g.state)
        assertEquals(null, g.pendingRecipient)
        assertEquals(null, g.pendingText)
    }
}
