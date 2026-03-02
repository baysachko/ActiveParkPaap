package com.activepark_paap

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*

class GuardService : Service() {

    companion object {
        const val ACTION_PAUSE = "com.activepark_paap.GUARD_PAUSE"
        private const val CHECK_INTERVAL_MS = 8000L
    }

    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    lateinit var watchdog: GuardWatchdog

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        Log.e("GuardService", "started")
        watchdog = GuardWatchdog(
            watchedServices = setOf(
                OverlayService::class.java.name,
                LogcatReaderService::class.java.name
            ),
            serviceChecker = {
                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                am.getRunningServices(100).map { it.service.className }.toSet()
            },
            restarter = { className ->
                Log.e("GuardService", "$className dead, restarting")
                startService(Intent(this, Class.forName(className)))
            }
        )
        scope.launch { watchLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        watchdog.handleCommand(isKill = intent?.action == ACTION_PAUSE)
        if (!watchdog.killed && !scope.isActive) {
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            scope.launch { watchLoop() }
        }
        return START_STICKY
    }

    private suspend fun watchLoop() {
        while (scope.isActive) {
            delay(CHECK_INTERVAL_MS)
            watchdog.check()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        // Always self-restart — guardian should never stay dead
        val intent = Intent(this, BootReceiver::class.java).apply {
            action = BootReceiver.ACTION_RESTART
        }
        val pending = PendingIntent.getBroadcast(this, 1, intent, 0)
        val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5000, pending)
        Log.e("GuardService", "self-restart scheduled in 5s")
    }
}
