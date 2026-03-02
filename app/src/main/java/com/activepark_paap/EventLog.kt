package com.activepark_paap

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class EventLog(
    private val logDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String, String, Throwable?) -> Unit = { t, m, e -> Log.e(t, m, e) }
) {

    companion object {
        private const val TAG = "EventLog"
        private const val MAX_DAYS = 7
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        fun create(
            logDir: File,
            clock: () -> Long = System::currentTimeMillis,
            logger: (String, String, Throwable?) -> Unit = { t, m, e -> Log.e(t, m, e) }
        ): EventLog {
            assert(logDir.exists() || logDir.mkdirs()) { "EventLog: can't create logDir" }
            val log = EventLog(logDir, clock, logger)
            log.pruneOldFiles()
            return log
        }
    }

    fun append(event: PaapEvent) {
        assert(logDir.exists()) { "EventLog: logDir doesn't exist" }
        try {
            val json = eventToJson(event)
            todayFile().appendText(json.toString() + "\n")
        } catch (e: Exception) {
            logger(TAG, "append failed", e)
        }
    }

    fun loadToday(): List<PaapEvent> = loadFile(todayFile())

    fun loadAll(): List<PaapEvent> {
        val files = logDir.listFiles { f -> f.name.startsWith("events_") && f.name.endsWith(".jsonl") }
            ?: return emptyList()
        return files.sortedBy { it.name }
            .flatMap { loadFile(it) }
    }

    fun todayFile(): File = File(logDir, "events_${dateFormat.format(Date())}.jsonl")

    fun eventToJson(event: PaapEvent): JSONObject = JSONObject().apply {
        put("ts", event.timestamp)
        put("type", event.javaClass.simpleName)
        put("dir", event.directionLabel())
        put("summary", event.summary())
        if (event is PaapEvent.DisplayUpdate) {
            val textsObj = JSONObject()
            for ((key, field) in event.texts) {
                textsObj.put(key, JSONObject().apply {
                    put("text", field.text)
                    put("color", field.color)
                    put("size", field.size)
                    put("gravity", field.gravity)
                })
            }
            put("texts", textsObj)
        }
    }

    fun parseJsonLine(line: String): PaapEvent? {
        if (line.isBlank()) return null
        val json = JSONObject(line)
        val ts = json.getLong("ts")
        val dir = if (json.getString("dir") == "INBOUND")
            PaapEvent.Direction.INBOUND else PaapEvent.Direction.OUTBOUND
        val type = json.optString("type", "Unknown")

        if (type == "DisplayUpdate" && json.has("texts")) {
            val textsObj = json.getJSONObject("texts")
            val texts = mutableMapOf<String, PaapEvent.DisplayField>()
            for (key in textsObj.keys()) {
                val f = textsObj.getJSONObject(key)
                texts[key] = PaapEvent.DisplayField(
                    text = f.optString("text", ""),
                    color = f.optString("color", ""),
                    size = f.optInt("size", 0),
                    gravity = f.optString("gravity", "")
                )
            }
            return PaapEvent.DisplayUpdate(texts = texts, direction = dir)
        }

        val summary = json.getString("summary")
        return PaapEvent.Unknown(rawJson = summary, direction = dir, ts = ts)
    }

    private fun loadFile(file: File): List<PaapEvent> {
        if (!file.exists()) return emptyList()
        val results = mutableListOf<PaapEvent>()
        try {
            file.forEachLine { line ->
                try {
                    val event = parseJsonLine(line)
                    if (event != null) results.add(event)
                } catch (e: Exception) {
                    logger(TAG, "parse failed: $line", e)
                }
            }
        } catch (e: Exception) {
            logger(TAG, "loadFile failed", e)
        }
        return results
    }

    fun pruneOldFiles() {
        val cutoff = clock() - (MAX_DAYS * 24 * 60 * 60 * 1000L)
        val files = logDir.listFiles() ?: return
        for (file in files) {
            if (!file.name.startsWith("events_")) continue
            val dateStr = file.name.removePrefix("events_").removeSuffix(".jsonl")
            try {
                val fileDate = dateFormat.parse(dateStr) ?: continue
                if (fileDate.time < cutoff) {
                    file.delete()
                    logger(TAG, "Pruned old log: ${file.name}", null)
                }
            } catch (_: Exception) { }
        }
    }
}
