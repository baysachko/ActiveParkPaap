package com.activepark_paap

sealed class PaapEvent(val timestamp: Long = System.currentTimeMillis()) {

    /** Inbound vs outbound */
    enum class Direction { INBOUND, OUTBOUND }

    data class GateOpen(
        val delay: Int,
        val io: Int,
        val direction: Direction
    ) : PaapEvent()

    data class Speak(
        val text: String,
        val language: String,
        val speechRate: String,
        val direction: Direction
    ) : PaapEvent()

    data class PrintTicket(
        val title: String,
        val content: String,
        val qrCode: String,
        val direction: Direction
    ) : PaapEvent()

    data class DisplayUpdate(
        val texts: Map<String, DisplayField>,
        val direction: Direction
    ) : PaapEvent()

    data class DisplayField(
        val text: String,
        val color: String,
        val size: Int,
        val gravity: String
    )

    data class VehicleSensing(
        val status: String, // "car comming" or "car leave"
        val direction: Direction
    ) : PaapEvent()

    data class PushButton(
        val direction: Direction
    ) : PaapEvent()

    data class Heartbeat(
        val direction: Direction
    ) : PaapEvent()

    data class OnlineCheck(
        val direction: Direction
    ) : PaapEvent()

    data class Unknown(
        val rawJson: String,
        val direction: Direction
    ) : PaapEvent()

    fun summary(): String = when (this) {
        is GateOpen -> "GATE OPEN (delay=${delay}ms, io=$io)"
        is Speak -> "TTS: \"$text\" [$language]"
        is PrintTicket -> "PRINT: $title | QR=$qrCode"
        is DisplayUpdate -> "DISPLAY: ${texts.entries.joinToString { "${it.key}=${it.value.text}" }}"
        is VehicleSensing -> "VEHICLE: $status"
        is PushButton -> "BUTTON PRESSED"
        is Heartbeat -> "HEARTBEAT"
        is OnlineCheck -> "ONLINE CHECK"
        is Unknown -> "UNKNOWN: ${rawJson.take(80)}"
    }

    fun directionLabel(): String = when (this) {
        is GateOpen -> direction.name
        is Speak -> direction.name
        is PrintTicket -> direction.name
        is DisplayUpdate -> direction.name
        is VehicleSensing -> direction.name
        is PushButton -> direction.name
        is Heartbeat -> direction.name
        is OnlineCheck -> direction.name
        is Unknown -> direction.name
    }
}
