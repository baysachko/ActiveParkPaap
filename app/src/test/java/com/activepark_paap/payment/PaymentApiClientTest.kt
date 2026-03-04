package com.activepark_paap.payment

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PaymentApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: PaymentApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = PaymentApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-api-key"
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- initiate ---

    @Test
    fun initiate_success_parsesResponse() = runBlocking {
        val body = JSONObject()
            .put("Success", true)
            .put("TranId", "txn123")
            .put("QrImage", "data:image/png;base64,abc")
            .put("Amount", "4.00")
            .put("Currency", "USD")
            .put("ExpiresAt", 1700000000L)
            .put("ExpiresIn", 900L)
        server.enqueue(MockResponse().setResponseCode(200).setBody(body.toString()))

        val result = client.initiate("CARD001")

        assertTrue(result is ApiResult.Success)
        val data = (result as ApiResult.Success).data
        assertEquals("txn123", data.tranId)
        assertEquals("data:image/png;base64,abc", data.qrImageBase64)
        assertEquals("4.00", data.amount)
        assertEquals(1700000000L, data.expiresAtUnix)
        assertEquals(900L, data.expiresInSeconds)
    }

    @Test
    fun initiate_sendsCorrectRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            JSONObject().put("TranId", "t1").put("QrImage", "").put("Amount", "").put("Currency", "").put("ExpiresAt", 0).put("ExpiresIn", 0).toString()
        ))
        client.initiate("ABC123")

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.endsWith("/api/v1/terminal-box/payment/initiate"))
        assertEquals("test-api-key", req.getHeader("X-API-Key"))
        val reqBody = JSONObject(req.body.readUtf8())
        assertEquals("ABC123", reqBody.getString("CardNo"))
    }

    @Test
    fun initiate_409_withQr_returnsSuccess() = runBlocking {
        val body = JSONObject()
            .put("Success", false)
            .put("Error", "PAYMENT_IN_PROGRESS")
            .put("ExistingTranId", "old123")
            .put("QrImage", "data:image/png;base64,cached")
            .put("ExpiresAt", 1700000000L)
        server.enqueue(MockResponse().setResponseCode(409).setBody(body.toString()))

        val result = client.initiate("CARD001")

        assertTrue(result is ApiResult.Success)
        val data = (result as ApiResult.Success).data
        assertEquals("old123", data.tranId)
        assertEquals("data:image/png;base64,cached", data.qrImageBase64)
        assertEquals(0L, data.expiresInSeconds)
    }

    @Test
    fun initiate_409_withExpiresIn_parsesIt() = runBlocking {
        val body = JSONObject()
            .put("ExistingTranId", "old456")
            .put("QrImage", "data:image/png;base64,qr")
            .put("Amount", "5.00")
            .put("Currency", "KHR")
            .put("ExpiresAt", 1700000000L)
            .put("ExpiresIn", 600L)
        server.enqueue(MockResponse().setResponseCode(409).setBody(body.toString()))

        val result = client.initiate("CARD002")

        assertTrue(result is ApiResult.Success)
        val data = (result as ApiResult.Success).data
        assertEquals(600L, data.expiresInSeconds)
        assertEquals("KHR", data.currency)
    }

    @Test
    fun initiate_409_noQr_returnsHttpError() = runBlocking {
        val body = JSONObject()
            .put("Error", "PAYMENT_IN_PROGRESS")
            .put("ExistingTranId", "old123")
        server.enqueue(MockResponse().setResponseCode(409).setBody(body.toString()))

        val result = client.initiate("CARD001")

        assertTrue(result is ApiResult.HttpError)
        assertEquals(409, (result as ApiResult.HttpError).code)
        assertEquals("PAYMENT_IN_PROGRESS_NO_QR", result.errorCode)
    }

    @Test
    fun initiate_422_vehicleNotFound() = runBlocking {
        val body = JSONObject()
            .put("Error", "VEHICLE_NOT_FOUND")
            .put("Message", "No InRecord for CardNo")
        server.enqueue(MockResponse().setResponseCode(422).setBody(body.toString()))

        val result = client.initiate("CARD001")

        assertTrue(result is ApiResult.HttpError)
        assertEquals(422, (result as ApiResult.HttpError).code)
        assertEquals("VEHICLE_NOT_FOUND", result.errorCode)
    }

    @Test
    fun initiate_422_alreadyPaid() = runBlocking {
        val body = JSONObject()
            .put("Error", "ALREADY_PAID")
            .put("Message", "Payment completed")
        server.enqueue(MockResponse().setResponseCode(422).setBody(body.toString()))

        val result = client.initiate("CARD001")

        assertTrue(result is ApiResult.HttpError)
        assertEquals("ALREADY_PAID", (result as ApiResult.HttpError).errorCode)
    }

    @Test
    fun initiate_503_edgeError() = runBlocking {
        val body = JSONObject()
            .put("Error", "EDGE_API_ERROR")
            .put("Message", "Edge offline")
        server.enqueue(MockResponse().setResponseCode(503).setBody(body.toString()))

        val result = client.initiate("CARD001")

        assertTrue(result is ApiResult.HttpError)
        assertEquals(503, (result as ApiResult.HttpError).code)
    }

    @Test
    fun initiate_networkError() = runBlocking {
        server.shutdown()
        val result = client.initiate("CARD001")
        assertTrue(result is ApiResult.NetworkError)
    }

    // --- pollStatus ---

    @Test
    fun pollStatus_pending() = runBlocking {
        val body = JSONObject()
            .put("Success", true)
            .put("TranId", "txn123")
            .put("Status", "PENDING_TERMINAL_BOX")
            .put("Amount", "4.00")
            .put("Currency", "USD")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body.toString()))

        val result = client.pollStatus("txn123")

        assertTrue(result is ApiResult.Success)
        assertEquals("PENDING_TERMINAL_BOX", (result as ApiResult.Success).data.status)
    }

    @Test
    fun pollStatus_completed() = runBlocking {
        val body = JSONObject()
            .put("TranId", "txn123")
            .put("Status", "COMPLETED_TERMINAL_BOX")
            .put("Amount", "4.00")
            .put("Currency", "USD")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body.toString()))

        val result = client.pollStatus("txn123")

        assertTrue(result is ApiResult.Success)
        assertEquals("COMPLETED_TERMINAL_BOX", (result as ApiResult.Success).data.status)
    }

    @Test
    fun pollStatus_sendsApiKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            JSONObject().put("TranId", "t").put("Status", "").put("Amount", "").put("Currency", "").toString()
        ))
        client.pollStatus("txn123")

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertTrue(req.path!!.contains("/api/v1/terminal-box/payment/status/txn123"))
        assertEquals("test-api-key", req.getHeader("X-API-Key"))
    }

    // --- cancel ---

    @Test
    fun cancel_success() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            JSONObject().put("Success", true).toString()
        ))

        val result = client.cancel("CARD001")

        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun cancel_404_noPending() = runBlocking {
        val body = JSONObject().put("Error", "NOT_FOUND").put("Message", "No pending payment")
        server.enqueue(MockResponse().setResponseCode(404).setBody(body.toString()))

        val result = client.cancel("CARD001")

        assertTrue(result is ApiResult.HttpError)
        assertEquals(404, (result as ApiResult.HttpError).code)
    }

    @Test
    fun cancel_sendsCorrectRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.cancel("ABC123")

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.endsWith("/api/v1/terminal-box/payment/cancel"))
        val reqBody = JSONObject(req.body.readUtf8())
        assertEquals("ABC123", reqBody.getString("CardNo"))
    }
}
