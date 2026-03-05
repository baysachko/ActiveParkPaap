package com.activepark_paap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RESTART = "com.activepark_paap.ACTION_RESTART"
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.e(TAG, "onReceive action=${intent.action}")
        // Clear guard pause flag on boot
        @Suppress("DEPRECATION") context.getSharedPreferences("guard_state", Context.MODE_MULTI_PROCESS)
            .edit().putBoolean("paused", false).commit()

        context.startService(Intent(context, LogcatReaderService::class.java))
        context.startService(Intent(context, OverlayService::class.java))
        context.startService(Intent(context, GuardService::class.java))
    }
}
