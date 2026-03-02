# ActiveParkPaap

Overlay app for IoT-3288A ticket box (RK3288, Android 5.1.1, API 22, 1024x768, rooted).
Sits on top of PAAP (`com.anziot.park`) — the Chinese parking app that handles all hardware (gate, printer, card readers, UDP).

## Architecture

- **PAAP** = invisible hardware layer (always running, GuardService auto-restarts it)
- **Our app** = visible UI overlay via `SYSTEM_ALERT_WINDOW` (`TYPE_SYSTEM_ALERT` on API 22)
- Reads PAAP's logcat (`PRETTY_LOGGER:E` + `anziot:I` tags) to intercept UDP traffic and linphone call states
- Parses JSON payloads into typed events (gate open, TTS, print, display, vehicle sensing, linphone call, etc.)
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

- `LogcatReaderService.kt` — background logcat reader via `Runtime.exec("logcat", "-s", "PRETTY_LOGGER:E", "anziot:I", "-T", "1")`. Uses AtomicBoolean to prevent duplicate processes. Drains stderr.
- `PaapEventParser.kt` — regex extracts JSON from `UdpManager:handleUdpReadData` (inbound) / `UdpWriterManager:send` (outbound), maps to typed events
- `PaapEvent.kt` — sealed class: GateOpen, Speak, PrintTicket, DisplayUpdate, VehicleSensing, PushButton, Heartbeat, OnlineCheck, LinphoneCall, Unknown
- `OverlayService.kt` — draws UI via WindowManager overlay. Uses `TYPE_APPLICATION_OVERLAY` on API 26+ (emulator), `TYPE_SYSTEM_ALERT` on API 22 (box). Monitors PAAP foreground state via `su -c "dumpsys activity activities"` every 5s — shows red warning banner when PAAP not resumed. Manages page switching via `Page` enum and `showPage()`. Role toggle (entry/exit) is sole authority for page routing — entry mode only shows IDLE/TRANSACTION, exit mode only shows EXIT_IDLE/EXIT_TRANSACTION/COMPLETED_EXIT.
- `MainActivity.kt` — launcher: starts LogcatReaderService + OverlayService, then finishes. Handles overlay permission prompt on API 23+.
- `BootReceiver.kt` — `BOOT_COMPLETED` + custom `ACTION_RESTART` receiver. Starts all 3 services, clears guard pause flag.
- `GuardService.kt` — watchdog in `:guard` process. Uses `GuardWatchdog` for pure restart logic.
- `GuardWatchdog.kt` — testable restart logic. 8s debounce, `killed` flag for pause.
- `EventLog.kt` — JSONL event persistence. 7 daily files, auto-prune. Testable class (inject `logDir`).
- `AdbRemoteHelper.kt` — enables adb TCP :5555 + iptables firewall to saved server IP. Used by BootReceiver (on boot) and debug UI (on save).

## UI Screens (Page enum in OverlayService)

All screens share: top bar (logo + clock), accent line, bottom bar (version + network status), QR card. Font: Space Grotesk via `FontHelper.applyFonts()` (tag-based: `android:tag="bold"`).

- **IDLE** — `EntryIdleView` / `overlay_entry_idle.xml` — "WELCOME" + ActivePark branding. `setMode(isExit=true)` switches to "GOODBYE".
- **EXIT_IDLE** — reuses `EntryIdleView` with `setMode(isExit=true)` — "GOODBYE" screen.
- **TRANSACTION** — `EntryTransactionView` / `overlay_entry_transaction.xml` — plate number, type badge, entry date. Status label configurable (WELCOME/navy).
- **EXIT_TRANSACTION** — `ExitTransactionView` / `overlay_exit_transaction.xml` — plate, parking time, pay amount. Status: EXITING/yellow `#E8A000`.
- **COMPLETED_EXIT** — `CompletedExitView` / `overlay_completed_exit.xml` — green EXITED, plate, badge, exit date, "THANK YOU".
- **DEBUG** — `activity_main.xml` — event log + page selector buttons (Idle, Transaction, Exit Idle, Exit Txn, Complete).

Debug access: 6-tap "Connected" text in bottom bar on any screen.

## UI Conventions

- Shared font logic: `ui/common/FontHelper.kt` — walks view tree, applies bold/regular based on `android:tag="bold"`
- View classes: `ui/entry/` (entry screens), `ui/exit/` (exit screens). Each has `startClock(scope)`, `onDebugRequested` callback, `setNetworkStatus()`.
- Badge drawable: `bg_type_badge.xml` (red `#8C1B0A`, 8dp corners)
- Colors: navy `#010062`, accent_red `#8C1B0A`, brand_red `#E84333`, exit yellow `#E8A000`, exit green `#22C55E`

