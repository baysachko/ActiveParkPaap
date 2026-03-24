package com.activepark_paap

/**
 * Pure page-routing logic — no Android dependencies.
 * Extracted from OverlayService for testability.
 */
object PageRouter {

    enum class Page {
        IDLE, EXIT_IDLE, TRANSACTION, EXIT_TRANSACTION, COMPLETED_EXIT,
        CASH_ENTRY, CASH_ENTRY_DENY, CASH_EXIT, CASH_EXIT_DENY,
        EXPIRED_ENTRY_DENY, EXPIRED_EXIT_DENY,
        DEBUG
    }

    private val ACTIVE_CALL_STATES = setOf(
        "CallIncomingReceived", "CallConnected", "CallStreamsRunning",
        "CallOutgoingInit", "CallOutgoingProgress", "CallOutgoingRinging"
    )

    fun isCallActive(toState: String): Boolean = toState in ACTIVE_CALL_STATES

    fun isPaapResumed(dumpsysOutput: String): Boolean =
        dumpsysOutput.contains("com.anziot.park")

    fun initialPageForRole(role: String): Page =
        if (role == "exit") Page.EXIT_IDLE else Page.IDLE

    /**
     * Decide which page to show based on display text fields and role.
     * Returns null if text1 is empty (no page change needed).
     */
    fun decidePage(text1: String, text3: String, text4: String, role: String): Page? {
        assert(role == "entry" || role == "exit") { "invalid role: $role" }
        if (text1.isEmpty()) return null
        val isExpired = text3.contains("is Expiry", ignoreCase = true)
        val isCashDeny = text3.contains("has little money", ignoreCase = true)
        val isCash = text3.equals("CASH", ignoreCase = true)
        return if (role == "entry") {
            when {
                text1.contains("Welcome", ignoreCase = true) -> Page.IDLE
                isExpired -> Page.EXPIRED_ENTRY_DENY
                isCashDeny -> Page.CASH_ENTRY_DENY
                isCash -> Page.CASH_ENTRY
                else -> Page.TRANSACTION
            }
        } else {
            when {
                text1.contains("GoodBye", ignoreCase = true) -> Page.EXIT_IDLE
                text3.contains("Parking time", ignoreCase = true) -> Page.EXIT_TRANSACTION
                isExpired -> Page.EXPIRED_EXIT_DENY
                isCashDeny -> Page.CASH_EXIT_DENY
                isCash -> Page.CASH_EXIT
                else -> Page.COMPLETED_EXIT
            }
        }
    }
}
