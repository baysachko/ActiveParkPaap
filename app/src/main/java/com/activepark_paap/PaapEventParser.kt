package com.activepark_paap

import org.json.JSONObject

object PaapEventParser {

    private val INBOUND_PATTERN = Regex("""UdpManager\s*:\s*handleUdpReadData\s+data\s*=\s*(.+)""")
    private val OUTBOUND_PATTERN = Regex("""UdpWriterManager\s*:\s*send\s+data\s*=\s*(.+)""")
    private val LINPHONE_STATE_PATTERN = Regex("""moving from state (\S+) to (\S+)""")
    private val IO2_PATTERN = Regex("""handleIo2\s+ioValue\s*=\s*(\d)""")

    /** Debounce: only emit when IO2 value changes */
    private var lastIo2Value: Int = -1

    /** Reset debounce state (for testing) */
    fun resetState() { lastIo2Value = -1 }

    /** Parse a logcat line into a PaapEvent, or null if not a recognized line. */
    fun parseLine(line: String): PaapEvent? {
        IO2_PATTERN.find(line)?.let { match ->
            val value = match.groupValues[1].toInt()
            if (value == lastIo2Value) return null
            lastIo2Value = value
            return PaapEvent.PushButton(
                pressed = value == 1,
                direction = PaapEvent.Direction.OUTBOUND
            )
        }
        LINPHONE_STATE_PATTERN.find(line)?.let { match ->
            return PaapEvent.LinphoneCall(
                fromState = match.groupValues[1].removePrefix("Linphone"),
                toState = match.groupValues[2].removePrefix("Linphone")
            )
        }
        val (jsonStr, direction) = extractJson(line) ?: return null
        return parseJson(jsonStr.trim(), direction)
    }

    private fun extractJson(line: String): Pair<String, PaapEvent.Direction>? {
        INBOUND_PATTERN.find(line)?.let {
            return it.groupValues[1] to PaapEvent.Direction.INBOUND
        }
        OUTBOUND_PATTERN.find(line)?.let {
            return it.groupValues[1] to PaapEvent.Direction.OUTBOUND
        }
        return null
    }

    private fun parseJson(jsonStr: String, dir: PaapEvent.Direction): PaapEvent {
        val json = tryParseJson(jsonStr) ?: return PaapEvent.Unknown(jsonStr, dir)

        // Command-based events
        val command = json.optString("command", "")
        if (command.isNotEmpty()) {
            return when (command) {
                "openDoor" -> PaapEvent.GateOpen(
                    delay = json.optInt("delay", 0),
                    io = json.optInt("io", 0),
                    direction = dir
                )
                "speakOut" -> PaapEvent.Speak(
                    text = json.optString("speakText", ""),
                    language = json.optString("language", ""),
                    speechRate = json.optString("speechRate", "1.0"),
                    direction = dir
                )
                "Print" -> PaapEvent.PrintTicket(
                    title = json.optString("title", ""),
                    content = json.optString("content", ""),
                    qrCode = json.optString("QRcode", ""),
                    direction = dir
                )
                "OnLine" -> PaapEvent.OnlineCheck(dir)
                else -> PaapEvent.Unknown(jsonStr, dir)
            }
        }

        // Key-based events
        if (json.has("heartbeat")) return PaapEvent.Heartbeat(dir)
        if (json.has("PushButton")) return PaapEvent.PushButton(pressed = true, direction = dir)
        if (json.has("Vehicle Sensing")) {
            return PaapEvent.VehicleSensing(
                status = json.optString("Vehicle Sensing", ""),
                direction = dir
            )
        }

        // Display update: has text1..text5 or notice
        val displayKeys = listOf("text1", "text2", "text3", "text4", "text5", "notice")
        val foundDisplayKeys = displayKeys.filter { json.has(it) }
        if (foundDisplayKeys.isNotEmpty()) {
            val texts = mutableMapOf<String, PaapEvent.DisplayField>()
            for (key in foundDisplayKeys) {
                val fieldObj = json.optJSONObject(key) ?: continue
                texts[key] = PaapEvent.DisplayField(
                    text = fieldObj.optString("${key}Text", ""),
                    color = fieldObj.optString("${key}Color", "#FFFFFF"),
                    size = fieldObj.optInt("${key}Size", 30),
                    gravity = fieldObj.optString("${key}Gravity", "CENTER")
                )
            }
            return PaapEvent.DisplayUpdate(texts, dir)
        }

        // OnLine string reply
        if (jsonStr.trim('"') == "OnLine") return PaapEvent.OnlineCheck(dir)

        return PaapEvent.Unknown(jsonStr, dir)
    }

    private fun tryParseJson(s: String): JSONObject? {
        return try {
            JSONObject(s)
        } catch (_: Exception) {
            null
        }
    }
}