## Design Reference

Pencil file: `/Users/vodka/Documents/Pencil/untitled.pen`
Frames: `Hdmbk` (Entry Idle), `bMAUt` (Transaction Entry), `bJGHf` (Exit Idle), `FMa34` (Active Transaction Exit), `iMUyu` (Completed Transaction Exit)

## Auto-start & Self-restart

- **Boot receiver** (`BootReceiver.kt`) — `BOOT_COMPLETED` starts all 3 services, clears guard pause flag
- **GuardService** (`GuardService.kt`) — runs in `:guard` process, checks every 8s via `GuardWatchdog`. Restarts dead services only after 8s debounce. `ACTION_PAUSE` stops restarts. Self-restarts via AlarmManager if killed.
- **GuardWatchdog** (`GuardWatchdog.kt`) — pure logic, no Android deps. Tracks `firstDeadAt` per service, only restarts after `restartDelayMs` (8s). `killed` flag pauses all checks.
- **OverlayService** uses `START_NOT_STICKY` — OS does NOT restart it. GuardService is sole restart owner.
- **LogcatReaderService** uses `START_STICKY` — OS restarts if killed by low memory.
- **`stopWithTask="false"`** on OverlayService, LogcatReaderService, GuardService
- **Auto:ON/OFF** toggle in debug view — persisted via `SharedPreferences("guard_state", "paused")`. Auto:OFF pauses GuardService restarts.
- Box logs: app-level `Log.i`/`Log.d` don't appear in logcat. Use `Log.e` for debugging.

## Event Log

- `EventLog.kt` — persists events to JSONL files in `filesDir/logs/events_YYYY-MM-DD.jsonl`
- 1 file per day, 7 days max (auto-prune on init)
- `LogcatReaderService` appends events on receive
- `OverlayService` loads today's history on startup → debug view shows past events
- Class-based (not singleton) for testability — inject `logDir` via constructor

## Remote Access (ADB + scrcpy)

- **AdbRemoteHelper.kt** — enables adb TCP on port 5555, locks with iptables to a single server IP. Persisted in `SharedPreferences("adb_remote", "server_ip")`.
- **BootReceiver** calls `AdbRemoteHelper.enableAdbWithFirewall(context)` on boot — adb+iptables auto-restored after reboot.
- **Debug screen** has Server IP field + Save button + ADB status indicator (green=active, red=off).
- **One-time setup**: USB install → 6-tap debug → enter server IP → Save → walk away.
- **Remote workflow**: AnyDesk → customer server (same LAN) → `adb connect box-ip:5555` → `scrcpy` for full UI control.
- **Security**: iptables drops all connections to port 5555 except the saved server IP.

## Critical Warnings

- **NO shell grep** — `logcat | grep` caused box slowdown/reboot. Use logcat's built-in `-s` tag filtering only.
- **NO Jetpack Compose** — requires minSdk 23, box is API 22. Use XML views.
- **PAAP must stay running** — it handles all hardware. Our app is overlay only. PAAP must be in foreground (resumed activity) for hardware to work.
- **UDP timing**: Heartbeat every 10s, OnlineCheck every 60s.
- **Linphone/SIP**: PAAP embeds linphone 4.5.0 for cashier↔box calls. Logs under `anziot:I` tag. Call state transitions: `CallIdle → CallIncomingReceived → CallConnected → CallStreamsRunning → CallEnd → CallReleased`. Outgoing: `CallIdle → CallOutgoingInit → CallOutgoingProgress → CallOutgoingRinging → CallEnd`.
- Emulator vs box: emulator needs `TYPE_APPLICATION_OVERLAY` + Settings permission grant. Box needs `TYPE_SYSTEM_ALERT` + `appops set`.
- **PAAP detection**: `getRunningTasks`/`getRunningAppProcesses` don't work (overlay steals focus, API restrictions). Only `su -c "dumpsys activity activities | grep mResumedActivity"` reliably detects PAAP foreground state on the rooted box.

## Knowledge Reference

Full UDP protocol, hardware APIs, tested approaches:
`/Users/vodka/Documents/baysachko_obsidian/Parking_Integration/ParkingEdge/_android_ticket_box_knowledge.md`
