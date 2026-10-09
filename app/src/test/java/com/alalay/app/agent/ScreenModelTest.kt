package com.alalay.app.agent

import com.alalay.app.el
import com.alalay.app.screenOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenModelTest {

    @Test
    fun `label match is case-insensitive contains`() {
        assertTrue(matchesLabel("Mama Remedios", "mama"))
        assertTrue(matchesLabel("Send", "send"))
        assertFalse(matchesLabel("Gallery", "camera"))
    }

    @Test
    fun `short labels tolerate typos up to distance 2`() {
        assertTrue(matchesLabel("Send", "sned"))     // transposition ≈ 2 edits
        assertFalse(matchesLabel("Send", "zebra"))
    }

    @Test
    fun `levenshtein basics`() {
        assertEquals(0, levenshtein("same", "same"))
        assertEquals(1, levenshtein("send", "sent"))
        assertEquals(3, levenshtein("abc", ""))
    }

    @Test
    fun `distinctTargets collapses nested same-label elements`() {
        val screen = screenOf(
            el(0, "Rosa Cruz", clickable = true, bounds = RectI(0, 0, 1000, 200)),
            el(1, "Rosa Cruz", clickable = false, bounds = RectI(20, 20, 500, 100)),
            el(2, "Rosa Diaz", clickable = true, bounds = RectI(0, 200, 1000, 400)),
        )
        assertEquals(2, screen.distinctTargets("rosa").size)
        assertEquals(1, screen.distinctTargets("rosa cruz").size)
    }

    @Test
    fun `distinctTargets keeps clickable over nested label`() {
        val row = el(0, "Mama", clickable = true, bounds = RectI(0, 0, 1000, 200))
        val inner = el(1, "Mama", clickable = false, bounds = RectI(20, 20, 300, 100))
        val screen = screenOf(row, inner)
        val targets = screen.distinctTargets("mama")
        assertEquals(1, targets.size)
        assertTrue(targets[0].isClickable)
    }
}
