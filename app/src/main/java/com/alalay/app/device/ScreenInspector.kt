package com.alalay.app.device

import com.alalay.app.accessibility.AlalayAccessibilityService
import com.alalay.app.agent.ScreenModel
import com.alalay.app.agent.ScreenSource
import com.alalay.app.agent.UiElement

/**
 * ScreenInspector (FR-04): reads the foreground accessibility tree and
 * compacts it to the bounded text format the planner consumes
 * (07-§4 slot 4: ≤60 elements, ≤50 chars per label, `…(n more)` tail).
 */
class ScreenInspector(
    private val serviceProvider: () -> AlalayAccessibilityService? = {
        AlalayAccessibilityService.instance
    },
) : ScreenSource {

    fun isAvailable(): Boolean = serviceProvider() != null

    override suspend fun dump(): ScreenModel = dumpSync()

    fun dumpSync(): ScreenModel {
        val svc = serviceProvider() ?: return ScreenModel(null, emptyList())
        return ScreenModel(
            foregroundPackage = svc.foregroundPackage(),
            elements = svc.dumpElements(),
            display = svc.displayBounds(),
        )
    }

    // ── Verification helpers (D-5) ─────────────────────────────────

    fun appIsForeground(pkgSubstring: String): Boolean =
        dumpSync().foregroundPackage?.contains(pkgSubstring, ignoreCase = true) == true

    /** Compose field currently contains the given draft text. */
    fun fieldContains(text: String): Boolean =
        dumpSync().editableElements().any { it.text.contains(text) }

    /** Compose field exists and is empty. */
    fun fieldIsEmpty(): Boolean =
        dumpSync().editableElements().all { it.text.isBlank() }

    /** A message bubble containing [text] exists on screen. */
    fun messageVisible(text: String): Boolean =
        dumpSync().elements.any {
            it.viewId.endsWith("message_text") && it.text.contains(text)
        } || dumpSync().elements.any {
            !it.isEditable && it.text.contains(text)
        }

    /**
     * The compose/editable field's label or hint-bearing element, used for
     * draft verification and clearing.
     */
    fun composeField(): UiElement? =
        dumpSync().editableElements().firstOrNull { e ->
            COMPOSE_HINTS.any { hint ->
                e.viewId.contains(hint, true) ||
                    e.contentDesc.contains(hint, true)
            }
        } ?: dumpSync().editableElements().firstOrNull()

    companion object {
        private val COMPOSE_HINTS = listOf("compose", "message", "reply", "type")

        internal val CLASS_SHORT = mapOf(
            "TextView" to "text", "Button" to "btn", "EditText" to "edit",
            "ImageButton" to "imgbtn", "ImageView" to "img",
            "RecyclerView" to "list", "ListView" to "list",
            "ScrollView" to "scroll", "CheckBox" to "check",
            "Switch" to "switch", "ViewGroup" to "group",
            "LinearLayout" to "group", "FrameLayout" to "group",
            "ConstraintLayout" to "group", "View" to "view",
        )

        const val MAX_ELEMENTS = 60
        const val MAX_LABEL = 50
    }
}

/**
 * Pure compactor — unit-testable on JVM (07-§4 slot 4 format).
 * `[i]* type "label" {flags} center:(x,y)` with `*` marking elements that
 * match task keywords and `…(n more)` when the list is truncated.
 */
object ScreenCompactor {

    fun compact(screen: ScreenModel, taskKeywords: List<String> = emptyList()): String {
        val sb = StringBuilder()
        sb.append("app:").append(screen.foregroundPackage ?: "?").append('\n')
        val shown = screen.elements.take(ScreenInspector.MAX_ELEMENTS)
        shown.forEach { e ->
            sb.append('[').append(e.index).append(']')
            if (taskKeywords.any { k -> e.label.contains(k, ignoreCase = true) }) sb.append('*')
            sb.append(' ').append(shortClass(e))
            val label = e.label.take(ScreenInspector.MAX_LABEL)
            if (label.isNotEmpty()) sb.append(" \"").append(label).append('"')
            val flags = buildList {
                if (e.isClickable) add("tap")
                if (e.isEditable) add("edit")
                if (e.isScrollable) add("scroll")
                if (e.isCheckable) add("check")
            }
            if (flags.isNotEmpty()) sb.append(' ').append(flags.joinToString(" ", "{", "}"))
            sb.append(" center:(").append(e.bounds.centerX).append(',')
                .append(e.bounds.centerY).append(')')
            sb.append('\n')
        }
        val hidden = screen.elements.size - shown.size
        if (hidden > 0) sb.append("…(").append(hidden).append(" more)")
        return sb.toString().trimEnd()
    }

    fun taskKeywords(task: String): List<String> =
        task.lowercase()
            .split(Regex("[^a-z0-9ñ]+"))
            .filter { it.length >= 3 }
            .filterNot { it in STOP_WORDS }

    private val STOP_WORDS = setOf(
        "the", "and", "for", "you", "mo", "ko", "ang", "yung", "nang", "mga",
        "nasa", "ni", "kay", "from", "message", "read", "basahin", "open",
        "buksan", "latest", "last", "huling", "please", "pak", "nga",
    )

    private fun shortClass(e: UiElement): String =
        ScreenInspector.CLASS_SHORT[e.shortClass] ?: e.shortClass.lowercase()
}
