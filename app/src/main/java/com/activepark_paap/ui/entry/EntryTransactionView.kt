package com.activepark_paap.ui.entry

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.activepark_paap.R
import com.activepark_paap.ui.common.FontHelper

class EntryTransactionView(context: Context) {

    val view: View = LayoutInflater.from(context)
        .inflate(R.layout.overlay_entry_transaction, null)

    private val tvPlate: TextView = view.findViewById(R.id.tvPlate)
    private val tvTypeBadge: TextView = view.findViewById(R.id.tvTypeBadge)
    private val tvEntryDate: TextView = view.findViewById(R.id.tvEntryDate)
    private val tvStatusLabel: TextView = view.findViewById(R.id.tvStatusLabel)

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

    fun setEntryDate(text: String) {
        assert(text.isNotEmpty()) { "entry date empty" }
        tvEntryDate.text = text
    }

    fun setStatusLabel(text: String, color: Int) {
        assert(text.isNotEmpty()) { "status label empty" }
        tvStatusLabel.text = text
        tvStatusLabel.setTextColor(color)
    }
}
