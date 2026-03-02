package com.activepark_paap

/**
 * Pure logic for the guardian watchdog — no Android dependencies.
 * [serviceChecker] returns set of alive service class names.
 * [restarter] called with class name of service to restart.
 */
class GuardWatchdog(
    private val watchedServices: Set<String>,
    private val serviceChecker: () -> Set<String>,
    private val restarter: (String) -> Unit,
    private val restartDelayMs: Long = 8000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Volatile var killed = false
    private val firstDeadAt = mutableMapOf<String, Long>()

    fun handleCommand(isKill: Boolean): Boolean {
        assert(watchedServices.isNotEmpty()) { "watchedServices must not be empty" }
        if (isKill) {
            killed = true
            return true
        }
        killed = false
        return false
    }

    fun check() {
        assert(watchedServices.isNotEmpty()) { "watchedServices must not be empty" }
        if (killed) return
        val alive = serviceChecker()
        for (svc in watchedServices) {
            if (svc in alive) {
                firstDeadAt.remove(svc)
            } else {
                val now = clock()
                val deadSince = firstDeadAt.getOrPut(svc) { now }
                assert(now >= deadSince) { "clock went backwards" }
                if (now - deadSince >= restartDelayMs) {
                    firstDeadAt.remove(svc)
                    restarter(svc)
                }
            }
        }
    }

}
