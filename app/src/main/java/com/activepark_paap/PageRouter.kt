package com.activepark_paap

/**
 * Pure page-routing logic — no Android dependencies.
 * Extracted from OverlayService for testability.
 */
object PageRouter {

    enum class Page { IDLE, EXIT_IDLE, TRANSACTION, EXIT_TRANSACTION, COMPLETED_EXIT, DEBUG }

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
        return if (role == "entry") {
            when {
                text1.contains("Welcome", ignoreCase = true) -> Page.IDLE
                else -> Page.TRANSACTION
            }
        } else {
            when {
                text1.contains("GoodBye", ignoreCase = true) -> Page.EXIT_IDLE
                text3.contains("Parking time", ignoreCase = true) -> Page.EXIT_TRANSACTION
                else -> Page.COMPLETED_EXIT
            }
        }
    }
}
