package com.alalay.app.agent

/**
 * Confirmation gate state machine (04-Architecture §6, FR-07).
 * SEND_EXEC is reachable only from CONFIRMED — the model has no send
 * primitive and no way to open this path (D-4).
 */
class ConfirmationGate {

    enum class State { NONE, PENDING, CONFIRMED, DENIED, EXPIRED, SEND_EXEC, SENT, FAIL }

    var state: State = State.NONE
        private set

    var pendingRecipient: String? = null
        private set
    var pendingText: String? = null
        private set

    /** Draft verified on screen → open the gate. */
    fun open(recipient: String, text: String) {
        state = State.PENDING
        pendingRecipient = recipient
        pendingText = text
    }

    /** Router heard yes/oo/sige. */
    fun confirm(): Boolean {
        if (state != State.PENDING) return false
        state = State.CONFIRMED
        return true
    }

    /** Router heard no/hindi, or "stop" (04-§6 PENDING→DENIED). */
    fun deny(): Boolean {
        if (state != State.PENDING) return false
        state = State.DENIED
        return true
    }

    /** 30 s silence expired. */
    fun expire(): Boolean {
        if (state != State.PENDING) return false
        state = State.EXPIRED
        return true
    }

    /** Code path only: CONFIRMED → SEND_EXEC. Returns false otherwise. */
    fun beginSend(): Boolean {
        if (state != State.CONFIRMED) return false
        state = State.SEND_EXEC
        return true
    }

    /** Post-send screen verification result. */
    fun completeSend(sentVerified: Boolean): Boolean {
        if (state != State.SEND_EXEC) return false
        state = if (sentVerified) State.SENT else State.FAIL
        return true
    }

    /** Draft cleared / spoke done → back to NONE. */
    fun reset() {
        state = State.NONE
        pendingRecipient = null
        pendingText = null
    }
}
