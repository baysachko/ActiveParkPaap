package com.activepark_paap.ui.exit

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.activepark_paap.R
import com.activepark_paap.ui.common.FontHelper

class CashExitDenyView(context: Context) {

    val view: View = LayoutInflater.from(context)
        .inflate(R.layout.overlay_cash_exit_deny, null)

    private val tvPlate: TextView = view.findViewById(R.id.tvPlate)
    private val tvTypeBadge: TextView = view.findViewById(R.id.tvTypeBadge)
    private val tvBalance: TextView = view.findViewById(R.id.tvBalance)

    init {
        FontHelper.applyFonts(context, view)
    }

    fun setPlate(text: String) {
        if (text.isEmpty()) { Log.e("CashExitDenyView", "plate text empty"); return }
        tvPlate.text = text
        tvPlate.post {
            val parent = tvPlate.parent as View
            val maxWidth = parent.width - parent.paddingStart - parent.paddingEnd
            if (maxWidth > 0) FontHelper.autoFitTextSize(tvPlate, 120f, maxWidth)
        }
    }

    fun setTypeBadge(text: String) {
        if (text.isEmpty()) { Log.e("CashExitDenyView", "badge text empty"); return }
        tvTypeBadge.text = text
    }

    fun setBalance(text: String) {
        if (text.isEmpty()) { Log.e("CashExitDenyView", "balance empty"); return }
        tvBalance.text = text
    }
}
