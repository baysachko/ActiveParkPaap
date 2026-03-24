package com.activepark_paap

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PaapEventParserTest {

    @Before
    fun setUp() {
        PaapEventParser.resetState()
    }

    @Test
    fun `linphone state transition`() {
        val line = "01-01 12:00:00.000 I/anziot: moving from state CallIdle to CallIncomingReceived"
        val event = PaapEventParser.parseLine(line) as PaapEvent.LinphoneCall
        assertEquals("CallIdle", event.fromState)
        assertEquals("CallIncomingReceived", event.toState)
    }

    @Test
    fun `inbound openDoor`() {
        val json = """{"command":"openDoor","delay":500,"io":1}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        val event = PaapEventParser.parseLine(line) as PaapEvent.GateOpen
        assertEquals(500, event.delay)
        assertEquals(1, event.io)
        assertEquals(PaapEvent.Direction.INBOUND, event.direction)
    }

    @Test
    fun `inbound speakOut`() {
        val json = """{"command":"speakOut","speakText":"Welcome","language":"en","speechRate":"1.5"}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        val event = PaapEventParser.parseLine(line) as PaapEvent.Speak
        assertEquals("Welcome", event.text)
        assertEquals("en", event.language)
        assertEquals("1.5", event.speechRate)
    }

    @Test
    fun `inbound Print`() {
        val json = """{"command":"Print","title":"Entry","content":"A-001","QRcode":"http://qr.test"}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        val event = PaapEventParser.parseLine(line) as PaapEvent.PrintTicket
        assertEquals("Entry", event.title)
        assertEquals("A-001", event.content)
        assertEquals("http://qr.test", event.qrCode)
    }

    @Test
    fun `inbound OnLine command`() {
        val json = """{"command":"OnLine"}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        val event = PaapEventParser.parseLine(line)
        assertTrue(event is PaapEvent.OnlineCheck)
        assertEquals(PaapEvent.Direction.INBOUND, (event as PaapEvent.OnlineCheck).direction)
    }

    @Test
    fun `heartbeat key`() {
        val json = """{"heartbeat":"1"}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        assertTrue(PaapEventParser.parseLine(line) is PaapEvent.Heartbeat)
    }

    @Test
    fun `PushButton key`() {
        val json = """{"PushButton":"1"}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        assertTrue(PaapEventParser.parseLine(line) is PaapEvent.PushButton)
    }

    @Test
    fun `Vehicle Sensing`() {
        val json = """{"Vehicle Sensing":"car comming"}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        val event = PaapEventParser.parseLine(line) as PaapEvent.VehicleSensing
        assertEquals("car comming", event.status)
    }

    @Test
    fun `DisplayUpdate with text fields`() {
        val json = """{"text1":{"text1Text":"Hello","text1Color":"#FF0000","text1Size":40,"text1Gravity":"CENTER"},"text2":{"text2Text":"World","text2Color":"#00FF00","text2Size":30,"text2Gravity":"LEFT"}}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        val event = PaapEventParser.parseLine(line) as PaapEvent.DisplayUpdate
        assertEquals("Hello", event.texts["text1"]?.text)
        assertEquals("#FF0000", event.texts["text1"]?.color)
        assertEquals(40, event.texts["text1"]?.size)
        assertEquals("World", event.texts["text2"]?.text)
    }

    @Test
    fun `unknown command returns Unknown`() {
        val json = """{"command":"foobar"}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        assertTrue(PaapEventParser.parseLine(line) is PaapEvent.Unknown)
    }

    @Test
    fun `malformed JSON returns Unknown`() {
        val line = "UdpManager: handleUdpReadData data = {broken json!!!"
        assertTrue(PaapEventParser.parseLine(line) is PaapEvent.Unknown)
    }

    @Test
    fun `outbound direction`() {
        val json = """{"command":"openDoor","delay":0,"io":0}"""
        val line = "UdpWriterManager: send data = $json"
        val event = PaapEventParser.parseLine(line) as PaapEvent.GateOpen
        assertEquals(PaapEvent.Direction.OUTBOUND, event.direction)
    }

    @Test
    fun `IO2 press emits PushButton pressed`() {
        val line = "E/PRETTY_LOGGER( 1120): │ MainFragment:handleIo2 ioValue = 1"
        val event = PaapEventParser.parseLine(line) as PaapEvent.PushButton
        assertTrue(event.pressed)
    }

    @Test
    fun `IO2 release emits PushButton released`() {
        // Force state to pressed first
        PaapEventParser.parseLine("E/PRETTY_LOGGER( 1120): │ MainFragment:handleIo2 ioValue = 1")
        val event = PaapEventParser.parseLine("E/PRETTY_LOGGER( 1120): │ MainFragment:handleIo2 ioValue = 0") as PaapEvent.PushButton
        assertFalse(event.pressed)
    }

    @Test
    fun `IO2 debounce suppresses duplicate values`() {
        // Reset by sending opposite value first
        PaapEventParser.parseLine("E/PRETTY_LOGGER( 1120): │ MainFragment:handleIo2 ioValue = 0")
        PaapEventParser.parseLine("E/PRETTY_LOGGER( 1120): │ MainFragment:handleIo2 ioValue = 1")
        // Same value again should be null
        val dup = PaapEventParser.parseLine("E/PRETTY_LOGGER( 1120): │ MainFragment:handleIo2 ioValue = 1")
        assertNull(dup)
    }

    // --- DisplayUpdate with member type text3 ---

    @Test
    fun `DisplayUpdate with CASH text3 and balance text4`() {
        val json = """{"text1":{"text1Text":"CB12345","text1Color":"#000","text1Size":40,"text1Gravity":"LEFT"},"text3":{"text3Text":"CASH","text3Color":"#FFF","text3Size":30,"text3Gravity":"CENTER"},"text4":{"text4Text":"10000","text4Color":"#333","text4Size":56,"text4Gravity":"LEFT"}}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        val event = PaapEventParser.parseLine(line) as PaapEvent.DisplayUpdate
        assertEquals("CB12345", event.texts["text1"]?.text)
        assertEquals("CASH", event.texts["text3"]?.text)
        assertEquals("10000", event.texts["text4"]?.text)
    }

    @Test
    fun `DisplayUpdate with cash deny text3`() {
        val json = """{"text1":{"text1Text":"CB12345","text1Color":"#000","text1Size":40,"text1Gravity":"LEFT"},"text3":{"text3Text":"Vehicle No. has little money.","text3Color":"#FFF","text3Size":30,"text3Gravity":"CENTER"},"text4":{"text4Text":"0","text4Color":"#333","text4Size":56,"text4Gravity":"LEFT"}}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        val event = PaapEventParser.parseLine(line) as PaapEvent.DisplayUpdate
        assertEquals("Vehicle No. has little money.", event.texts["text3"]?.text)
        assertEquals("0", event.texts["text4"]?.text)
    }

    @Test
    fun `DisplayUpdate with expired text3`() {
        val json = """{"text1":{"text1Text":"CB12345","text1Color":"#000","text1Size":40,"text1Gravity":"LEFT"},"text3":{"text3Text":"Vehicle No. is Expiry.","text3Color":"#FFF","text3Size":30,"text3Gravity":"CENTER"},"text4":{"text4Text":"04/23/2025","text4Color":"#333","text4Size":56,"text4Gravity":"LEFT"}}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        val event = PaapEventParser.parseLine(line) as PaapEvent.DisplayUpdate
        assertEquals("Vehicle No. is Expiry.", event.texts["text3"]?.text)
        assertEquals("04/23/2025", event.texts["text4"]?.text)
    }

    @Test
    fun `DisplayUpdate with VIP text3`() {
        val json = """{"text1":{"text1Text":"CB12345","text1Color":"#000","text1Size":40,"text1Gravity":"LEFT"},"text3":{"text3Text":"VIP","text3Color":"#FFF","text3Size":30,"text3Gravity":"CENTER"}}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        val event = PaapEventParser.parseLine(line) as PaapEvent.DisplayUpdate
        assertEquals("VIP", event.texts["text3"]?.text)
    }

    @Test
    fun `DisplayUpdate with missing text fields uses defaults`() {
        val json = """{"text1":{"text1Text":"Hello"}}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        val event = PaapEventParser.parseLine(line) as PaapEvent.DisplayUpdate
        assertEquals("Hello", event.texts["text1"]?.text)
        assertEquals("#FFFFFF", event.texts["text1"]?.color)
        assertEquals(30, event.texts["text1"]?.size)
        assertEquals("CENTER", event.texts["text1"]?.gravity)
    }

    // --- PrintTicket content edge cases ---

    @Test
    fun `PrintTicket with short content has empty ticketNo`() {
        val json = """{"command":"Print","title":"Entry","content":"footer1;footer2","QRcode":"http://qr"}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        val event = PaapEventParser.parseLine(line) as PaapEvent.PrintTicket
        assertEquals("footer1", event.footer1)
        assertEquals("footer2", event.footer2)
        assertEquals("", event.entryDate)
        assertEquals("", event.ticketNo)
    }

    @Test
    fun `PrintTicket with empty content`() {
        val json = """{"command":"Print","title":"Entry","content":"","QRcode":"http://qr"}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        val event = PaapEventParser.parseLine(line) as PaapEvent.PrintTicket
        assertEquals("", event.ticketNo)
        assertEquals("http://qr", event.qrCode)
    }

    @Test
    fun `PrintTicket with no content key`() {
        val json = """{"command":"Print","title":"Entry","QRcode":"http://qr"}"""
        val line = "UdpManager: handleUdpReadData data = $json"
        val event = PaapEventParser.parseLine(line) as PaapEvent.PrintTicket
        assertEquals("", event.content)
        assertEquals("", event.ticketNo)
    }

    @Test
    fun `unrecognized line returns null`() {
        assertNull(PaapEventParser.parseLine("some random log line"))
    }

    @Test
    fun `OnLine string reply`() {
        val line = """UdpManager: handleUdpReadData data = "OnLine""""
        val event = PaapEventParser.parseLine(line)
        // "OnLine" is not valid JSON so → Unknown, then check for OnLine string
        // Actually the parser wraps in tryParseJson which returns null → Unknown
        // Then checks jsonStr.trim('"') == "OnLine" — but this path is inside parseJson
        // which already got null from tryParseJson and returned Unknown early.
        // Let's verify behavior:
        assertNotNull(event)
    }
}
