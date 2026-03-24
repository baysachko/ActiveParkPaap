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
    fun `PrintTicket summary includes content and ticketNo`() {
        val content = "Keep ticket;Lost no comp;03/05/2026 16:22:14;0103052026162214;Scan"
        val e = PaapEvent.PrintTicket("Title", content, "QR", PaapEvent.Direction.INBOUND)
        assertEquals("PRINT: Title | ticket=0103052026162214 | content=$content | QR=QR", e.summary())
    }

    @Test
    fun `PrintTicket parses content fields`() {
        val content = "Please keep the ticket;Lost not compensation;03/05/2026 16:22:14;0103052026162214;Scan to pay"
        val e = PaapEvent.PrintTicket("T", content, "QR", PaapEvent.Direction.INBOUND)
        assertEquals("Please keep the ticket", e.footer1)
        assertEquals("Lost not compensation", e.footer2)
        assertEquals("03/05/2026 16:22:14", e.entryDate)
        assertEquals("0103052026162214", e.ticketNo)
    }

    @Test
    fun `PrintTicket handles missing content fields gracefully`() {
        val e = PaapEvent.PrintTicket("T", "only footer1", "QR", PaapEvent.Direction.INBOUND)
        assertEquals("only footer1", e.footer1)
        assertEquals("", e.footer2)
        assertEquals("", e.entryDate)
        assertEquals("", e.ticketNo)
    }

    @Test
    fun `PrintTicket handles empty content`() {
        val e = PaapEvent.PrintTicket("T", "", "QR", PaapEvent.Direction.INBOUND)
        assertEquals("", e.footer1)
        assertEquals("", e.footer2)
        assertEquals("", e.entryDate)
        assertEquals("", e.ticketNo)
    }

    @Test
    fun `PrintTicket semicolons only → all empty parts`() {
        val e = PaapEvent.PrintTicket("T", ";;;", "QR", PaapEvent.Direction.INBOUND)
        assertEquals("", e.footer1)
        assertEquals("", e.footer2)
        assertEquals("", e.entryDate)
        assertEquals("", e.ticketNo)
    }

    @Test
    fun `PrintTicket trailing semicolons`() {
        val e = PaapEvent.PrintTicket("T", "f1;f2;date;ticket;", "QR", PaapEvent.Direction.INBOUND)
        assertEquals("f1", e.footer1)
        assertEquals("f2", e.footer2)
        assertEquals("date", e.entryDate)
        assertEquals("ticket", e.ticketNo)
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
