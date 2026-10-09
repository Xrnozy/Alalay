package com.alalay.app.agent

/**
 * Deterministic router for control utterances (FR-03, D-3; spec 07-§11).
 * Runs before the LLM; control phrases are matched in code and never
 * reach the model. Decision is pure string matching — far under the
 * 300 ms NFR-02 budget.
 */

enum class RouterMode {
    /** Normal command listening. */
    COMMAND,

    /** A confirmation question (yes/no) is pending. */
    AWAITING_CONFIRMATION,

    /** An ambiguity question (ordinals/names) is pending. */
    AWAITING_ANSWER,
}

sealed interface RouteResult {
    /** "stop"/"cancel"/"tigil" — halt everything (FR-09). */
    data object Stop : RouteResult

    /** Affirmative answer while AWAITING_CONFIRMATION. */
    data object Confirm : RouteResult

    /** Negative answer while AWAITING_CONFIRMATION. */
    data object Deny : RouteResult

    /** "go back" / "balik" — global back, executed in code. */
    data object GoBack : RouteResult

    /** "repeat"/"ulitin" — re-speak the last message. */
    data object Repeat : RouteResult

    /** "open X" / "buksan mo ang X" — direct open_app shortcut (CMD-01). */
    data class OpenApp(val appQuery: String) : RouteResult

    /** Ordinal answer while AWAITING_ANSWER (0-based). */
    data class AnswerOrdinal(val index: Int) : RouteResult

    /** Name answer while AWAITING_ANSWER — re-matched against options. */
    data class AnswerName(val name: String) : RouteResult

    /** Unparseable answer while awaiting one. */
    data object AnswerUnclear : RouteResult

    /** A real task for the planner (LLM or rules). */
    data class Task(val utterance: String) : RouteResult

    /** Silence/garbage — TPL-02. */
    data object Empty : RouteResult

    /** Control phrase with nothing pending (e.g. "yes" out of context, 07-§11 #20). */
    data object Ignored : RouteResult
}

object Router {

    private val stopWords = setOf(
        "stop", "cancel", "tigil", "tumigil", "itigil", "hinto", "hinto na",
        "huwag", "wag", "huwag na", "wag na", "tama na", "cancel it", "i-cancel",
    )

    private val yesWords = setOf(
        "yes", "oo", "sige", "confirm", "send", "send it", "ipadala", "ipadala mo",
        "go", "ok", "okay", "sure", "yep", "yeah", "sige na", "oo na",
    )

    private val noWords = setOf(
        // "don t" is what normalize() produces from "don't".
        "no", "hindi", "nope", "ayaw", "ayoko", "dont", "don't", "don t", "do not",
        "huwag", "wag", "no way", "hindi ko", "hindi na",
    )

    private val backWords = setOf(
        "go back", "back", "balik", "bumalik", "atras", "bumalik ka", "balik ka",
    )

    private val repeatWords = setOf(
        "repeat", "ulitin", "again", "say again", "repeat that", "ulit", "paulit",
    )

    /** Ordinal words → 0-based index, EN + Tagalog. */
    private val ordinals: List<Set<String>> = listOf(
        setOf("first", "una", "ang una", "the first one", "the first", "one", "1", "isa", "unang", "number one", "number 1"),
        setOf("second", "pangalawa", "ang pangalawa", "the second one", "the second", "two", "2", "dalawa", "ikalawa", "number two", "number 2"),
        setOf("third", "ikatlo", "ang ikatlo", "the third one", "the third", "three", "3", "tatlo", "ikatlong", "number three", "number 3"),
        setOf("fourth", "ikaapat", "ang ikaapat", "the fourth one", "the fourth", "four", "4", "apat", "ika-apat", "number four", "number 4"),
    )

    private val openAppRegex = Regex(
        // normalize() maps "i-open" → "i open", so the hyphen is a space here.
        "^(?:i ?open|open|buksan|bukas)\\b" +
            "(?:\\s+mo)?(?:\\s+nga)?(?:\\s+na)?" +
            "(?:\\s+(?:ang|yung|ng|si|the))?" +
            "(?:\\s+(?:app|application|application na))?\\s*(.*)$"
    )

    private val nameParticles = Regex("^(?:si|kay|yung|ang|ni|sa|the|option|number|numero)\\s+")

    fun normalize(t: String): String =
        t.lowercase()
            .replace(Regex("[^a-z0-9ñ\\s-]"), " ")
            .replace(Regex("[\\s-]+"), " ")
            .trim()

    /** Multi-word phrases must be checked before single tokens. */
    private fun containsPhrase(t: String, phrases: Set<String>): Boolean {
        val padded = " $t "
        return phrases.any { padded.contains(" ${it.replace('-', ' ')} ") } || t in phrases
    }

    fun route(transcript: String, mode: RouterMode = RouterMode.COMMAND): RouteResult {
        val t = normalize(transcript)
        if (t.isEmpty()) return RouteResult.Empty

        // Stop is checked first in every mode (FR-09, D-3).
        if (containsPhrase(t, stopWords)) return RouteResult.Stop

        when (mode) {
            RouterMode.AWAITING_CONFIRMATION -> {
                // Deny bias: if any no-word is present it wins over a yes-word
                // (the wrong direction here sends a message — D-4).
                if (containsPhrase(t, backWords)) return RouteResult.Deny
                if (containsPhrase(t, noWords)) return RouteResult.Deny
                if (containsPhrase(t, yesWords)) return RouteResult.Confirm
                return RouteResult.AnswerUnclear
            }

            RouterMode.AWAITING_ANSWER -> {
                for ((i, words) in ordinals.withIndex()) {
                    if (containsPhrase(t, words)) return RouteResult.AnswerOrdinal(i)
                }
                val stripped = t.replace(nameParticles, "").trim()
                if (stripped.isNotEmpty()) return RouteResult.AnswerName(stripped)
                return RouteResult.AnswerUnclear
            }

            RouterMode.COMMAND -> {
                // Back/repeat checked before yes/no: "go back" must not be
                // eaten by the confirm-word "go".
                if (containsPhrase(t, backWords)) return RouteResult.GoBack
                if (containsPhrase(t, repeatWords)) return RouteResult.Repeat
                // A bare yes/no with nothing pending is ignored (07-§11 #20) —
                // exact match only, or "send money to mama" would be swallowed.
                if (t in yesWords || t in noWords) {
                    return RouteResult.Ignored
                }
                val m = openAppRegex.find(t)
                if (m != null) {
                    val name = m.groupValues[1]
                        .replace(Regex("\\s+(app|application)$"), "")
                        .trim()
                    if (name.isNotEmpty()) return RouteResult.OpenApp(name)
                }
                return RouteResult.Task(transcript.trim())
            }
        }
    }
}
