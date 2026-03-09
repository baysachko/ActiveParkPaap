package com.activepark_paap.payment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class HttpError(val code: Int, val errorCode: String, val message: String) : ApiResult<Nothing>()
    data class NetworkError(val exception: Exception) : ApiResult<Nothing>()
}

data class InitiateResponse(
    val tranId: String,
    val qrImageBase64: String,
    val amount: String,
    val currency: String,
    val expiresAtUnix: Long,
    val expiresInSeconds: Long
)

data class StatusResponse(
    val status: String,
    val tranId: String,
    val amount: String,
    val currency: String
)

open class PaymentApiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val client: OkHttpClient = buildDefaultClient()
) {
    init {
        assert(baseUrl.isNotEmpty()) { "baseUrl must not be empty" }
        assert(apiKey.isNotEmpty()) { "apiKey must not be empty" }
    }

    open suspend fun initiate(cardNo: String): ApiResult<InitiateResponse> = withContext(Dispatchers.IO) {
        assert(cardNo.isNotEmpty()) { "cardNo must not be empty" }
        val json = JSONObject().put("CardNo", cardNo)
        val body = RequestBody.create(JSON_TYPE, json.toString())
        val request = newRequest("$baseUrl/api/v1/terminal-box/payment/initiate")
            .post(body)
            .build()
        execute(request) { parseInitiateResponse(it) }
    }

    open suspend fun pollStatus(tranId: String): ApiResult<StatusResponse> = withContext(Dispatchers.IO) {
        assert(tranId.isNotEmpty()) { "tranId must not be empty" }
        val request = newRequest("$baseUrl/api/v1/terminal-box/payment/status/$tranId")
            .get()
            .build()
        execute(request) { parseStatusResponse(it) }
    }

    open suspend fun cancel(cardNo: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        assert(cardNo.isNotEmpty()) { "cardNo must not be empty" }
        val json = JSONObject().put("CardNo", cardNo)
        val body = RequestBody.create(JSON_TYPE, json.toString())
        val request = newRequest("$baseUrl/api/v1/terminal-box/payment/cancel")
            .post(body)
            .build()
        execute(request) { Unit }
    }

    private fun newRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .addHeader("X-API-Key", apiKey)
    }

    private fun <T> execute(request: Request, parse: (JSONObject) -> T): ApiResult<T> {
        return try {
            val response = client.newCall(request).execute()
            val bodyStr = response.body()?.string() ?: ""
            val json = if (bodyStr.isNotEmpty()) JSONObject(bodyStr) else JSONObject()

            when {
                response.isSuccessful -> ApiResult.Success(parse(json))
                response.code() == 409 -> parseConflictAsSuccess(json, parse)
                else -> parseHttpError(response.code(), json)
            }
        } catch (e: Exception) {
            ApiResult.NetworkError(e)
        }
    }

    private fun <T> parseConflictAsSuccess(json: JSONObject, parse: (JSONObject) -> T): ApiResult<T> {
        val qrImage = safeString(json, "QrImage")
        if (qrImage.isEmpty()) {
            return ApiResult.HttpError(409, "PAYMENT_IN_PROGRESS_NO_QR", "Cached QR unavailable")
        }
        val expiresIn = safeLong(json, "ExpiresIn")
        val retryAfter = safeLong(json, "RetryAfterSeconds")
        val effectiveExpiresIn = if (expiresIn > 0) expiresIn else retryAfter
        val mapped = JSONObject()
            .put("TranId", safeString(json, "ExistingTranId"))
            .put("QrImage", qrImage)
            .put("Amount", safeString(json, "Amount"))
            .put("Currency", safeString(json, "Currency"))
            .put("ExpiresAt", safeLong(json, "ExpiresAt"))
            .put("ExpiresIn", effectiveExpiresIn)
        return ApiResult.Success(parse(mapped))
    }

    private fun parseHttpError(code: Int, json: JSONObject): ApiResult.HttpError {
        return ApiResult.HttpError(
            code = code,
            errorCode = json.optString("Error", "UNKNOWN"),
            message = json.optString("Message", "Request failed ($code)")
        )
    }

    companion object {
        private val JSON_TYPE = MediaType.parse("application/json; charset=utf-8")

        /** API 22 optString returns literal "null" for JSON null — normalize to "" */
        fun safeString(json: JSONObject, key: String): String {
            if (json.isNull(key)) return ""
            val value = json.optString(key, "")
            return if (value == "null") "" else value
        }

        /** API 22 optLong returns 0 for JSON null — explicit null check */
        fun safeLong(json: JSONObject, key: String): Long {
            if (json.isNull(key)) return 0L
            return json.optLong(key, 0)
        }

        fun buildDefaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun parseInitiateResponse(json: JSONObject): InitiateResponse {
            val tranId = safeString(json, "TranId")
            assert(tranId.isNotEmpty()) { "TranId missing from response" }
            return InitiateResponse(
                tranId = tranId,
                qrImageBase64 = safeString(json, "QrImage"),
                amount = safeString(json, "Amount"),
                currency = safeString(json, "Currency"),
                expiresAtUnix = safeLong(json, "ExpiresAt"),
                expiresInSeconds = safeLong(json, "ExpiresIn")
            )
        }

        fun parseStatusResponse(json: JSONObject): StatusResponse {
            val tranId = safeString(json, "TranId")
            assert(tranId.isNotEmpty()) { "TranId missing from response" }
            return StatusResponse(
                status = safeString(json, "Status"),
                tranId = tranId,
                amount = safeString(json, "Amount"),
                currency = safeString(json, "Currency")
            )
        }
    }
}
