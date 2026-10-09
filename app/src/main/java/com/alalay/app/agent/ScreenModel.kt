package com.alalay.app.agent

import kotlin.math.min

/** Bounds of a UI element on screen. */
data class RectI(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val isEmpty get() = right <= left || bottom <= top
    val area get() = (right - left).toLong() * (bottom - top)
    fun contains(x: Int, y: Int) = x in left..right && y in top..bottom
    fun contains(other: RectI) =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}

/** One flattened UI-tree element (spec 04-§3 ScreenInspector, FR-04). */
data class UiElement(
    val index: Int,
    val text: String,
    val contentDesc: String,
    val className: String,
    val viewId: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isCheckable: Boolean,
    val bounds: RectI,
    val depth: Int,
) {
    /** What the user would call this element; empty if it has no label. */
    val label: String get() = text.ifEmpty { contentDesc }
    val shortClass: String get() = className.substringAfterLast('.')
}

data class ScreenModel(
    val foregroundPackage: String?,
    val elements: List<UiElement>,
    /** Full display bounds — for validating x,y click args (07-§2). */
    val display: RectI = RectI(0, 0, 1080, 2400),
) {
    val isEmpty get() = elements.isEmpty()

    /** Elements whose label fuzzy-matches the query (07-§5.4). */
    fun labelMatches(query: String): List<UiElement> =
        elements.filter { matchesLabel(it.label, query) }

    /**
     * Matches with nested same-label elements collapsed — a clickable row
     * containing a TextView with the same label is ONE target, not two.
     */
    fun distinctTargets(query: String): List<UiElement> {
        val raw = labelMatches(query)
        val kept = mutableListOf<UiElement>()
        for (e in raw.sortedByDescending { it.bounds.area }) {
            val nested = kept.any { k ->
                k.bounds.contains(e.bounds) &&
                    labelsOverlap(k.label, e.label)
            }
            if (nested) {
                // Replace a kept non-clickable outer with this clickable inner.
                val idx = kept.indexOfFirst { k ->
                    k.bounds.contains(e.bounds) && !k.isClickable && e.isClickable
                }
                if (idx >= 0) kept[idx] = e
                continue
            }
            kept += e
        }
        return kept.sortedBy { it.index }
    }

    private fun labelsOverlap(a: String, b: String): Boolean {
        val na = a.trim().lowercase()
        val nb = b.trim().lowercase()
        return na.isNotEmpty() && nb.isNotEmpty() && (na.contains(nb) || nb.contains(na))
    }

    fun editableElements(): List<UiElement> = elements.filter { it.isEditable }

    /** First editable field's current text, or null when none exists. */
    fun firstEditableText(): String? = editableElements().firstOrNull()?.text
}

private fun normalize(s: String): String =
    s.trim().lowercase().replace(Regex("\\s+"), " ")

/** Fuzzy label match per 07-§5.4: contains (ci) or Levenshtein ≤2 for short labels. */
fun matchesLabel(label: String, query: String): Boolean {
    val l = normalize(label)
    val q = normalize(query)
    if (q.isEmpty()) return false
    if (l.contains(q) || q.contains(l) && l.isNotEmpty()) return true
    if (l.length <= 8) return levenshtein(l, q) <= 2
    return false
}

fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1) { 0 }
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
        }
        prev = cur
    }
    return prev[b.length]
}
