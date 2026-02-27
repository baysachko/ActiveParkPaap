package com.activepark_paap.ui.common

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.activepark_paap.R

object FontHelper {

    fun applyFonts(context: Context, root: View) {
        val regular = ResourcesCompat.getFont(context, R.font.space_grotesk_regular)
        val bold = ResourcesCompat.getFont(context, R.font.space_grotesk_bold)
        assert(regular != null) { "space_grotesk_regular font not found" }
        assert(bold != null) { "space_grotesk_bold font not found" }
        applyFontToViewTree(root, regular!!, bold!!)
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
}
