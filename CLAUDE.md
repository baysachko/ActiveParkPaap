# ActiveParkPaap

Overlay app for IoT-3288A ticket box (RK3288, Android 5.1.1, API 22, 1920x1080, rooted).
Sits on top of PAAP (`com.anziot.park`) — the Chinese parking app that handles all hardware (gate, printer, card readers, UDP).

## Architecture

- **PAAP** = invisible hardware layer (always running, GuardService auto-restarts it)
- **Our app** = visible UI overlay via `SYSTEM_ALERT_WINDOW` (`TYPE_SYSTEM_ALERT` on API 22)
- Reads PAAP's logcat (`PRETTY_LOGGER:E` tag) to intercept all UDP traffic
- Parses JSON payloads into typed events (gate open, TTS, print, display, vehicle sensing, etc.)
- Zero interference with PAAP — read-only logcat

## Build

- **Package**: `com.activepark_paap`
- **Stack**: Kotlin + XML views (NO Compose — Compose requires minSdk 23, box is API 22)
- **compileSdk**: 34, **minSdk**: 22, **targetSdk**: 34
- **Dependencies pinned** to older versions for compileSdk 34 compat (e.g. core-ktx:1.12.0)
- Build: `./gradlew assembleDebug`
- APK: `app/build/outputs/apk/debug/app-debug.apk`

## Deploy to Ticket Box

1. Enable adb on box terminal: `su && setprop service.adb.tcp.port 5555 && stop adbd && start adbd`
2. Connect: `adb connect 192.168.30.25:5555`
3. Install via Android Studio (device shows as "rockchip") or `adb install`
4. Grant permissions (once, persists across reboots):
   ```
   adb -s 192.168.30.25:5555 shell su -c "pm grant com.activepark_paap android.permission.READ_LOGS"
   adb -s 192.168.30.25:5555 shell su -c "appops set com.activepark_paap SYSTEM_ALERT_WINDOW allow"
   ```

## Key Files

- `LogcatReaderService.kt` — background logcat reader via `Runtime.exec("logcat", "-s", "PRETTY_LOGGER:E", "-T", "1")`. Uses AtomicBoolean to prevent duplicate processes. Drains stderr.
- `PaapEventParser.kt` — regex extracts JSON from `UdpManager:handleUdpReadData` (inbound) / `UdpWriterManager:send` (outbound), maps to typed events
- `PaapEvent.kt` — sealed class: GateOpen, Speak, PrintTicket, DisplayUpdate, VehicleSensing, PushButton, Heartbeat, OnlineCheck, Unknown
- `OverlayService.kt` — draws UI via WindowManager overlay. Uses `TYPE_APPLICATION_OVERLAY` on API 26+ (emulator), `TYPE_SYSTEM_ALERT` on API 22 (box)
- `MainActivity.kt` — launcher: starts LogcatReaderService + OverlayService, then finishes. Handles overlay permission prompt on API 23+.

## Critical Warnings

- **NO shell grep** — `logcat | grep` caused box slowdown/reboot. Use logcat's built-in `-s` tag filtering only.
- **NO Jetpack Compose** — requires minSdk 23, box is API 22. Use XML views.
- **PAAP must stay running** — it handles all hardware. Our app is overlay only.
- Emulator vs box: emulator needs `TYPE_APPLICATION_OVERLAY` + Settings permission grant. Box needs `TYPE_SYSTEM_ALERT` + `appops set`.

## Knowledge Reference

Full UDP protocol, hardware APIs, tested approaches:
`/Users/vodka/Documents/baysachko_obsidian/Parking_Integration/ParkingEdge/_android_ticket_box_knowledge.md`
