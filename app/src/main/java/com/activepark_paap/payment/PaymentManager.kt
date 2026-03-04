package com.activepark_paap.payment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.activepark_paap.PageRouter.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PaymentManager(
    private val apiClient: PaymentApiClient,
    private val pollIntervalMs: Long,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val decodeQr: (String) -> Bitmap? = ::defaultDecodeQr
) {
    private val _state = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val state: StateFlow<PaymentState> = _state

    private var paymentJob: Job? = null
    private var currentCardNo: String? = null
    private var lastExitPage: Page? = null

    init {
        assert(pollIntervalMs > 0) { "pollIntervalMs must be positive" }
    }

    fun startPayment(cardNo: String) {
        assert(cardNo.isNotEmpty()) { "cardNo must not be empty" }
        paymentJob?.cancel()
        val oldCard = currentCardNo
        currentCardNo = cardNo
        paymentJob = scope.launch {
            if (oldCard != null && oldCard != cardNo) {
                apiClient.cancel(oldCard)
            }
            executePaymentFlow(cardNo)
        }
    }

    fun cancelPayment(cardNo: String) {
        assert(cardNo.isNotEmpty()) { "cardNo must not be empty" }
        paymentJob?.cancel()
        paymentJob = scope.launch {
            apiClient.cancel(cardNo)
            _state.value = PaymentState.Idle
        }
    }

    fun onPageChanged(page: Page) {
        when (page) {
            Page.EXIT_TRANSACTION -> lastExitPage = page
            Page.COMPLETED_EXIT -> {
                lastExitPage = null
            }
            Page.EXIT_IDLE -> {
                if (lastExitPage == Page.EXIT_TRANSACTION) {
                    val card = currentCardNo
                    if (card != null) {
                        cancelPayment(card)
                    }
                }
                lastExitPage = null
            }
            else -> { /* no-op */ }
        }
    }

    fun destroy() {
        paymentJob?.cancel()
        paymentJob = null
        currentCardNo = null
        lastExitPage = null
    }

    private suspend fun executePaymentFlow(cardNo: String) {
        _state.value = PaymentState.Initiating
        val result = apiClient.initiate(cardNo)
        when (result) {
            is ApiResult.Success -> handleInitiateSuccess(result.data)
            is ApiResult.HttpError -> _state.value = mapHttpError(result)
            is ApiResult.NetworkError -> {
                _state.value = PaymentState.Error("Network error: ${result.exception.message ?: "unknown"}")
            }
        }
    }

    private suspend fun handleInitiateSuccess(data: InitiateResponse) {
        val bitmap = decodeQr(data.qrImageBase64)
        if (bitmap == null) {
            _state.value = PaymentState.Error("Failed to decode QR code")
            return
        }
        val localExpiresAt = clock() + data.expiresInSeconds
        _state.value = PaymentState.AwaitingPayment(data.tranId, bitmap, localExpiresAt, data.currency)
        pollUntilResolved(data.tranId, localExpiresAt)
    }

    private suspend fun pollUntilResolved(tranId: String, expiresAtUnix: Long) {
        while (true) {
            delay(pollIntervalMs)
            if (clock() > expiresAtUnix) {
                _state.value = PaymentState.Expired
                return
            }
            val poll = apiClient.pollStatus(tranId)
            if (poll is ApiResult.Success) {
                when (poll.data.status) {
                    "COMPLETED_TERMINAL_BOX", "COMPLETED_VIA_FREE_VEHICLE_TERMINAL_BOX" -> {
                        _state.value = PaymentState.Confirmed(tranId)
                        return
                    }
                    "EXPIRED" -> {
                        _state.value = PaymentState.Expired
                        return
                    }
                }
            }
        }
    }

    private fun mapHttpError(err: ApiResult.HttpError): PaymentState = when {
        err.code == 422 && err.errorCode == "VEHICLE_NOT_FOUND" -> PaymentState.Error("Ticket not found")
        err.code == 422 && err.errorCode == "ALREADY_PAID" -> PaymentState.Confirmed(currentCardNo ?: "")
        err.code == 422 && err.errorCode == "FEE_CALCULATION_FAILED" -> PaymentState.Error("Fee calculation failed")
        err.code == 422 && err.errorCode == "DUPLICATE_PAYMENT" -> PaymentState.Error("Duplicate payment")
        err.code == 400 -> PaymentState.Error("Invalid ticket number")
        err.code == 403 -> PaymentState.Error("API key unauthorized")
        err.code == 503 -> PaymentState.Error("Payment unavailable, please pay at counter")
        err.code == 404 -> PaymentState.FeatureUnavailable
        err.code == 409 && err.errorCode == "PAYMENT_IN_PROGRESS_NO_QR" -> PaymentState.Error("Payment in progress, QR unavailable")
        else -> PaymentState.Error(err.message)
    }

    companion object {
        fun defaultDecodeQr(base64: String): Bitmap? {
            val cleaned = if (base64.contains(",")) base64.substringAfter(",") else base64
            if (cleaned.isEmpty()) return null
            val bytes = Base64.decode(cleaned, Base64.DEFAULT)
            assert(bytes.isNotEmpty()) { "Base64 decoded to empty bytes" }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
}
