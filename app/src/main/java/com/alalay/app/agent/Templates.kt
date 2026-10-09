package com.alalay.app.agent

/**
 * Spoken response templates TPL-01..TPL-18 (07-Agent-Specs §10).
 * Speaker picks the variant from the user's language preference
 * ('speak_language' pref: "en" | "tl"); English is the default.
 */

enum class Tpl(val en: String, val tl: String) {
    LISTENING("Listening.", "O, nakikinig ako."),
    DIDNT_CATCH("Sorry, I didn't catch that.", "Sorry, di ko narinig. Ulitin mo?"),
    OPENING("Opening {app}.", "Binubuksan ko ang {app}."),
    APP_OPEN("{app} is open.", "Naka-open na ang {app}."),
    APP_NOT_FOUND("I couldn't find {app} on this phone.", "Di ko mahanap ang {app} sa phone na 'to."),
    GETTING_MESSAGE("Getting {name}'s latest message.", "Kinukuha ko ang huling message ni {name}."),
    MESSAGE_FROM("{name} says: {message}", "Sabi ni {name}: {message}"),
    SCREEN_DESC("You're in {app}. I see: {items}.", "Nasa {app} ka. Nakikita ko: {items}."),
    AMBIGUITY("I found {n} matches for {name}: {list}. Which one?", "May {n} akong nahanap na {name}: {list}. Alin?"),
    AMBIGUITY_FAIL("Sorry, I didn't get that. Stopping for now.", "Sorry, di ko gets. Ititigil ko muna."),
    CONFIRM_SEND("Send this to {recipient}: '{text}'. Say yes to send, or no to cancel.",
        "Ipadala ito kay {recipient}: '{text}'. Sabihin 'oo' para ipadala, 'hindi' para i-cancel."),
    CANCELLED_SEND("Cancelled. Nothing was sent.", "Na-cancel. Walang na-send."),
    SENT("Sent.", "Na-send na."),
    FORBIDDEN("Sorry, I can't do {domain}. That's one thing I never do.",
        "Sorry, bawal ako sa {domain}. 'Yan ang di ko talaga ginagawa."),
    SERVICE_LOST("I lost my screen access. Please re-enable Alalay Screen Control in settings.",
        "Nawala ang access ko sa screen. I-enable ulit ang Alalay sa settings."),
    FAILED("Sorry, I couldn't {action}. {reason} Nothing was sent.",
        "Sorry, di ko nagawa ang {action}. {reason} Walang na-send."),
    STOPPED("Stopped.", "Tumigil na."),
    DONE("Done. {summary}", "Tapos na. {summary}"),
    SCROLLED("Scrolled.", "Naka-scroll na."),
    NOTHING_MORE("There's nothing more below.", "Wala nang laman sa baba."),
    THINKING("Let me look.", "Titingnan ko."),
    LOW_CONFIDENCE("Did you mean {target}?", "Ang ibig mo ba ay {target}?"),
}

object TemplateRenderer {
    /** Render a template with named args; unknown {keys} are left blank. */
    fun render(tpl: Tpl, lang: String = "en", vararg args: Pair<String, String>): String {
        val base = if (lang.startsWith("tl") || lang.startsWith("fil")) tpl.tl else tpl.en
        var out = base
        for ((k, v) in args) out = out.replace("{$k}", v)
        // ICU regex on Android requires both braces escaped (JVM tolerates "}").
        return out.replace(Regex("\\{[a-z_]+\\}"), "").trim()
    }
}
