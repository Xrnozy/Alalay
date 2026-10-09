package com.alalay.demomessenger

data class Message(val text: String, val outgoing: Boolean, val time: String)

data class Thread(val name: String, val messages: MutableList<Message>)

/**
 * Fixed conversation data (T-06). Two "Mama…" contacts and two "Rosa…"
 * contacts exist so the ambiguity flow has real ambiguous targets.
 * Mama Remedios' latest message is the acceptance-test fixture:
 * "Nasaan ka na? Dinner is ready."
 */
object Seed {

    val threads = mutableListOf(
        Thread(
            "Mama Remedios",
            mutableListOf(
                Message("Good morning anak", false, "8:02 AM"),
                Message("Good morning Ma", true, "8:15 AM"),
                Message("Nasaan ka na? Dinner is ready.", false, "6:42 PM"),
            ),
        ),
        Thread(
            "Mama Linda",
            mutableListOf(
                Message("Salamat sa pasalubong", false, "5:10 PM"),
            ),
        ),
        Thread(
            "Rosa Cruz",
            mutableListOf(
                Message("Okay, see you tomorrow!", false, "4:55 PM"),
            ),
        ),
        Thread(
            "Rosa Diaz",
            mutableListOf(
                Message("The meeting moved to 3pm.", false, "3:20 PM"),
            ),
        ),
        Thread(
            "Juan",
            mutableListOf(
                Message("Tara, laro bukas?", false, "2:11 PM"),
            ),
        ),
    )
}
