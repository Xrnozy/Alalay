package com.alalay.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentParserTest {

    @Test
    fun `read message - english`() {
        assertEquals(
            TaskIntent.ReadMessage("mama"),
            IntentParser.parse("read the latest message from Mama"),
        )
        assertEquals(
            TaskIntent.ReadMessage("rosa cruz"),
            IntentParser.parse("read Rosa Cruz's latest message"),
        )
    }

    @Test
    fun `read message - taglish`() {
        assertEquals(
            TaskIntent.ReadMessage("mama"),
            IntentParser.parse("basahin mo yung huling message ni Mama"),
        )
        assertEquals(
            TaskIntent.ReadMessage("mama"),
            IntentParser.parse("ano ang sabi ni Mama"),
        )
    }

    @Test
    fun `send message - english`() {
        assertEquals(
            TaskIntent.SendMessage("mama", "im on my way"),
            IntentParser.parse("reply to Mama: I'm on my way"),
        )
        assertEquals(
            TaskIntent.SendMessage("rosa", "see you tomorrow"),
            IntentParser.parse("tell Rosa that see you tomorrow"),
        )
    }

    @Test
    fun `send message - taglish`() {
        assertEquals(
            TaskIntent.SendMessage("mama", "papunta na ako"),
            IntentParser.parse("sabihin kay Mama na papunta na ako"),
        )
    }

    @Test
    fun `describe screen`() {
        assertEquals(TaskIntent.DescribeScreen, IntentParser.parse("what's on my screen"))
        assertEquals(TaskIntent.DescribeScreen, IntentParser.parse("ano ang nasa screen"))
    }

    @Test
    fun `scroll`() {
        assertEquals(
            TaskIntent.Scroll(ScrollDirection.DOWN),
            IntentParser.parse("scroll down please"),
        )
        assertEquals(
            TaskIntent.Scroll(ScrollDirection.UP),
            IntentParser.parse("scroll up pa"),
        )
    }

    @Test
    fun `unknown`() {
        assertEquals(TaskIntent.Unknown, IntentParser.parse("xyzzy plugh"))
    }
}
