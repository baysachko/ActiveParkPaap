package com.activepark_paap.ui.common

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
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

    fun autoFitTextSize(tv: TextView, maxSizeSp: Float, maxWidthPx: Int) {
        assert(maxSizeSp > 10f) { "maxSizeSp must be > 10" }
        assert(maxWidthPx > 0) { "maxWidthPx must be > 0" }
        var size = maxSizeSp
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        while (tv.paint.measureText(tv.text.toString()) > maxWidthPx && size > 10f) {
            size -= 2f
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        }
    }
}
