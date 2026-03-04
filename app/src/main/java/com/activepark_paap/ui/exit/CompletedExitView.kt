package com.activepark_paap.ui.exit

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.activepark_paap.R
import com.activepark_paap.ui.common.FontHelper

class CompletedExitView(context: Context) {

    val view: View = LayoutInflater.from(context)
        .inflate(R.layout.overlay_completed_exit, null)

    private val tvPlate: TextView = view.findViewById(R.id.tvPlate)
    private val tvTypeBadge: TextView = view.findViewById(R.id.tvTypeBadge)
    private val tvExitDate: TextView = view.findViewById(R.id.tvExitDate)
    private val tvThankYou: TextView = view.findViewById(R.id.tvThankYou)
    private val tvPaymentConfirmed: TextView = view.findViewById(R.id.tvPaymentConfirmed)

    init {
        FontHelper.applyFonts(context, view)
    }

    fun setPlate(text: String) {
        assert(text.isNotEmpty()) { "plate text empty" }
        tvPlate.text = text
        tvPlate.post {
            val parent = tvPlate.parent as View
            val maxWidth = parent.width - parent.paddingStart - parent.paddingEnd
            if (maxWidth > 0) FontHelper.autoFitTextSize(tvPlate, 120f, maxWidth)
        }
    }

    fun setTypeBadge(text: String) {
        assert(text.isNotEmpty()) { "badge text empty" }
        tvTypeBadge.text = text
    }

    fun setExitDate(text: String) {
        assert(text.isNotEmpty()) { "exit date empty" }
        tvExitDate.text = text
    }

    fun setPaymentConfirmed(subtitle: String) {
        assert(subtitle.isNotEmpty()) { "payment subtitle empty" }
        tvPaymentConfirmed.text = subtitle
        tvPaymentConfirmed.visibility = View.VISIBLE
    }

    fun resetThankYou() {
        tvThankYou.text = "THANK YOU"
        tvPaymentConfirmed.visibility = View.GONE
    }
}
