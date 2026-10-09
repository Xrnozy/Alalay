package com.alalay.app.agent

/**
 * Intent extraction for the rules-only planner (T-23 contingency, RISK-01)
 * and for spoken feedback context. Pure string matching — EN + Taglish.
 */
sealed interface TaskIntent {
    data class ReadMessage(val contact: String) : TaskIntent
    data class SendMessage(val contact: String, val text: String) : TaskIntent
    data object DescribeScreen : TaskIntent
    data class Scroll(val direction: ScrollDirection) : TaskIntent
    data class OpenApp(val app: String) : TaskIntent
    data object Unknown : TaskIntent
}

object IntentParser {

    private val readPatterns = listOf(
        // "read Mama's latest message", "read the latest message from Mama"
        Regex("read (?:my |the )?(?:latest|last|new|huling)??\\s*(?:message|messages|text|txt) (?:from|ni|of|by) (.+)"),
        Regex("read (.+?)(?:'s|s) (?:latest|last|new|huling)? ?(?:message|messages|text|txt)"),
        Regex("read (.+?)(?:'s|s) messages"),
        // "basahin mo yung huling message ni Mama"
        Regex("basahin (?:mo |ko |natin )?(?:yung |ang )?(?:huling |latest |last )?(?:message|mensahe|text) (?:ni|kay|ng) (.+)"),
        Regex("check (?:the )?(?:message|messages) (?:from|ni) (.+)"),
        Regex("what did (.+?) (?:say|sabi)"),
        Regex("ano(?:ng|)(?: ang)? (?:sabi|sinabi) ni (.+)"),
    )

    private val sendPatterns = listOf(
        // "sabihin kay Mama, papunta na ako" / "sabihin kay Mama na papunta na ako"
        Regex("sabihin (?:mo )?(?:kay|kina) (.+?)[,]\\s*(.+)"),
        Regex("sabihin (?:mo )?(?:kay|kina) (.+?) na (.+)"),
        // "reply to Mama: I'm on my way" / "reply to Mama I'm on my way"
        Regex("reply to (.+?)[:;,]\\s*(.+)"),
        Regex("reply to (.+?) (?:with|na|saying) (.+)"),
        // "tell Mama I'm on my way" / "message Mama na papunta na ako"
        Regex("tell (.+?)[:;,]\\s*(.+)"),
        Regex("tell (.+?) (?:that|na) (.+)"),
        Regex("(?:send|text|message) (?:a message |)?to (.+?)[:;,]\\s*(.+)"),
        Regex("message (.+?) na (.+)"),
    )

    private val describePatterns = listOf(
        Regex("what'?s on (?:my |the )?screen"),
        Regex("describe (?:the |my )?screen"),
        Regex("ano(?:ng|)(?: ang)? nasa screen"),
        Regex("ano (?:ang )?nasa screen"),
        Regex("tingnan mo (?:ang |yung )?screen"),
        Regex("what (?:do you see|is on screen)"),
        Regex("read (?:the |my )?screen"),
    )

    private val scrollDownWords =
        setOf("scroll down", "baba pa", "pababa", "scroll down pa", "down pa", "scroll pa baba", "ibaba pa")
    private val scrollUpWords =
        setOf("scroll up", "taas pa", "pataas", "scroll up pa", "up pa", "scroll pa taas", "itaas pa")

    private fun clean(raw: String): String =
        " " + raw.lowercase()
            .replace(Regex("[\"'`]"), "")
            .replace(Regex("\\s+"), " ")
            .trim() + " "

    private fun stripTailPunct(s: String) = s.trim().trimEnd(',', '.', '!', '?', ' ').trim()

    fun parse(utterance: String): TaskIntent {
        val t = clean(utterance)

        for (p in sendPatterns) {
            val m = p.find(t) ?: continue
            val contact = stripTailPunct(m.groupValues[1])
            val text = stripTailPunct(m.groupValues[2])
            if (contact.isNotEmpty() && text.isNotEmpty()) {
                return TaskIntent.SendMessage(contact, text)
            }
        }
        for (p in readPatterns) {
            val m = p.find(t) ?: continue
            val contact = stripTailPunct(m.groupValues[1])
            if (contact.isNotEmpty()) return TaskIntent.ReadMessage(contact)
        }
        if (describePatterns.any { it.containsMatchIn(t) }) return TaskIntent.DescribeScreen
        if (scrollDownWords.any { t.contains(" $it ") }) {
            return TaskIntent.Scroll(ScrollDirection.DOWN)
        }
        if (scrollUpWords.any { t.contains(" $it ") }) {
            return TaskIntent.Scroll(ScrollDirection.UP)
        }
        // Residual open-app phrasing the router's strict pattern missed.
        val openM = Regex("(?:open|buksan|i-open) (?:mo )?(?:ang |yung |the )?(.+?)(?:\\s|$)").find(t)
        if (openM != null && t.trim().let { it.startsWith(" open") || it.startsWith(" buksan") || it.startsWith(" i open") }) {
            val app = stripTailPunct(openM.groupValues[1])
            if (app.isNotEmpty()) return TaskIntent.OpenApp(app)
        }
        return TaskIntent.Unknown
    }
}
