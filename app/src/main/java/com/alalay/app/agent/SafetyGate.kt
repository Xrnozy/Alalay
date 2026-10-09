package com.alalay.app.agent

/**
 * SafetyGate (FR-12, FR-07; spec 07-§5–§7).
 * Plain deterministic Kotlin — the model proposes, code disposes (D-1).
 * validate() never throws; every failure path is a Verdict.
 */

enum class ForbiddenDomain(val spoken: String) {
    PAYMENTS("payments"),
    AUTHENTICATION("passwords and security codes"),
    SECURITY_SETTINGS("security settings"),
    ACCOUNT_DELETION("account deletion"),
    PUBLIC_POSTING("public posting"),
}

enum class RejectReason { PARSE, UNKNOWN_TOOL, FORBIDDEN, NO_TARGET, LOOP, POLICY }

sealed interface Verdict {
    data class Approved(val action: ActionV1) : Verdict

    /**
     * draft_reply: execute the typing, then the confirmation FSM must
     * complete before any send (07-§5.5, 04-§6).
     */
    data class NeedsConfirmation(val action: ActionV1) : Verdict

    data class Rejected(
        val reason: RejectReason,
        val domain: ForbiddenDomain? = null,
        val retryHint: String? = null,
    ) : Verdict

    /**
     * Ambiguity or low-confidence check. When [yesNo] the router listens
     * in AWAITING_CONFIRMATION mode; otherwise AWAITING_ANSWER with the
     * numbered candidate list.
     */
    data class AskUser(
        val question: String,
        val candidates: List<UiElement>,
        val yesNo: Boolean = false,
        val pendingAction: ActionV1? = null,
    ) : Verdict
}

object SafetyGate {

    private val paymentKeywords = listOf(
        "send money", "gcash", "g-cash", "paymaya", "payment", "padala",
        "magpadala", "transfer", "buy load", "buy a load", "buying load",
        "paload", "pa-load", "pasaload", "cash in", "cash-in", "cash out",
        "remit", "ibayad", "magbayad", "bayad",
    )
    private val paymentPackages = setOf(
        "com.globe.gcash.android", "com.globe.gcash.android.uat",
        "com.paymaya", "com.paymaya.maya", "com.unionbankph.online",
        "com.bdo.retailbanking", "com.bpi.mobile", "com.ing.banking.phl",
        "com.metrobank.mobile", "com.rcbc.mobile", "com.cimb.phl",
    )
    private val authKeywords = listOf(
        "password", "otp", "one-time", "one time", "pin", "verification code",
        "sign in", "signin", "log in", "login", "authenticate", "2fa",
        "security code", "mpin",
    )
    private val securitySettingsKeywords = listOf(
        "pin", "password", "lock screen", "device admin", "fingerprint",
        "face unlock", "biometric", "screen lock", "pattern",
    )
    private val accountDeletionKeywords = listOf(
        "delete account", "delete my account", "deactivate", "deactivate my",
        "remove my account", "close account", "delete profile",
    )
    private val publicPostingKeywords = listOf(
        "post to", "post on", "post a", "share publicly", "public post",
        "post this", "update my status", "post on facebook", "post sa",
        "i-post", "ipost",
    )

    private val settingsPackage = "com.android.settings"

    /** OTP/password shapes that a draft text must never carry (07-§2 draft_reply). */
    private val draftSecretPatterns = listOf(
        Regex("\\b\\d{6}\\b"),
        Regex("\\b(?:otp|one\\s?time|password|passcode|pin|mpin|verification code)\\b", RegexOption.IGNORE_CASE),
    )

    private fun normalize(s: String) = " ${s.lowercase().replace(Regex("\\s+"), " ").trim()} "

    /** Keywords are space-padded so "pin" never fires inside "spin". */
    private fun matchesAny(haystack: String, needles: List<String>): Boolean =
        needles.any { haystack.contains(" ${it.trim()} ") }

    /**
     * Utterance-level blocklist check (FR-12, AT-11). Runs on the raw
     * transcript before routing result is executed, so forbidden asks are
     * refused before any navigation happens.
     */
    fun checkTask(utterance: String): ForbiddenDomain? {
        val t = normalize(utterance)
        return when {
            matchesAny(t, accountDeletionKeywords) -> ForbiddenDomain.ACCOUNT_DELETION
            matchesAny(t, paymentKeywords) -> ForbiddenDomain.PAYMENTS
            matchesAny(t, authKeywords) -> ForbiddenDomain.AUTHENTICATION
            matchesAny(t, publicPostingKeywords) -> ForbiddenDomain.PUBLIC_POSTING
            t.contains(" settings") && matchesAny(t, securitySettingsKeywords) ->
                ForbiddenDomain.SECURITY_SETTINGS
            else -> null
        }
    }

