package com.activepark_paap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RESTART = "com.activepark_paap.ACTION_RESTART"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.e("BootReceiver", "onReceive action=${intent.action}")
        context.startService(Intent(context, LogcatReaderService::class.java))
        context.startService(Intent(context, OverlayService::class.java))
    }
}
