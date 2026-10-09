package com.alalay.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.alalay.app.agent.RectI
import com.alalay.app.agent.UiElement

/**
 * Alalay Screen Control — UI-tree reading and gesture execution.
 * Clean-room Kotlin implementation on standard Android accessibility APIs
 * (06-TRD §0.7: patterns only, no copied code from private-agent).
 */
class AlalayAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AlalayAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        private const val OWN_PACKAGE = "com.alalay.app"
    }

    /** The agent loop listens for the screen settling after an action. */
    @Volatile
    var onContentChanged: (() -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onContentChanged?.invoke()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ── Screen reading (FR-04) ─────────────────────────────────────

    /** Flattened elements from every non-Alalay application window. */
    fun dumpElements(): List<UiElement> {
        val out = mutableListOf<UiElement>()
        val wins = windows
        if (wins.isNullOrEmpty()) {
            val root = rootInActiveWindow ?: return emptyList()
            if (root.packageName?.toString() != OWN_PACKAGE) {
                traverse(root, out, 0)
            }
            root.recycle()
            return out
        }
        for (w in wins) {
            // Only real app windows + the IME — skip status/nav bar noise.
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
            ) continue
            val root = w.root ?: continue
            if (root.packageName?.toString() == OWN_PACKAGE) {
                root.recycle()
                continue
            }
            traverse(root, out, 0)
            root.recycle()
        }
        return out
    }

    private fun traverse(node: AccessibilityNodeInfo, out: MutableList<UiElement>, depth: Int) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val zeroSize = rect.width() <= 0 || rect.height() <= 0

        if (node.isVisibleToUser && !zeroSize && isIncluded(node)) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            out.add(
                UiElement(
                    index = out.size,
                    text = text,
                    contentDesc = desc,
                    className = node.className?.toString().orEmpty(),
                    viewId = node.viewIdResourceName.orEmpty(),
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isCheckable = node.isCheckable,
                    bounds = RectI(rect.left, rect.top, rect.right, rect.bottom),
                    depth = depth,
                )
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(child, out, depth + 1)
            child.recycle()
        }
    }

    fun foregroundPackage(): String? {
        val wins = windows ?: return rootInActiveWindow?.packageName?.toString()
        var fallback: String? = null
        for (w in wins) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val pkg = w.root?.packageName?.toString()
            w.root?.recycle()
            if (w.isActive || w.isFocused) return pkg
            if (fallback == null && pkg != OWN_PACKAGE) fallback = pkg
        }
        return fallback
    }

    fun displayBounds(): RectI {
        val r = resources.displayMetrics
        return RectI(0, 0, r.widthPixels, r.heightPixels)
    }

    // ── Actions (FR-05) ────────────────────────────────────────────

    fun clickByText(target: String): Boolean {
        eachAppRoot { root ->
            // Exact-label pass, then contains pass; prefer non-editable
            // targets so a text field holding the same word isn't tapped.
            findAndClick(root, target, exactOnly = true, skipEditable = true) ||
                findAndClick(root, target, exactOnly = false, skipEditable = true) ||
                findAndClick(root, target, exactOnly = true, skipEditable = false) ||
                findAndClick(root, target, exactOnly = false, skipEditable = false)
        }?.let { return it }
        return false
    }

    /** Click the element with this dump index (parity with dumpElements). */
    fun clickByIndex(index: Int): Boolean {
        val found = findNthIncluded(index) ?: return false
        val ok = clickNodeOrParent(found)
        found.recycle()
        return ok
    }

    private fun findNthIncluded(index: Int): AccessibilityNodeInfo? {
        var current = 0
        var hit: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo) {
            if (hit != null) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val zeroSize = rect.width() <= 0 || rect.height() <= 0
            if (node.isVisibleToUser && !zeroSize && isIncluded(node)) {
                if (current == index) {
                    hit = AccessibilityNodeInfo.obtain(node)
                    return
                }
                current++
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)
                child.recycle()
                if (hit != null) return
            }
        }
        eachAppRoot<Unit> { walk(it); null }
        return hit
    }

    private fun isIncluded(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        return text.isNotEmpty() || desc.isNotEmpty() ||
            node.isClickable || node.isEditable || node.isScrollable
    }

    private fun findAndClick(
        node: AccessibilityNodeInfo,
        target: String,
        exactOnly: Boolean,
        skipEditable: Boolean,
    ): Boolean {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val matches = if (exactOnly) {
            text.equals(target, true) || desc.equals(target, true)
        } else {
            text.contains(target, true) || desc.contains(target, true)
        }
        if (matches && (!skipEditable || !node.isEditable) && clickNodeOrParent(node)) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val done = findAndClick(child, target, exactOnly, skipEditable)
            child.recycle()
            if (done) return true
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        while (target != null && !target.isClickable) {
            val parent = target.parent
            target.recycle()
            target = parent
        }
        if (target != null) {
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target.recycle()
            if (ok) return true
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return !rect.isEmpty && tap(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Type into the editable node matching [fieldHint] ("message",
     * "compose"...) or the first editable on screen (FR-05 draft_reply —
     * never sends).
     */
    fun typeText(text: String, fieldHint: String? = null): Boolean {
        eachAppRoot { root ->
            val edit = findEditable(root, fieldHint)
                ?: if (!fieldHint.isNullOrEmpty()) findEditable(root, null) else null
            if (edit != null) {
                edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                    )
                }
                edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } else false
        }?.let { return it }
        return false
    }

    fun clearText(fieldHint: String? = null): Boolean = typeText("", fieldHint)

    private fun findEditable(
        node: AccessibilityNodeInfo,
        hint: String?,
    ): AccessibilityNodeInfo? {
        if (node.isEditable) {
            if (hint.isNullOrEmpty()) return node
            val fields = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
                node.hintText?.toString(),
                node.viewIdResourceName,
            )
            if (fields.any { it.contains(hint, ignoreCase = true) }) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child, hint)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    fun scroll(direction: String): Boolean {
        val action = when (direction.lowercase()) {
            "up", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        eachAppRoot { root ->
            findScrollable(root)?.performAction(action) ?: false
        }?.let { return it }
        return false
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollable(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** Walk every application-window root; first non-null result wins. */
    private inline fun <T> eachAppRoot(block: (AccessibilityNodeInfo) -> T): T? {
        val wins = windows
        if (wins.isNullOrEmpty()) {
            val root = rootInActiveWindow ?: return null
            val res = if (root.packageName?.toString() != OWN_PACKAGE) block(root) else null
            root.recycle()
            return res
        }
        for (w in wins) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = w.root ?: continue
            if (root.packageName?.toString() == OWN_PACKAGE) {
                root.recycle()
                continue
            }
            val res = block(root)
            root.recycle()
            if (res != null && res != false) return res
        }
        return null
    }

}
