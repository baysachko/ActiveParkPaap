package com.activepark_paap

import org.junit.Assert.*
import org.junit.Test

class PaapEventTest {

    @Test
    fun `GateOpen summary`() {
        val e = PaapEvent.GateOpen(500, 1, PaapEvent.Direction.INBOUND)
        assertEquals("GATE OPEN (delay=500ms, io=1)", e.summary())
    }

    @Test
    fun `Speak summary`() {
        val e = PaapEvent.Speak("Hello", "en", "1.0", PaapEvent.Direction.INBOUND)
        assertEquals("TTS: \"Hello\" [en]", e.summary())
    }

    @Test
    fun `PrintTicket summary`() {
        val e = PaapEvent.PrintTicket("T", "C", "QR", PaapEvent.Direction.INBOUND)
        assertEquals("PRINT: T | QR=QR", e.summary())
    }

    @Test
    fun `DisplayUpdate summary`() {
        val e = PaapEvent.DisplayUpdate(
            mapOf("text1" to PaapEvent.DisplayField("Hi", "#FFF", 30, "CENTER")),
            PaapEvent.Direction.INBOUND
        )
        assertTrue(e.summary().contains("text1=Hi"))
    }

    @Test
    fun `VehicleSensing summary`() {
        val e = PaapEvent.VehicleSensing("car comming", PaapEvent.Direction.INBOUND)
        assertEquals("VEHICLE: car comming", e.summary())
    }

    @Test
    fun `Heartbeat summary`() {
        assertEquals("HEARTBEAT", PaapEvent.Heartbeat(PaapEvent.Direction.INBOUND).summary())
    }

    @Test
    fun `OnlineCheck summary`() {
        assertEquals("ONLINE CHECK", PaapEvent.OnlineCheck(PaapEvent.Direction.INBOUND).summary())
    }

    @Test
    fun `LinphoneCall summary`() {
        val e = PaapEvent.LinphoneCall("Idle", "Connected")
        assertEquals("CALL: Idle → Connected", e.summary())
    }

    @Test
    fun `Unknown summary truncates`() {
        val long = "x".repeat(100)
        val e = PaapEvent.Unknown(long, PaapEvent.Direction.INBOUND)
        assertTrue(e.summary().length <= 90) // "UNKNOWN: " + 80 chars
    }

    @Test
    fun `LinphoneCall directionLabel is DEVICE`() {
        assertEquals("DEVICE", PaapEvent.LinphoneCall("A", "B").directionLabel())
    }

    @Test
    fun `regular events directionLabel matches direction`() {
        assertEquals("INBOUND", PaapEvent.Heartbeat(PaapEvent.Direction.INBOUND).directionLabel())
        assertEquals("OUTBOUND", PaapEvent.Heartbeat(PaapEvent.Direction.OUTBOUND).directionLabel())
    }
}
