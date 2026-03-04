package com.activepark_paap.ui.exit

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.activepark_paap.R
import com.activepark_paap.ui.common.FontHelper

class ExitTransactionPaymentView(context: Context) {

    val view: View = LayoutInflater.from(context)
        .inflate(R.layout.overlay_exit_transaction_payment, null)

    private val tvPlate: TextView = view.findViewById(R.id.tvPlate)
    private val tvParkingTime: TextView = view.findViewById(R.id.tvParkingTime)
    private val tvPayAmount: TextView = view.findViewById(R.id.tvPayAmount)
    private val tvStatusLabel: TextView = view.findViewById(R.id.tvStatusLabel)
    private val tvStatusLine: View = view.findViewById(R.id.tvStatusLine)
    private val ivQrPayment: ImageView = view.findViewById(R.id.ivQrPayment)
    private val tvPayLabel: TextView = view.findViewById(R.id.tvPayLabel)
    private val tvPaymentStatus: TextView = view.findViewById(R.id.tvPaymentStatus)
    private val tvPaymentTimer: TextView = view.findViewById(R.id.tvPaymentTimer)
    private val tvHelpText: TextView = view.findViewById(R.id.tvHelpText)
    private val ivCheckmark: ImageView = view.findViewById(R.id.ivCheckmark)
    private val ivErrorIcon: ImageView = view.findViewById(R.id.ivErrorIcon)
    private val ivExpiredIcon: ImageView = view.findViewById(R.id.ivExpiredIcon)
    private val ivWarningIcon: ImageView = view.findViewById(R.id.ivWarningIcon)

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

    fun setParkingTime(text: String) {
        assert(text.isNotEmpty()) { "parking time empty" }
        tvParkingTime.text = text
    }

    fun setPayAmount(text: String) {
        assert(text.isNotEmpty()) { "pay amount empty" }
        tvPayAmount.text = text
    }

    fun setStatusLabel(text: String, color: Int) {
        assert(text.isNotEmpty()) { "status label empty" }
        tvStatusLabel.text = text
        tvStatusLabel.setTextColor(color)
        tvStatusLine.setBackgroundColor(color)
    }

    fun setQrBitmap(bitmap: Bitmap) {
        ivQrPayment.setImageBitmap(bitmap)
        ivQrPayment.visibility = View.VISIBLE
    }

    fun setPaymentStatus(text: String, color: Int) {
        tvPaymentStatus.text = text
        tvPaymentStatus.setTextColor(color)
    }

    fun setPaymentTimer(text: String) {
        tvPaymentTimer.text = text
    }

    fun setPayLabel(text: String) {
        assert(text.isNotEmpty()) { "pay label empty" }
        tvPayLabel.text = text
    }

    fun setPayAmountColor(color: Int) {
        tvPayAmount.setTextColor(color)
    }

    fun setPaymentTimerColor(color: Int) {
        tvPaymentTimer.setTextColor(color)
    }

    fun setHelpText(text: String, color: Int) {
        assert(text.isNotEmpty()) { "help text empty" }
        tvHelpText.text = text
        tvHelpText.setTextColor(color)
        tvHelpText.visibility = View.VISIBLE
    }

    private fun hideAllIcons() {
        ivQrPayment.visibility = View.GONE
        ivCheckmark.visibility = View.GONE
        ivErrorIcon.visibility = View.GONE
        ivExpiredIcon.visibility = View.GONE
        ivWarningIcon.visibility = View.GONE
    }

    fun showCheckmark() {
        hideAllIcons()
        ivCheckmark.visibility = View.VISIBLE
    }

    fun showErrorIcon() {
        hideAllIcons()
        ivErrorIcon.visibility = View.VISIBLE
    }

    fun showExpiredIcon() {
        hideAllIcons()
        ivExpiredIcon.visibility = View.VISIBLE
    }

    fun showWarningIcon() {
        hideAllIcons()
        ivWarningIcon.visibility = View.VISIBLE
    }

    fun clearPayment() {
        ivQrPayment.setImageBitmap(null)
        hideAllIcons()
        tvPaymentStatus.text = ""
        tvPaymentTimer.text = ""
        tvHelpText.visibility = View.GONE
        tvPayLabel.text = "PLEASE PAY"
        @Suppress("DEPRECATION")
        tvPayAmount.setTextColor(view.context.resources.getColor(R.color.accent_red))
        tvPaymentTimer.setTextColor(android.graphics.Color.parseColor("#888888"))
    }
}
