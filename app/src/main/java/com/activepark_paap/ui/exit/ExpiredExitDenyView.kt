package com.activepark_paap.ui.exit

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.activepark_paap.R
import com.activepark_paap.ui.common.FontHelper

class ExpiredExitDenyView(context: Context) {

    val view: View = LayoutInflater.from(context)
        .inflate(R.layout.overlay_expired_exit_deny, null)

    private val tvPlate: TextView = view.findViewById(R.id.tvPlate)
    private val tvTypeBadge: TextView = view.findViewById(R.id.tvTypeBadge)
    private val tvValidDate: TextView = view.findViewById(R.id.tvValidDate)

    init {
        FontHelper.applyFonts(context, view)
    }

    fun setPlate(text: String) {
        if (text.isEmpty()) { Log.e("ExpiredExitDenyView", "plate text empty"); return }
        tvPlate.text = text
        tvPlate.post {
            val parent = tvPlate.parent as View
            val maxWidth = parent.width - parent.paddingStart - parent.paddingEnd
            if (maxWidth > 0) FontHelper.autoFitTextSize(tvPlate, 120f, maxWidth)
        }
    }

    fun setTypeBadge(text: String) {
        if (text.isEmpty()) { Log.e("ExpiredExitDenyView", "badge text empty"); return }
        tvTypeBadge.text = text
    }

    fun setValidDate(text: String) {
        if (text.isEmpty()) { Log.e("ExpiredExitDenyView", "valid date empty"); return }
        tvValidDate.text = text
    }
}
