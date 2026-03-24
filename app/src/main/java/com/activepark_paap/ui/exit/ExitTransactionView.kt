package com.activepark_paap.ui.exit

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.util.Log
import com.activepark_paap.R
import com.activepark_paap.ui.common.FontHelper

class ExitTransactionView(context: Context) {

    val view: View = LayoutInflater.from(context)
        .inflate(R.layout.overlay_exit_transaction, null)

    private val tvPlate: TextView = view.findViewById(R.id.tvPlate)
    private val tvParkingTime: TextView = view.findViewById(R.id.tvParkingTime)
    private val tvPayAmount: TextView = view.findViewById(R.id.tvPayAmount)
    private val tvStatusLabel: TextView = view.findViewById(R.id.tvStatusLabel)
    private val tvStatusLine: View = view.findViewById(R.id.tvStatusLine)

    init {
        FontHelper.applyFonts(context, view)
    }

    fun setPlate(text: String) {
        if (text.isEmpty()) { Log.e("ExitTransactionView", "plate text empty"); return }
        tvPlate.text = text
        tvPlate.post {
            val parent = tvPlate.parent as View
            val maxWidth = parent.width - parent.paddingStart - parent.paddingEnd
            if (maxWidth > 0) FontHelper.autoFitTextSize(tvPlate, 120f, maxWidth)
        }
    }

    fun setParkingTime(text: String) {
        if (text.isEmpty()) { Log.e("ExitTransactionView", "parking time empty"); return }
        tvParkingTime.text = text
    }

    fun setPayAmount(text: String) {
        if (text.isEmpty()) { Log.e("ExitTransactionView", "pay amount empty"); return }
        tvPayAmount.text = text
    }

    fun setStatusLabel(text: String, color: Int) {
        if (text.isEmpty()) { Log.e("ExitTransactionView", "status label empty"); return }
        tvStatusLabel.text = text
        tvStatusLabel.setTextColor(color)
        tvStatusLine.setBackgroundColor(color)
    }
}
