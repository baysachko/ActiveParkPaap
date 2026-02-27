package com.activepark_paap.ui.exit

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.activepark_paap.R
import com.activepark_paap.ui.common.FontHelper
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class CompletedExitView(context: Context) {

    val view: View = LayoutInflater.from(context)
        .inflate(R.layout.overlay_completed_exit, null)

    private val tvTime: TextView = view.findViewById(R.id.tvTime)
    private val tvDate: TextView = view.findViewById(R.id.tvDate)
    private val tvNetStatus: TextView = view.findViewById(R.id.tvNetStatus)
    private val tvPlate: TextView = view.findViewById(R.id.tvPlate)
    private val tvTypeBadge: TextView = view.findViewById(R.id.tvTypeBadge)
    private val tvExitDate: TextView = view.findViewById(R.id.tvExitDate)

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.ENGLISH)

    private var debugTapCount = 0
    private var lastTapTime = 0L
    var onDebugRequested: (() -> Unit)? = null

    init {
        FontHelper.applyFonts(context, view)
        setupDebugTap()
    }

    private fun setupDebugTap() {
        tvNetStatus.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime > 2000) {
                debugTapCount = 0
            }
            lastTapTime = now
            debugTapCount++
            if (debugTapCount >= 6) {
                debugTapCount = 0
                onDebugRequested?.invoke()
            }
        }
    }

    fun startClock(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                val now = Date()
                tvTime.text = timeFormat.format(now)
                tvDate.text = dateFormat.format(now)
                delay(1000)
            }
        }
    }

    fun setPlate(text: String) {
        assert(text.isNotEmpty()) { "plate text empty" }
        tvPlate.text = text
    }

    fun setTypeBadge(text: String) {
        assert(text.isNotEmpty()) { "badge text empty" }
        tvTypeBadge.text = text
    }

    fun setExitDate(text: String) {
        assert(text.isNotEmpty()) { "exit date empty" }
        tvExitDate.text = text
    }

    fun setNetworkStatus(connected: Boolean) {
        tvNetStatus.text = if (connected) "Connected" else "Disconnected"
    }
}