    /**
     * Validate a parsed action against the current screen (07-§5, in order).
     */
    fun validate(
        action: ActionV1,
        screen: ScreenModel,
        language: String = "en",
    ): Verdict {
        // 2. Allowlist — parser already guarantees, kept as defense in depth.
        if (action.tool !in Tool.entries) {
            return Verdict.Rejected(RejectReason.UNKNOWN_TOOL)
        }

        // 3. Forbidden-domain screen/target check.
        domainViolation(action, screen)?.let {
            return Verdict.Rejected(RejectReason.FORBIDDEN, domain = it)
        }

        // 4. Target verification for clicks.
        if (action.tool == Tool.CLICK) {
            val a = action.args as ActionArgs.Click
            when {
                a.target != null -> {
                    val matches = screen.distinctTargets(a.target)
                    when {
                        matches.isEmpty() -> return Verdict.Rejected(
                            RejectReason.NO_TARGET,
                            retryHint = "no element matches \"${a.target}\" on the current screen",
                        )
                        matches.size > 1 -> return Verdict.AskUser(
                            question = TemplateRenderer.render(
                                Tpl.AMBIGUITY, language,
                                "n" to matches.size.toString(),
                                "name" to a.target,
                                "list" to numberedList(matches.take(ActionContract.MAX_OPTIONS)),
                            ),
                            candidates = matches.take(ActionContract.MAX_OPTIONS),
                            pendingAction = action,
                        )
                    }
                }
                a.x != null && a.y != null -> {
                    if (!screen.display.contains(a.x, a.y)) {
                        return Verdict.Rejected(
                            RejectReason.NO_TARGET,
                            retryHint = "coordinates (${a.x},${a.y}) are outside the display",
                        )
                    }
                }
            }
        }

        // 5. Risk gate.
        if (action.tool == Tool.DRAFT_REPLY) {
            val text = (action.args as ActionArgs.DraftReply).text
            if (draftSecretPatterns.any { it.containsMatchIn(text) }) {
                return Verdict.Rejected(RejectReason.FORBIDDEN, domain = ForbiddenDomain.AUTHENTICATION)
            }
            return Verdict.NeedsConfirmation(action)
        }

        // 7. Low confidence on navigation actions → confirm with user (07-§7).
        if (action.confidence < 0.4 &&
            (action.tool == Tool.CLICK || action.tool == Tool.OPEN_APP)
        ) {
            val target = when (val a = action.args) {
                is ActionArgs.Click -> a.target ?: "(${a.x}, ${a.y})"
                is ActionArgs.OpenApp -> a.app
                else -> "that"
            }
            return Verdict.AskUser(
                question = TemplateRenderer.render(Tpl.LOW_CONFIDENCE, language, "target" to target),
                candidates = emptyList(),
                yesNo = true,
                pendingAction = action,
            )
        }

        return Verdict.Approved(action)
    }

    /**
     * Screen-domain blocklist (07-§5.3): foreground app package plus the
     * action's own text arguments. Screen content itself is not scanned —
     * that would block legitimate reads; the task check covers intent.
     */
    private fun domainViolation(action: ActionV1, screen: ScreenModel): ForbiddenDomain? {
        val pkg = screen.foregroundPackage.orEmpty().lowercase()
        if (pkg in paymentPackages) return ForbiddenDomain.PAYMENTS
        if (pkg == settingsPackage) {
            // Only security/admin sub-screens are forbidden; the blocklist
            // keyword check on the action args decides when relevant.
            val argText = actionArgText(action)
            if (argText != null && matchesAny(normalize(argText), securitySettingsKeywords)) {
                return ForbiddenDomain.SECURITY_SETTINGS
            }
        }
        val argText = actionArgText(action)?.let(::normalize)
        if (argText != null) {
            if (matchesAny(argText, authKeywords)) return ForbiddenDomain.AUTHENTICATION
            if (matchesAny(argText, paymentKeywords)) return ForbiddenDomain.PAYMENTS
            if (matchesAny(argText, accountDeletionKeywords)) return ForbiddenDomain.ACCOUNT_DELETION
            if (matchesAny(argText, publicPostingKeywords)) return ForbiddenDomain.PUBLIC_POSTING
        }
        return null
    }

    private fun actionArgText(action: ActionV1): String? = when (val a = action.args) {
        is ActionArgs.OpenApp -> a.app
        is ActionArgs.Click -> a.target
        is ActionArgs.DraftReply -> a.text
        is ActionArgs.AskUser -> a.question + " " + a.options.joinToString(" ")
        is ActionArgs.Finish -> a.summary
        else -> null
    }

    private fun numberedList(elements: List<UiElement>): String =
        elements.mapIndexed { i, e ->
            val ord = listOf("one", "two", "three", "four").getOrElse(i) { "${i + 1}" }
            "$ord, ${e.label.take(ActionContract.MAX_OPTION_LEN)}"
        }.joinToString("; ")
}
