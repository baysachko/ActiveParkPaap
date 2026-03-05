package com.activepark_paap.ui.common

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.activepark_paap.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class OverlayBarHelper(private val root: View) {

    private val tvTime: TextView = root.findViewById(R.id.tvTime)
    private val tvDate: TextView = root.findViewById(R.id.tvDate)
    private val tvNetStatus: TextView = root.findViewById(R.id.tvNetStatus)
    private val phoneIndicator: View = root.findViewById(R.id.phoneIndicator)
    private val ivHandPress: ImageView = root.findViewById(R.id.ivHandPress)

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.ENGLISH)

    private var debugTapCount = 0
    private var lastTapTime = 0L
    var onDebugRequested: (() -> Unit)? = null

    init {
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

    fun setNetworkStatus(connected: Boolean) {
        tvNetStatus.text = if (connected) "Connected" else "Disconnected"
    }

    fun setCallActive(active: Boolean) {
        phoneIndicator.visibility = if (active) View.VISIBLE else View.GONE
    }

    fun setHandPress(pressed: Boolean) {
        ivHandPress.visibility = if (pressed) View.VISIBLE else View.GONE
    }
}
