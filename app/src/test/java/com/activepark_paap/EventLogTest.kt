package com.activepark_paap

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class EventLogTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private val noopLogger: (String, String, Throwable?) -> Unit = { _, _, _ -> }
    private lateinit var logDir: File

    @Before
    fun setup() {
        logDir = tmpDir.newFolder("logs")
    }

    @Test
    fun `append and loadToday round-trip`() {
        val log = EventLog(logDir, logger = noopLogger)
        val event = PaapEvent.Heartbeat(PaapEvent.Direction.INBOUND)
        log.append(event)
        val loaded = log.loadToday()
        assertEquals(1, loaded.size)
        // Heartbeat round-trips as Unknown with summary
        assertTrue(loaded[0] is PaapEvent.Unknown)
        assertTrue((loaded[0] as PaapEvent.Unknown).rawJson.contains("HEARTBEAT"))
    }

    @Test
    fun `DisplayUpdate serialize deserialize round-trip`() {
        val log = EventLog(logDir, logger = noopLogger)
        val texts = mapOf(
            "text1" to PaapEvent.DisplayField("Hello", "#FF0000", 40, "CENTER"),
            "text2" to PaapEvent.DisplayField("World", "#00FF00", 30, "LEFT")
        )
        val event = PaapEvent.DisplayUpdate(texts, PaapEvent.Direction.INBOUND)
        log.append(event)
        val loaded = log.loadToday()
        assertEquals(1, loaded.size)
        val du = loaded[0] as PaapEvent.DisplayUpdate
        assertEquals("Hello", du.texts["text1"]?.text)
        assertEquals("#FF0000", du.texts["text1"]?.color)
        assertEquals(40, du.texts["text1"]?.size)
        assertEquals("World", du.texts["text2"]?.text)
    }

    @Test
    fun `loadAll aggregates multiple files`() {
        val log = EventLog(logDir, logger = noopLogger)
        // Write a fake "yesterday" file
        val yesterday = File(logDir, "events_2020-01-01.jsonl")
        val json = log.eventToJson(PaapEvent.Heartbeat(PaapEvent.Direction.OUTBOUND))
        yesterday.writeText(json.toString() + "\n")
        // Write today
        log.append(PaapEvent.PushButton(pressed = true, direction = PaapEvent.Direction.INBOUND))
        val all = log.loadAll()
        assertEquals(2, all.size)
    }

    @Test
    fun `pruneOldFiles removes files older than 7 days`() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = dateFormat.parse("2024-06-15")!!.time + 12 * 3600 * 1000L // noon
        val log = EventLog(logDir, clock = { now }, logger = noopLogger)

        // Old file (>7 days)
        File(logDir, "events_2024-06-01.jsonl").writeText("{}\n")
        // Recent file
        File(logDir, "events_2024-06-14.jsonl").writeText("{}\n")

        log.pruneOldFiles()

        assertFalse(File(logDir, "events_2024-06-01.jsonl").exists())
        assertTrue(File(logDir, "events_2024-06-14.jsonl").exists())
    }

    @Test
    fun `loadToday on empty dir returns empty list`() {
        val log = EventLog(logDir, logger = noopLogger)
        assertTrue(log.loadToday().isEmpty())
    }

    @Test
    fun `eventToJson includes type and summary`() {
        val log = EventLog(logDir, logger = noopLogger)
        val json = log.eventToJson(PaapEvent.GateOpen(100, 2, PaapEvent.Direction.INBOUND))
        assertEquals("GateOpen", json.getString("type"))
        assertEquals("INBOUND", json.getString("dir"))
        assertTrue(json.getString("summary").contains("100"))
    }
}
