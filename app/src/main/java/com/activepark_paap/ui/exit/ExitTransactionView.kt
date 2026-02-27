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

class ExitTransactionView(context: Context) {

    val view: View = LayoutInflater.from(context)
        .inflate(R.layout.overlay_exit_transaction, null)

    private val tvTime: TextView = view.findViewById(R.id.tvTime)
    private val tvDate: TextView = view.findViewById(R.id.tvDate)
    private val tvNetStatus: TextView = view.findViewById(R.id.tvNetStatus)
    private val tvPlate: TextView = view.findViewById(R.id.tvPlate)
    private val tvParkingTime: TextView = view.findViewById(R.id.tvParkingTime)
    private val tvPayAmount: TextView = view.findViewById(R.id.tvPayAmount)
    private val tvStatusLabel: TextView = view.findViewById(R.id.tvStatusLabel)
    private val tvStatusLine: View = view.findViewById(R.id.tvStatusLine)

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

    fun setParkingTime(text: String) {
        assert(text.isNotEmpty()) { "parking time empty" }
        tvParkingTime.text = text
    }

    fun setPayAmount(text: String) {
        assert(text.isNotEmpty()) { "pay amount empty" }
        tvPayAmount.text = text
    }

    fun setStatusLabel(text: String, color: Int) {
        assert(text.isNotEmpty()) { "status label empty" }
        tvStatusLabel.text = text
        tvStatusLabel.setTextColor(color)
        tvStatusLine.setBackgroundColor(color)
    }

    fun setNetworkStatus(connected: Boolean) {
        tvNetStatus.text = if (connected) "Connected" else "Disconnected"
    }
}
