package com.activepark_paap.ui.entry

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.activepark_paap.R
import com.activepark_paap.ui.common.FontHelper

class EntryIdleView(context: Context) {

    val view: View = LayoutInflater.from(context)
        .inflate(R.layout.overlay_entry_idle, null)

    private val tvWelcome: TextView = view.findViewById(R.id.tvWelcome)

    init {
        FontHelper.applyFonts(context, view)
    }

    fun setMode(isExit: Boolean) {
        tvWelcome.text = if (isExit) "GoodBye" else "Welcome"
    }
}
