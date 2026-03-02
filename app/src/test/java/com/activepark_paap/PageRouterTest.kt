package com.activepark_paap

import org.junit.Assert.*
import org.junit.Test

class PageRouterTest {

    // --- decidePage: entry role ---

    @Test
    fun `entry Welcome text → IDLE`() {
        assertEquals(PageRouter.Page.IDLE, PageRouter.decidePage("Welcome", "", "", "entry"))
    }

    @Test
    fun `entry welcome case-insensitive → IDLE`() {
        assertEquals(PageRouter.Page.IDLE, PageRouter.decidePage("welcome to park", "", "", "entry"))
    }

    @Test
    fun `entry plate number → TRANSACTION`() {
        assertEquals(PageRouter.Page.TRANSACTION, PageRouter.decidePage("CB12345", "Temporary", "2024-06-15", "entry"))
    }

    // --- decidePage: exit role ---

    @Test
    fun `exit GoodBye → EXIT_IDLE`() {
        assertEquals(PageRouter.Page.EXIT_IDLE, PageRouter.decidePage("GoodBye", "", "", "exit"))
    }

    @Test
    fun `exit goodbye case-insensitive → EXIT_IDLE`() {
        assertEquals(PageRouter.Page.EXIT_IDLE, PageRouter.decidePage("goodbye", "", "", "exit"))
    }

    @Test
    fun `exit with Parking time → EXIT_TRANSACTION`() {
        assertEquals(
            PageRouter.Page.EXIT_TRANSACTION,
            PageRouter.decidePage("CB12345", "Parking time: 2h 30m", "Amount: 50", "exit")
        )
    }

    @Test
    fun `exit other text → COMPLETED_EXIT`() {
        assertEquals(
            PageRouter.Page.COMPLETED_EXIT,
            PageRouter.decidePage("CB12345", "Sedan", "2024-06-15 14:00", "exit")
        )
    }

    // --- decidePage: edge cases ---

    @Test
    fun `empty text1 → null`() {
        assertNull(PageRouter.decidePage("", "foo", "bar", "entry"))
    }

    // --- initialPageForRole ---

    @Test
    fun `entry role → IDLE`() {
        assertEquals(PageRouter.Page.IDLE, PageRouter.initialPageForRole("entry"))
    }

    @Test
    fun `exit role → EXIT_IDLE`() {
        assertEquals(PageRouter.Page.EXIT_IDLE, PageRouter.initialPageForRole("exit"))
    }

    // --- isCallActive ---

    @Test
    fun `active call states return true`() {
        val activeStates = listOf(
            "CallIncomingReceived", "CallConnected", "CallStreamsRunning",
            "CallOutgoingInit", "CallOutgoingProgress", "CallOutgoingRinging"
        )
        for (state in activeStates) {
            assertTrue("$state should be active", PageRouter.isCallActive(state))
        }
    }

    @Test
    fun `inactive call states return false`() {
        assertFalse(PageRouter.isCallActive("CallIdle"))
        assertFalse(PageRouter.isCallActive("CallEnd"))
        assertFalse(PageRouter.isCallActive("CallReleased"))
    }

    // --- isPaapResumed ---

    @Test
    fun `dumpsys with PAAP package → true`() {
        val output = "  mResumedActivity: ActivityRecord{abc com.anziot.park/.MainActivity t42}"
        assertTrue(PageRouter.isPaapResumed(output))
    }

    @Test
    fun `dumpsys without PAAP → false`() {
        assertFalse(PageRouter.isPaapResumed("  mResumedActivity: ActivityRecord{abc com.other.app/.Main t42}"))
    }

    @Test
    fun `empty dumpsys → false`() {
        assertFalse(PageRouter.isPaapResumed(""))
    }
}
