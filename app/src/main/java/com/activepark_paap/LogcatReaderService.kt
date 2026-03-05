package com.activepark_paap

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

class LogcatReaderService : Service() {

    companion object {
        private const val TAG = "LogcatReader"
        private val _events = MutableSharedFlow<PaapEvent>(extraBufferCapacity = 64)
        val events: SharedFlow<PaapEvent> = _events
        var eventLog: EventLog? = null


        fun injectLine(line: String) {
            val event = PaapEventParser.parseLine(line)
            if (event != null) {
                _events.tryEmit(event)
                eventLog?.append(event)
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var process: Process? = null
    private val started = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started.compareAndSet(false, true)) {
            eventLog = EventLog.create(File(filesDir, "logs"))
            grantReadLogs()
            // Delay to let READ_LOGS permission propagate to this process
            android.os.Handler(mainLooper).postDelayed({ startReading() }, 1000)
        }
        return START_STICKY
    }

    private fun grantReadLogs() {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "pm grant com.activepark_paap android.permission.READ_LOGS"))
            proc.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "self-grant READ_LOGS failed", e)
        }
    }

    private fun startReading() {
        scope.launch {
            while (isActive) {
                try {
                    readLogcat()
                } catch (e: Exception) {
                    Log.e(TAG, "Logcat read error, restarting in 5s", e)
                }
                process?.destroy()
                process = null
                delay(5000)
            }
        }
    }

    private suspend fun readLogcat() {
        val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val cmd = arrayOf("logcat", "-s", "PRETTY_LOGGER:E", "anziot:I", "-T", ts)
        process = Runtime.getRuntime().exec(cmd)
        val proc = process!!

        // Drain stderr to prevent blocking
        scope.launch {
            try {
                val err = BufferedReader(InputStreamReader(proc.errorStream))
                while (err.readLine() != null) { /* discard */ }
            } catch (_: Exception) { }
        }

        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        try {
            var line: String?
            while (currentCoroutineContext().isActive) {
                line = reader.readLine() ?: break
                val event = PaapEventParser.parseLine(line) ?: continue
                _events.emit(event)
                eventLog?.append(event)
            }
        } finally {
            reader.close()
            proc.destroy()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        process?.destroy()
        process = null
        Log.i(TAG, "Service destroyed")
    }
}
