package com.alalay.app.agent

import org.junit.Assert.assertFalse
import org.junit.Test

class TemplatesTest {

    private val sampleArgs = arrayOf(
        "app" to "Messenger",
        "name" to "Mama",
        "message" to "Kumain ka na?",
        "items" to "3 chats",
        "n" to "2",
        "list" to "Mama Cruz, Mama Remedios",
        "recipient" to "Mama",
        "text" to "On my way",
        "domain" to "banking",
        "action" to "open the app",
        "reason" to "the screen didn't change.",
        "summary" to "Message sent.",
        "target" to "Messages",
    )

    @Test fun `every template renders with no leftover placeholders (en + tl)`() {
        for (tpl in Tpl.entries) for (lang in listOf("en", "tl", "fil")) {
            val out = TemplateRenderer.render(tpl, lang, *sampleArgs)
            assertFalse("leftover {…} in ${tpl.name}/$lang: $out", out.contains('{') || out.contains('}'))
        }
    }

    @Test fun `unfilled placeholders are stripped, not left literal`() {
        val out = TemplateRenderer.render(Tpl.OPENING, "en") // no args
        assertFalse(out.contains('{'))
    }
}
