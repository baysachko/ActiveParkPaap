package com.activepark_paap.ui.entry

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.activepark_paap.R
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class EntryIdleView(context: Context) {

    val view: View = LayoutInflater.from(context)
        .inflate(R.layout.overlay_entry_idle, null)

    private val tvTime: TextView = view.findViewById(R.id.tvTime)
    private val tvDate: TextView = view.findViewById(R.id.tvDate)
    private val tvNetStatus: TextView = view.findViewById(R.id.tvNetStatus)

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.ENGLISH)

    private var debugTapCount = 0
    private var lastTapTime = 0L
    var onDebugRequested: (() -> Unit)? = null

    init {
        applyFonts(context)
        setupDebugTap()
    }

    private fun applyFonts(context: Context) {
        val regular = ResourcesCompat.getFont(context, R.font.space_grotesk_regular)
        val bold = ResourcesCompat.getFont(context, R.font.space_grotesk_bold)
        assert(regular != null) { "space_grotesk_regular font not found" }
        assert(bold != null) { "space_grotesk_bold font not found" }
        applyFontToViewTree(view, regular!!, bold!!)
    }

    private fun applyFontToViewTree(v: View, regular: Typeface, bold: Typeface) {
        if (v is TextView) {
            val wantsBold = v.tag == "bold"
            v.typeface = if (wantsBold) bold else regular
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                applyFontToViewTree(v.getChildAt(i), regular, bold)
            }
        }
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
}
