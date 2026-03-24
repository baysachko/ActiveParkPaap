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

    // --- decidePage: cash entry ---

    @Test
    fun `entry CASH type → CASH_ENTRY`() {
        assertEquals(
            PageRouter.Page.CASH_ENTRY,
            PageRouter.decidePage("CB12345", "CASH", "10000", "entry")
        )
    }

    @Test
    fun `entry cash case-insensitive → CASH_ENTRY`() {
        assertEquals(
            PageRouter.Page.CASH_ENTRY,
            PageRouter.decidePage("CB12345", "Cash", "500", "entry")
        )
    }

    @Test
    fun `entry has little money → CASH_ENTRY_DENY`() {
        assertEquals(
            PageRouter.Page.CASH_ENTRY_DENY,
            PageRouter.decidePage("CB12345", "Vehicle No. has little money.", "0", "entry")
        )
    }

    // --- decidePage: cash exit ---

    @Test
    fun `exit CASH type → CASH_EXIT`() {
        assertEquals(
            PageRouter.Page.CASH_EXIT,
            PageRouter.decidePage("CB12345", "CASH", "9800", "exit")
        )
    }

    @Test
    fun `exit has little money → CASH_EXIT_DENY`() {
        assertEquals(
            PageRouter.Page.CASH_EXIT_DENY,
            PageRouter.decidePage("CB12345", "Vehicle No. has little money.", "50", "exit")
        )
    }

    // --- decidePage: expired VIP/Season ---

    @Test
    fun `entry is Expiry → EXPIRED_ENTRY_DENY`() {
        assertEquals(
            PageRouter.Page.EXPIRED_ENTRY_DENY,
            PageRouter.decidePage("CB12345", "Vehicle No. is Expiry.", "04/23/2025", "entry")
        )
    }

    @Test
    fun `exit is Expiry → EXPIRED_EXIT_DENY`() {
        assertEquals(
            PageRouter.Page.EXPIRED_EXIT_DENY,
            PageRouter.decidePage("CB12345", "Vehicle No. is Expiry.", "04/23/2025", "exit")
        )
    }

    // --- decidePage: VIP/Season success → existing pages ---

    @Test
    fun `entry VIP → TRANSACTION (not cash)`() {
        assertEquals(
            PageRouter.Page.TRANSACTION,
            PageRouter.decidePage("CB12345", "VIP", "04/23/2025", "entry")
        )
    }

    @Test
    fun `entry Season → TRANSACTION (not cash)`() {
        assertEquals(
            PageRouter.Page.TRANSACTION,
            PageRouter.decidePage("CB12345", "Season", "04/23/2025", "entry")
        )
    }

    @Test
    fun `exit VIP → COMPLETED_EXIT (not cash)`() {
        assertEquals(
            PageRouter.Page.COMPLETED_EXIT,
            PageRouter.decidePage("CB12345", "VIP", "04/23/2025", "exit")
        )
    }

    @Test
    fun `exit Season → COMPLETED_EXIT (not cash)`() {
        assertEquals(
            PageRouter.Page.COMPLETED_EXIT,
            PageRouter.decidePage("CB12345", "Season", "04/23/2025", "exit")
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
