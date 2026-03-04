package com.activepark_paap.payment

import android.graphics.Bitmap

sealed class PaymentState {
    object Idle : PaymentState()
    object Initiating : PaymentState()

    data class AwaitingPayment(
        val tranId: String,
        val qrBitmap: Bitmap,
        val expiresAtUnix: Long,
        val currency: String
    ) : PaymentState()

    data class Confirmed(val tranId: String) : PaymentState()
    object Expired : PaymentState()
    data class Error(val message: String) : PaymentState()
    object NotConfigured : PaymentState()
    object FeatureUnavailable : PaymentState()
}
