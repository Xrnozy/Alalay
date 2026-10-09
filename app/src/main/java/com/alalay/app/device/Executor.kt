package com.alalay.app.device

import android.content.Context
import android.content.Intent
import com.alalay.app.accessibility.AlalayAccessibilityService
import com.alalay.app.agent.ActionArgs
import com.alalay.app.agent.ActionPerformer
import com.alalay.app.agent.ActionV1
import com.alalay.app.agent.ExecOutcome

/**
 * Executor (FR-05): performs the primitives behind the model tool set via
 * the accessibility service. `confirmSend` is code-only — reachable only
 * from the confirmation FSM, never from a model action (D-4).
 */
class Executor(
    private val context: Context,
    private val inspector: ScreenInspector,
    private val serviceProvider: () -> AlalayAccessibilityService? = {
        AlalayAccessibilityService.instance
    },
) : ActionPerformer {

    override fun serviceUp(): Boolean = serviceProvider() != null

    override suspend fun perform(action: ActionV1): ExecOutcome {
        val svc = serviceProvider() ?: return ExecOutcome.ServiceUnavailable
        return when (val a = action.args) {
            is ActionArgs.OpenApp -> openApp(a.app)
            is ActionArgs.Click -> when {
                a.target != null ->
                    if (svc.clickByText(a.target)) ExecOutcome.Ok("clicked")
                    else ExecOutcome.Failed("couldn't tap \"${a.target}\"")
                a.x != null && a.y != null ->
                    if (svc.tap(a.x.toFloat(), a.y.toFloat())) ExecOutcome.Ok("tapped")
                    else ExecOutcome.Failed("tap gesture was not dispatched")
                else -> ExecOutcome.Failed("click had no target")
            }
            ActionArgs.ReadText -> readScreen()
            is ActionArgs.Scroll ->
                if (svc.scroll(a.direction.wire)) ExecOutcome.Ok("scrolled ${a.direction.wire}")
                else ExecOutcome.Failed("nothing scrollable on screen")
            ActionArgs.GoBack ->
                if (svc.pressBack()) ExecOutcome.Ok("went back")
                else ExecOutcome.Failed("back did not respond")
            is ActionArgs.DraftReply ->
                if (svc.typeText(a.text, fieldHint = "message")) ExecOutcome.Ok("draft typed")
                else ExecOutcome.Failed("no compose field found")
            is ActionArgs.AskUser -> ExecOutcome.Ok("asked user")
            is ActionArgs.Finish -> ExecOutcome.Ok(a.summary)
        }
    }

    /** Code-only send path — invoked solely from the confirmation FSM. */
    override suspend fun confirmSend(): ExecOutcome {
        val svc = serviceProvider() ?: return ExecOutcome.ServiceUnavailable
        val screen = inspector.dump()
        val sendEl = screen.elements.firstOrNull { e ->
            e.isClickable && SEND_LABELS.any { l ->
                e.label.equals(l, true) ||
                    e.contentDesc.equals(l, true) ||
                    e.viewId.endsWith("send_button")
            }
        } ?: return ExecOutcome.Failed("no send button on screen")
        val ok = svc.clickByIndex(sendEl.index)
        return if (ok) ExecOutcome.Ok("send tapped") else ExecOutcome.Failed("send tap failed")
    }

    /** Clear the compose field — used on deny/timeout/cancel. */
    override suspend fun clearDraft() {
        serviceProvider()?.clearText(fieldHint = "message")
    }

    fun openApp(query: String): ExecOutcome {
        val pm = context.packageManager
        // A literal package name skips the fuzzy match.
        if (query.contains('.')) {
            val intent = pm.getLaunchIntentForPackage(query)
                ?: return ExecOutcome.Failed("not installed")
            return launch(intent, query)
        }
        val q = query.lowercase()
        val matches = pm.getInstalledApplications(0).mapNotNull { info ->
            pm.getLaunchIntentForPackage(info.packageName)?.let {
                Triple(info.packageName, pm.getApplicationLabel(info).toString(), it)
            }
        }.filter { (_, label, _) ->
            val l = label.lowercase()
            l == q || l.contains(q) || q.contains(l)
        }
        if (matches.isEmpty()) return ExecOutcome.Failed("not installed")
        // Exact label > starts-with > contains; ties prefer the demo
        // messenger (DEC-02 default demo target).
        val best = matches.minWithOrNull(
            compareBy(
                { if (it.second.lowercase() == q) 0 else if (it.second.lowercase().startsWith(q)) 1 else 2 },
                { if (it.first == DEMO_PACKAGE) 0 else 1 },
            )
        )!!
        return launch(best.third, best.second)
    }

    private fun launch(intent: Intent, label: String): ExecOutcome {
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ExecOutcome.Ok("launched $label")
        } catch (e: Exception) {
            ExecOutcome.Failed("launch failed: ${e.message}")
        }
    }

    private fun readScreen(): ExecOutcome {
        val screen = inspector.dumpSync()
        if (screen.isEmpty) return ExecOutcome.Failed("screen is empty")

        // Message-shaped content first: a bubble view or the last
        // non-interactive text under a thread title.
        val bubbles = screen.elements.filter { it.viewId.endsWith("message_text") }
        val messageText = bubbles.lastOrNull()?.text
        val title = screen.elements.firstOrNull { it.viewId.endsWith("thread_title") }?.text
            ?: screen.elements.firstOrNull { it.className.endsWith("Toolbar") || it.viewId.endsWith("title") }?.text

        val prominent = screen.elements
            .filter { it.label.isNotBlank() }
            .filter { it.viewId.endsWith("message_text").not() }
            .take(5)
            .map { it.label.take(50) }

        return ExecOutcome.Read(
            speaker = title,
            messageText = messageText,
            items = prominent,
            appLabel = screen.foregroundPackage?.substringAfterLast('.') ?: "this app",
        )
    }

    companion object {
        const val DEMO_PACKAGE = "com.alalay.demomessenger"
        private val SEND_LABELS = setOf("send", "ipadala", "send message")
    }
}
