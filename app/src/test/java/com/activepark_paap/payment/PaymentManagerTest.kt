package com.activepark_paap.payment

import android.graphics.Bitmap
import com.activepark_paap.PageRouter.Page
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentManagerTest {

    private lateinit var testScope: TestScope
    private lateinit var fakeBitmap: Bitmap
    private lateinit var manager: PaymentManager

    private var initiateResult: ApiResult<InitiateResponse> = ApiResult.NetworkError(Exception("not set"))
    private var pollResult: ApiResult<StatusResponse> = ApiResult.NetworkError(Exception("not set"))
    private var cancelResult: ApiResult<Unit> = ApiResult.Success(Unit)
    private var cancelledCardNos = mutableListOf<String>()
    private var clockSeconds = 1000L

    private val fakeClient = object : PaymentApiClient("http://fake", "key") {
        override suspend fun initiate(cardNo: String): ApiResult<InitiateResponse> = initiateResult
        override suspend fun pollStatus(tranId: String): ApiResult<StatusResponse> = pollResult
        override suspend fun cancel(cardNo: String): ApiResult<Unit> {
            cancelledCardNos.add(cardNo)
            return cancelResult
        }
    }

    @Before
    fun setUp() {
        testScope = TestScope(StandardTestDispatcher())
        fakeBitmap = Mockito.mock(Bitmap::class.java)
        cancelledCardNos.clear()
        clockSeconds = 1000L
        manager = PaymentManager(
            apiClient = fakeClient,
            pollIntervalMs = 5000,
            scope = testScope,
            clock = { clockSeconds },
            decodeQr = { fakeBitmap }
        )
    }

    private fun successInitiate(tranId: String = "txn1", expiresIn: Long = 1000L) {
        initiateResult = ApiResult.Success(InitiateResponse(tranId, "base64data", "4.00", "USD", 0L, expiresIn))
    }

    private fun pendingPoll(tranId: String = "txn1") {
        pollResult = ApiResult.Success(StatusResponse("PENDING_TERMINAL_BOX", tranId, "4.00", "USD"))
    }

    private fun completedPoll(tranId: String = "txn1") {
        pollResult = ApiResult.Success(StatusResponse("COMPLETED_TERMINAL_BOX", tranId, "4.00", "USD"))
    }

    private fun preAuthHeldPoll(tranId: String = "txn1") {
        pollResult = ApiResult.Success(StatusResponse("PRE_AUTH_HELD_TERMINAL_BOX", tranId, "4.00", "USD"))
    }

    private fun cancelledPreAuthPoll(tranId: String = "txn1") {
        pollResult = ApiResult.Success(StatusResponse("CANCELLED_PRE_AUTH_TERMINAL_BOX", tranId, "4.00", "USD"))
    }

    private fun expiredPoll(tranId: String = "txn1") {
        pollResult = ApiResult.Success(StatusResponse("EXPIRED", tranId, "4.00", "USD"))
    }

    // --- startPayment ---

    @Test
    fun startPayment_success_emitsAwaitingPayment() = testScope.runTest {
        successInitiate()
        pendingPoll()

        manager.startPayment("CARD1")
        advanceTimeBy(1) // let initiate run

        val state = manager.state.value
        assertTrue("Expected AwaitingPayment, got $state", state is PaymentState.AwaitingPayment)
        assertEquals("txn1", (state as PaymentState.AwaitingPayment).tranId)
        manager.destroy()
    }

    @Test
    fun startPayment_success_computesLocalExpiry() = testScope.runTest {
        clockSeconds = 500L
        successInitiate(expiresIn = 900L)
        pendingPoll()

        manager.startPayment("CARD1")
        advanceTimeBy(1)

        val state = manager.state.value as PaymentState.AwaitingPayment
        assertEquals(1400L, state.expiresAtUnix) // 500 + 900
        manager.destroy()
    }

    @Test
    fun startPayment_success_passesCurrency() = testScope.runTest {
        successInitiate()
        pendingPoll()

        manager.startPayment("CARD1")
        advanceTimeBy(1)

        val state = manager.state.value as PaymentState.AwaitingPayment
        assertEquals("USD", state.currency)
        manager.destroy()
    }

    @Test
    fun pollExpires_whenClockExceedsLocalExpiry() = testScope.runTest {
        clockSeconds = 1000L
        successInitiate(expiresIn = 100L) // localExpiresAt = 1100
        pendingPoll()

        manager.startPayment("CARD1")
        advanceTimeBy(1)
        assertTrue(manager.state.value is PaymentState.AwaitingPayment)

        clockSeconds = 1101L // past expiry
        advanceTimeBy(5001)

        assertTrue(manager.state.value is PaymentState.Expired)
    }

    @Test
    fun startPayment_vehicleNotFound_emitsError() = testScope.runTest {
        initiateResult = ApiResult.HttpError(422, "VEHICLE_NOT_FOUND", "No InRecord")

        manager.startPayment("CARD1")
        advanceUntilIdle()

        val state = manager.state.value
        assertTrue(state is PaymentState.Error)
        assertEquals("Ticket not found", (state as PaymentState.Error).message)
    }

    @Test
    fun startPayment_alreadyPaid_emitsConfirmed() = testScope.runTest {
        initiateResult = ApiResult.HttpError(422, "ALREADY_PAID", "Already paid")

        manager.startPayment("CARD1")
        advanceUntilIdle()

        assertTrue(manager.state.value is PaymentState.Confirmed)
    }

    @Test
    fun startPayment_503_emitsError() = testScope.runTest {
        initiateResult = ApiResult.HttpError(503, "EDGE_API_ERROR", "Edge offline")

        manager.startPayment("CARD1")
        advanceUntilIdle()

        val state = manager.state.value
        assertTrue(state is PaymentState.Error)
        assertEquals("Payment unavailable, please pay at counter", (state as PaymentState.Error).message)
    }

    @Test
    fun startPayment_404_emitsFeatureUnavailable() = testScope.runTest {
        initiateResult = ApiResult.HttpError(404, "NOT_FOUND", "Feature disabled")

        manager.startPayment("CARD1")
        advanceUntilIdle()

        assertTrue(manager.state.value is PaymentState.FeatureUnavailable)
    }

    @Test
    fun startPayment_409noQr_emitsError() = testScope.runTest {
        initiateResult = ApiResult.HttpError(409, "PAYMENT_IN_PROGRESS_NO_QR", "No QR")

        manager.startPayment("CARD1")
        advanceUntilIdle()

        val state = manager.state.value
        assertTrue(state is PaymentState.Error)
        assertEquals("Payment in progress, QR unavailable", (state as PaymentState.Error).message)
    }

    @Test
    fun startPayment_networkError_emitsError() = testScope.runTest {
        initiateResult = ApiResult.NetworkError(Exception("timeout"))

        manager.startPayment("CARD1")
        advanceUntilIdle()

        val state = manager.state.value
        assertTrue(state is PaymentState.Error)
        assertEquals("Network error: timeout", (state as PaymentState.Error).message)
    }

    // --- poll ---

    @Test
    fun pollStatus_completed_emitsConfirmed() = testScope.runTest {
        successInitiate()
        pendingPoll()
        manager.startPayment("CARD1")
        advanceTimeBy(1)
        assertTrue(manager.state.value is PaymentState.AwaitingPayment)

        completedPoll()
        advanceTimeBy(5001)

        assertTrue(manager.state.value is PaymentState.Confirmed)
    }

    @Test
    fun pollStatus_expired_emitsExpired() = testScope.runTest {
        successInitiate()
        pendingPoll()
        manager.startPayment("CARD1")
        advanceTimeBy(1)

        expiredPoll()
        advanceTimeBy(5001)

        assertTrue(manager.state.value is PaymentState.Expired)
    }

    @Test
    fun pollStatus_preAuthHeld_emitsConfirmed() = testScope.runTest {
        successInitiate()
        pendingPoll()
        manager.startPayment("CARD1")
        advanceTimeBy(1)
        assertTrue(manager.state.value is PaymentState.AwaitingPayment)

        preAuthHeldPoll()
        advanceTimeBy(5001)

        assertTrue(manager.state.value is PaymentState.Confirmed)
    }

    @Test
    fun pollStatus_cancelledPreAuth_emitsError() = testScope.runTest {
        successInitiate()
        pendingPoll()
        manager.startPayment("CARD1")
        advanceTimeBy(1)
        assertTrue(manager.state.value is PaymentState.AwaitingPayment)

        cancelledPreAuthPoll()
        advanceTimeBy(5001)

        val state = manager.state.value
        assertTrue(state is PaymentState.Error)
        assertEquals("Payment released, try again", (state as PaymentState.Error).message)
    }

    // --- cancel ---

    @Test
    fun cancelPayment_stopsPolling_emitsIdle() = testScope.runTest {
        successInitiate()
        pendingPoll()
        manager.startPayment("CARD1")
        advanceTimeBy(1)
        assertTrue(manager.state.value is PaymentState.AwaitingPayment)

        manager.cancelPayment("CARD1")
        advanceUntilIdle()

        assertEquals(PaymentState.Idle, manager.state.value)
        assertTrue(cancelledCardNos.contains("CARD1"))
    }

    // --- exit state tracking ---

    @Test
    fun onPageChanged_exitIdleAfterTransaction_autoCancel() = testScope.runTest {
        successInitiate()
        pendingPoll()
        manager.startPayment("CARD1")
        advanceTimeBy(1)

        manager.onPageChanged(Page.EXIT_TRANSACTION)
        manager.onPageChanged(Page.EXIT_IDLE)
        advanceUntilIdle()

        assertEquals(PaymentState.Idle, manager.state.value)
        assertTrue(cancelledCardNos.contains("CARD1"))
    }

    // --- startPayment replaces existing ---

    @Test
    fun startPayment_replacesExisting_cancelsOldViaApi() = testScope.runTest {
        successInitiate(tranId = "txn1")
        pendingPoll()
        manager.startPayment("CARD1")
        advanceTimeBy(1)
        assertTrue(manager.state.value is PaymentState.AwaitingPayment)

        successInitiate(tranId = "txn2")
        manager.startPayment("CARD2")
        advanceTimeBy(1)

        assertEquals(listOf("CARD1"), cancelledCardNos)
        val state = manager.state.value
        assertTrue(state is PaymentState.AwaitingPayment)
        assertEquals("txn2", (state as PaymentState.AwaitingPayment).tranId)
        manager.destroy()
    }

    @Test
    fun startPayment_sameCard_noCancelCall() = testScope.runTest {
        successInitiate(tranId = "txn1")
        pendingPoll()
        manager.startPayment("CARD1")
        advanceTimeBy(1)

        successInitiate(tranId = "txn2")
        manager.startPayment("CARD1")
        advanceTimeBy(1)

        assertTrue(cancelledCardNos.isEmpty())
        manager.destroy()
    }

    // --- destroy ---

    @Test
    fun destroy_cancelsAll() = testScope.runTest {
        successInitiate()
        pendingPoll()
        manager.startPayment("CARD1")
        advanceTimeBy(1)

        manager.destroy()
        advanceTimeBy(10000)

        // State stays at AwaitingPayment (no further transitions after destroy)
        assertTrue(manager.state.value is PaymentState.AwaitingPayment)
    }

    // --- cached currency ---

    @Test
    fun rescan409_usesCachedCurrencyFromFirstInitiate() = testScope.runTest {
        // First initiate returns currency
        successInitiate(tranId = "txn1")
        pendingPoll()
        manager.startPayment("CARD1")
        advanceTimeBy(1)
        assertEquals("USD", (manager.state.value as PaymentState.AwaitingPayment).currency)

        // Simulate 409 rescan: same card, no currency
        initiateResult = ApiResult.Success(
            InitiateResponse("txn1b", "base64data", "", "", 0L, 500L)
        )
        manager.startPayment("CARD1")
        advanceTimeBy(1)

        val state = manager.state.value as PaymentState.AwaitingPayment
        assertEquals("USD", state.currency)
        manager.destroy()
    }

    @Test
    fun destroy_clearsCachedCurrency() = testScope.runTest {
        // First initiate caches currency
        successInitiate(tranId = "txn1")
        pendingPoll()
        manager.startPayment("CARD1")
        advanceTimeBy(1)
        assertEquals("USD", (manager.state.value as PaymentState.AwaitingPayment).currency)

        manager.destroy()

        // New payment with no currency — should be empty, not cached "USD"
        initiateResult = ApiResult.Success(
            InitiateResponse("txn2", "base64data", "", "", 0L, 500L)
        )
        manager.startPayment("CARD2")
        advanceTimeBy(1)

        val state = manager.state.value as PaymentState.AwaitingPayment
        assertEquals("", state.currency)
        manager.destroy()
    }
}
