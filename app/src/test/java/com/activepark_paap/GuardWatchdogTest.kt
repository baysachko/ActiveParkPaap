package com.activepark_paap

import org.junit.Assert.*
import org.junit.Test

class GuardWatchdogTest {

    private fun watchdog(
        services: Set<String> = setOf("SvcA", "SvcB"),
        alive: () -> Set<String> = { emptySet() },
        restarted: MutableList<String> = mutableListOf(),
        delayMs: Long = 8000L,
        clock: () -> Long = { 0L }
    ): Triple<GuardWatchdog, MutableList<String>, () -> Long> {
        val wd = GuardWatchdog(services, alive, { restarted.add(it) }, delayMs, clock)
        return Triple(wd, restarted, clock)
    }

    @Test
    fun `kill sets killed flag, resume clears it`() {
        val (wd, _, _) = watchdog()
        assertFalse(wd.killed)
        wd.handleCommand(isKill = true)
        assertTrue(wd.killed)
        wd.handleCommand(isKill = false)
        assertFalse(wd.killed)
    }

    @Test
    fun `check does nothing while killed`() {
        val restarted = mutableListOf<String>()
        var now = 0L
        val wd = GuardWatchdog(setOf("Svc"), { emptySet() }, { restarted.add(it) }, 0L) { now }
        wd.killed = true
        now = 100_000L
        wd.check()
        assertTrue(restarted.isEmpty())
    }

    @Test
    fun `no restart before debounce elapsed`() {
        val restarted = mutableListOf<String>()
        var now = 1000L
        val wd = GuardWatchdog(setOf("Svc"), { emptySet() }, { restarted.add(it) }, 8000L) { now }

        wd.check() // first seen dead at 1000
        now = 8999L
        wd.check() // 7999ms elapsed, not enough
        assertTrue(restarted.isEmpty())
    }

    @Test
    fun `restart after debounce elapsed`() {
        val restarted = mutableListOf<String>()
        var now = 1000L
        val wd = GuardWatchdog(setOf("Svc"), { emptySet() }, { restarted.add(it) }, 8000L) { now }

        wd.check() // dead at 1000
        now = 9000L
        wd.check() // 8000ms elapsed
        assertEquals(listOf("Svc"), restarted)
    }

    @Test
    fun `service alive clears dead timer`() {
        val restarted = mutableListOf<String>()
        var now = 0L
        var alive = emptySet<String>()
        val wd = GuardWatchdog(setOf("Svc"), { alive }, { restarted.add(it) }, 8000L) { now }

        wd.check() // dead at 0
        now = 5000L
        alive = setOf("Svc")
        wd.check() // alive — clears timer
        now = 10000L
        alive = emptySet()
        wd.check() // dead again at 10000
        now = 17999L
        wd.check() // 7999ms, not enough
        assertTrue(restarted.isEmpty())
        now = 18000L
        wd.check()
        assertEquals(listOf("Svc"), restarted)
    }

    @Test
    fun `multiple services tracked independently`() {
        val restarted = mutableListOf<String>()
        var now = 0L
        val wd = GuardWatchdog(setOf("A", "B"), { setOf("B") }, { restarted.add(it) }, 100L) { now }

        wd.check() // A dead at 0, B alive
        now = 100L
        wd.check() // A restarted at 100
        assertEquals(listOf("A"), restarted)
    }

    @Test
    fun `handleCommand returns true on kill, false on resume`() {
        val (wd, _, _) = watchdog()
        assertTrue(wd.handleCommand(isKill = true))
        assertFalse(wd.handleCommand(isKill = false))
    }
}
