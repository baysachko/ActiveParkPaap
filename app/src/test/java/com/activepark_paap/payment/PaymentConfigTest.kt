package com.activepark_paap.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentConfigTest {

    @Test
    fun isReady_allSet_returnsTrue() {
        val config = PaymentConfig("https://api.example.com", "key123", 10000, enabled = true)
        assertTrue(config.isReady())
    }

    @Test
    fun isReady_disabled_returnsFalse() {
        val config = PaymentConfig("https://api.example.com", "key123", 10000, enabled = false)
        assertFalse(config.isReady())
    }

    @Test
    fun isReady_emptyUrl_returnsFalse() {
        val config = PaymentConfig("", "key123", 10000, enabled = true)
        assertFalse(config.isReady())
    }

    @Test
    fun isReady_emptyKey_returnsFalse() {
        val config = PaymentConfig("https://api.example.com", "", 10000, enabled = true)
        assertFalse(config.isReady())
    }

    @Test
    fun defaultPollInterval_is10s() {
        val config = PaymentConfig("url", "key", 10_000, enabled = true)
        assertEquals(10_000L, config.pollIntervalMs)
    }

    @Test
    fun dataClass_equality() {
        val a = PaymentConfig("url", "key", 5000, true)
        val b = PaymentConfig("url", "key", 5000, true)
        assertEquals(a, b)
    }
}
