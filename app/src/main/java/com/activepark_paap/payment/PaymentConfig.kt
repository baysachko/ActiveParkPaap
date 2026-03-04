package com.activepark_paap.payment

import android.content.Context

data class PaymentConfig(
    val baseUrl: String,
    val apiKey: String,
    val pollIntervalMs: Long,
    val enabled: Boolean
) {
    fun isReady(): Boolean = enabled && baseUrl.isNotEmpty() && apiKey.isNotEmpty()

    companion object {
        private const val PREFS_NAME = "payment_config"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_POLL_INTERVAL = "poll_interval_ms"
        private const val KEY_ENABLED = "enabled"
        private const val DEFAULT_POLL_MS = 10_000L

        fun load(context: Context): PaymentConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return PaymentConfig(
                baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
                apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
                pollIntervalMs = prefs.getLong(KEY_POLL_INTERVAL, DEFAULT_POLL_MS),
                enabled = prefs.getBoolean(KEY_ENABLED, false)
            )
        }

        fun save(context: Context, config: PaymentConfig) {
            assert(config.pollIntervalMs > 0) { "pollIntervalMs must be positive" }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_BASE_URL, config.baseUrl)
                .putString(KEY_API_KEY, config.apiKey)
                .putLong(KEY_POLL_INTERVAL, config.pollIntervalMs)
                .putBoolean(KEY_ENABLED, config.enabled)
                .commit()
        }
    }
}
