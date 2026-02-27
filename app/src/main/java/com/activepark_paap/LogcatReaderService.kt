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
            startReading()
        }
        return START_STICKY
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
        val cmd = arrayOf("logcat", "-s", "PRETTY_LOGGER:E", "-T", "1")
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
