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
- **App icon**: `parking_sign.png` (P in circle) — PNG mipmaps (48–192px), no adaptive icon XMLs
- Build: `./gradlew assembleDebug`
- APK: `app/build/outputs/apk/debug/app-debug.apk`

## Deploy to Ticket Box

1. Enable adb on box terminal: `su && setprop service.adb.tcp.port 5555 && stop adbd && start adbd`
2. Connect: `adb connect 192.168.30.25:5555`
3. Install via Android Studio (device shows as "rockchip") or `adb install`
4. Permissions auto-granted on launch via `su` (MainActivity + LogcatReaderService). No manual adb commands needed. Wiped on reinstall but re-granted on next launch.

## Key Files

- `LogcatReaderService.kt` — background logcat reader via `Runtime.exec("logcat", "-s", "PRETTY_LOGGER:E", "anziot:I", "-T", "1")`. Uses AtomicBoolean to prevent duplicate processes. Drains stderr. 1s delay after `grantReadLogs()` before starting logcat process — fixes first-install permission race.
- `PaapEventParser.kt` — regex extracts JSON from `UdpManager:handleUdpReadData` (inbound) / `UdpWriterManager:send` (outbound), maps to typed events. Also parses GPIO IO2 (`handleIo2 ioValue`) for ticket button press/release with debounce (only emits on value change).
- `PaapEvent.kt` — sealed class: GateOpen, Speak, PrintTicket, DisplayUpdate, VehicleSensing, PushButton(pressed:Boolean), Heartbeat, OnlineCheck, LinphoneCall, DebugLog, Unknown
- `OverlayService.kt` — draws UI via WindowManager overlay. Uses `TYPE_APPLICATION_OVERLAY` on API 26+ (emulator), `TYPE_SYSTEM_ALERT` on API 22 (box). Monitors PAAP foreground state via `su -c "dumpsys activity activities"` every 5s — shows red warning banner when PAAP not resumed. Manages page switching via `Page` enum and `showPage()`. Role toggle (entry/exit) is sole authority for page routing — entry mode only shows IDLE/TRANSACTION, exit mode only shows EXIT_IDLE/EXIT_TRANSACTION/COMPLETED_EXIT.
- `MainActivity.kt` — launcher: starts LogcatReaderService + OverlayService, then finishes. Handles overlay permission prompt on API 23+.
- `BootReceiver.kt` — `BOOT_COMPLETED` + `ACTION_RESTART` receiver. Starts all 3 services, clears guard pause flag. ADB setup moved to OverlayService.
- `GuardService.kt` — watchdog in `:guard` process. Uses `GuardWatchdog` for pure restart logic.
- `GuardWatchdog.kt` — testable restart logic. 8s debounce, `killed` flag for pause.
- `EventLog.kt` — JSONL event persistence. 7 daily files, auto-prune. Testable class (inject `logDir`).
- `AdbRemoteHelper.kt` — enables adb TCP :5555 + iptables firewall to saved server IP. Writes `service.adb.tcp.port=5555` to `/system/build.prop` on first save (ROM resets it to `0` on reboot, so `ensureAdbConfigured()` re-applies on every boot). `isFirewallApplied()` checks iptables state. `persistAdbPort()` for save button, `ensureAdbConfigured()` for boot. Used by OverlayService (boot + save).
- `payment/PaymentConfig.kt` — SharedPreferences wrapper (`baseUrl`, `apiKey`, `pollIntervalMs`, `enabled`). `isReady()` = enabled + baseUrl + apiKey non-empty.
- `payment/PaymentState.kt` — sealed class: Idle, Initiating, AwaitingPayment(qrBitmap, tranId, expiresAtUnix, currency), Confirmed, Expired, Error(message), NotConfigured, FeatureUnavailable.
- `payment/PaymentApiClient.kt` — OkHttp3 wrapper. `initiate(cardNo)`, `pollStatus(tranId)`, `cancel(cardNo)`. Auth via `X-API-Key` header. 409 = existing transaction (returns cached QR). `baseUrl` = domain only (client appends `/api/v1/terminal-box/payment/...`).
- `payment/PaymentManager.kt` — lifecycle orchestrator. Owns `StateFlow<PaymentState>`, poll loop (configurable interval), cancel on page change, auto-cancel on idle-without-complete. `onPageChanged()` for page transitions, `startPayment(cardNo)` entry point.

## UI Screens (Page enum in OverlayService)

All screens share: top bar (logo + clock), accent line, bottom bar (version + network status + hand press indicator), QR card. Font: Space Grotesk via `FontHelper.applyFonts()` (tag-based: `android:tag="bold"`).

- **IDLE** — `EntryIdleView` / `overlay_entry_idle.xml` — "WELCOME" + ActivePark branding. `setMode(isExit=true)` switches to "GOODBYE".
- **EXIT_IDLE** — reuses `EntryIdleView` with `setMode(isExit=true)` — "GOODBYE" screen.
- **TRANSACTION** — `EntryTransactionView` / `overlay_entry_transaction.xml` — plate number, type badge, entry date. Status label configurable (WELCOME/navy).
- **EXIT_TRANSACTION** — `ExitTransactionView` / `overlay_exit_transaction.xml` — plate, parking time, pay amount. Status: EXITING/yellow `#E8A000`. When payment enabled, uses `ExitTransactionPaymentView` / `overlay_exit_transaction_payment.xml` instead (left=ticket info, right=QR/icons+status).
- **EXIT_TRANSACTION payment states**: AwaitingPayment (QR + countdown), Confirmed (checkmark_area.png + green "PAID" + "Processing..."), Expired (expired_area.png + red "EXPIRED"), Error (error_area.png + red "ERROR"), Gate timeout 20s (warning_area.png + yellow "Payment confirmed, gate not responding." + red "Please contact parking staff.").
- **COMPLETED_EXIT** — `CompletedExitView` / `overlay_completed_exit.xml` — green EXITED, plate, badge, exit date, "THANK YOU". With payment: adds subtitle "Payment Confirmed, Paid via QR" via `tvPaymentConfirmed`.
- **DEBUG** — `activity_main.xml` — event log + Page row (Idle, Transaction, Exit Idle, Exit Txn, Complete) + Pay row (Expired, Error, Gate Fail, Paid, Paid Exit) + ADB config + Payment config (API URL, API Key, Poll interval, ON/OFF toggle).

Debug access: 6-tap "Connected" text in bottom bar on any screen.

## UI Conventions

- Shared font logic: `ui/common/FontHelper.kt` — walks view tree, applies bold/regular based on `android:tag="bold"`
- `ui/common/OverlayBarHelper.kt` — manages top/bottom bar: clock, network status, phone call indicator, hand press indicator, paper status indicator. `setHandPress(pressed)` shows/hides `hand_press_indicator.png` in footer center (IDLE + TRANSACTION pages only). `setPaperStatus(PaperStatus)` shows/hides paper status icon centered in top bar (entry-only).
- View classes: `ui/entry/` (entry screens), `ui/exit/` (exit screens). Each has `startClock(scope)`, `onDebugRequested` callback, `setNetworkStatus()`.
- Badge drawable: `bg_type_badge.xml` (red `#8C1B0A`, 8dp corners)
- Colors: navy `#010062`, accent_red `#8C1B0A`, brand_red `#E84333`, exit yellow `#E8A000`, exit green `#22C55E`

## Design Reference

Pencil file: `/Users/vodka/Documents/Pencil/untitled.pen`
Frames: `Hdmbk` (Entry Idle), `bMAUt` (Transaction Entry), `bJGHf` (Exit Idle), `FMa34` (Active Transaction Exit), `iMUyu` (Completed Transaction Exit), `ItbOE` (Payment Expired), `ftrLC` (Payment Error), `UrCQx` (Payment Confirmed - Gate Not Responding)

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

- **AdbRemoteHelper.kt** — enables adb TCP on port 5555, locks with iptables to a single server IP. Server IP persisted in `SharedPreferences("adb_remote", "server_ip")`. Port persisted via `/system/build.prop` (`service.adb.tcp.port=5555`), but ROM resets to `0` on reboot.
- **Boot flow**: OverlayService `ensureAdbOnStartup()` → checks build.prop port (re-applies if not 5555) → checks iptables (re-applies if missing) → logs all actions to event log.
- **Save flow**: `persistAdbPort()` writes build.prop + setprop + adbd restart → `enableAdbWithFirewall()` applies iptables → all logged to event log.
- **Debug screen** has Server IP field + Save button + ADB status indicator (polls every 5s while debug visible, green=active, red=off).
- **One-time setup**: USB install → launch app once (required for BOOT_COMPLETED) → 6-tap debug → enter server IP → Save → walk away.
- **Remote workflow**: AnyDesk → customer server (same LAN) → `adb connect box-ip:5555` → `scrcpy` for full UI control.
- **Security**: iptables drops all connections to port 5555 except the saved server IP.
- **`persist.*` props NOT supported** on RK3288 ROM — must use build.prop + setprop on every boot.
- **`sed` not available** on ROM — use `busybox sed` for build.prop edits.
- **Skip adbd restart** if already on port 5555 (`isAdbEnabled()` check) — avoids killing active scrcpy sessions on app re-enter.

## QR Payment Integration

- **Flow**: EXIT_TRANSACTION → PaymentManager.startPayment(cardNo) → API initiate → QR displayed → poll status → Confirmed → PAAP gate-open → COMPLETED_EXIT
- **API**: `POST initiate`, `GET status/{tranId}`, `POST cancel`. Auth: `X-API-Key`. Base URL = domain only.
- **Config**: SharedPreferences `payment_config` — baseUrl, apiKey, pollIntervalMs (default 10s), enabled (default OFF). Debug UI has fields + ON/OFF toggle.
- **Happy path**: poll detects COMPLETED → show "Processing..." + checkmark → PAAP logcat gate-open event → COMPLETED_EXIT with payment label
- **Gate timeout**: 20s after Confirmed, if no PAAP gate-open → warning icon + "Payment confirmed, gate not responding." + "Please contact parking staff."
- **Cancel**: EXIT_IDLE reappears without COMPLETED_EXIT → auto-cancel via API
- **Timer**: uses `ExpiresIn` (relative seconds) from API to compute local expiry — avoids server/box clock mismatch
- **Currency**: API returns `Currency` field, shown as prefix on pay amount. `PaymentManager` caches currency from first initiate (200), reuses on 409 rescan (where Currency is null). Cleared on `destroy()`.
- **409 handling**: existing transaction — returns cached QR + ExistingTranId + ExpiresAt + RetryAfterSeconds. `Amount`, `Currency`, `ExpiresIn` are JSON null. `parseConflictAsSuccess` falls back `ExpiresIn` → `RetryAfterSeconds` (relative, avoids clock mismatch).
- **JSON null safety**: API 22 `optString` returns literal `"null"` for JSON null. `safeString()`/`safeLong()` in `PaymentApiClient.Companion` normalize to `""`/`0L`. Used in all response parsing.
- **Dependencies**: OkHttp3 `3.12.13` (last Java 7 compatible), coroutines-test, mockito-core
- **Tests**: PaymentConfigTest (6), PaymentApiClientTest (24), PaymentManagerTest (19) — all pure logic, no Robolectric

## Custom Ticket Printing (Direct USB Printer)

- **Why**: PAAP's UDP Print command (`{"command":"Print","title":"...","content":"...","QRcode":"..."}`) has fixed layout — no custom design. We bypass it entirely.
- **How**: PAAP config → "Enable New Print" ON → PAAP skips printing but still creates parking record, opens gate, TTS, display — all normal. Our app prints via direct USB.
- **Library**: `autoreplyprint.aar` in `app/libs/` — JNA-based ESC/POS SDK from Caysn. Contains `libautoreplyprint.so` + `libjnidispatch.so` for armeabi-v7a/arm64-v8a/x86.
- **Printer**: "KC PRINT PORT" USB thermal printer, VID=0x0FE6, PID=0x811E, on USB bus 005. Driver: `usblp`. No `/dev/usblp0` node — accessed via Android USB Host API.
- **`TicketPrinter.kt`**: `printTicket(title, ticketNo, entryDate, footer1, footer2, qrUrl)` — opens USB printer via `AutoReplyPrint.INSTANCE.CP_Port_OpenUsb("VID:0x0FE6,PID:0x811E", 0)`. Renders ticket as `Bitmap` on `Canvas` with all dynamic fields, prints via `CP_Pos_PrintRasterImageFromData_Helper.PrintRasterImageFromBitmap()`, then `CP_Pos_FeedAndHalfCutPaper()`. Full custom layout (Space Grotesk 18pt, QR, dividers).
- **Paper status indicator**: `ivPaperStatus` ImageView centered in top bar (`overlay_root.xml`), 36dp, GONE by default. Entry-only (exit boxes have no dispenser). `PaperStatus` enum in `OverlayBarHelper.kt`: OK (hidden), LOW_PAPER (`low_paper_indicator.png`), NO_PAPER (`no_paper_indicator.png`), ERROR (`error_indicator.png`).
- **Post-print status check**: `checkPaperStatus()` calls `CP_Pos_QueryRTStatus(handle, 30000)` ONLY after `PrintBitmap=true` (paper present = safe). Checks `CP_RTSTATUS_PAPER_NEAREND` only — returns `LowPaper` or `Success`. NEVER called standalone or on startup (SIGSEGV risk when paper empty). Status values are `Long` (Int→toLong conversion needed).
- **Print result → indicator**: `Success` → clear, `LowPaper` → low paper icon, `Error` → error icon. `printBusy` skip → NO_PAPER icon (busy = previous print hung on empty dispenser, JNA blocked). Clears automatically on next successful print after refill.
- **PrintResult sealed class**: `Success`, `LowPaper`, `Error(message)`. `PrintBitmap=false` → Error. `openDevice()=null` → Error (JNA returns Java null, not Pointer.NULL).
- **Debug UI**: "Printer:" row with CardNo input + "Print" button + ON/OFF toggle + status text. Wired in `OverlayService.setupDebugView()`.
- **Tested & working**: Port opens, bitmap renders (550xN), prints successfully, port closes. No sleep needed before close — printer buffers data internally.
- **PAAP conflict**: With "Enable New Print" ON, PAAP doesn't hold the USB printer, so no conflict.
- **Entry flow with custom print**: PushButton → parking system creates record + gate opens + TTS (no print) → our app detects PrintTicket in logcat → sends own print via USB. CardNo from QR URL or Display text1 field.
- **Reference source**: PAAP's printer code in `/Users/vodka/Documents/Business/ParkingSystem/TicketboxAndroidDevelopment/paapsmtTest/paapsmtTest/app/src/main/java/com/sztigerwong/paap/ui/MainActivity.java` — uses same AutoReplyPrint library, renders bitmap on Canvas, prints as raster image.
- **Content field format**: `;`-separated — `[0]=footer1, [1]=footer2, [2]=entryDate, [3]=cardNo, [4]=scanLabel(unused)`. Parsed in `PrintTicket` data class via `contentParts`.
- **Auto-print wired**: `OverlayService.collectEvents()` catches `PrintTicket` → `handlePrintTicket()` → `TicketPrinter.printTicket()` on dedicated `PrinterThread` (single-thread context) with all dynamic fields from PAAP. `printBusy` flag prevents concurrent USB access.
- **Print busy skip**: When `printBusy=true`, print is skipped but PAAP still creates parking record, opens gate, TTS, display — all normal. Only the physical ticket doesn't print. Busy state = previous JNA call hung (empty dispenser). Shows NO_PAPER indicator.
- **Print toggle**: SharedPreferences `"printer_config"."enabled"` (default `true`). ON/OFF button in debug UI Printer row. When OFF, `handlePrintTicket()` skips — PAAP prints natively.

## Hardware API Docs

- **TGW RFID & Ticket Parking API**: `/Users/vodka/Documents/Business/ParkingSystem/TicketboxAndroidDevelopment/md/TGW_RFID_Ticket_Parking_API.md` — UDP commands (Print, openDoor, speakOut, Takecard, etc.), port 8000. Upload events (PushButton, VehicleSensing, card readers, printer status).
- **SMDT Android Board API**: `/Users/vodka/Documents/Business/ParkingSystem/TicketboxAndroidDevelopment/md/API_Android_Board.md` — `SmdtManager` via `smdt.jar`. GPIO, watchdog, serial, display, USB, network. No printer API.
- **IoT-3288A Spec**: `/Users/vodka/Documents/Business/ParkingSystem/TicketboxAndroidDevelopment/md/IoT3288A_Specification.md` — RK3288 board hardware spec (PCB, connectors, power). No software APIs.
- **PAAP source (reference)**: `/Users/vodka/Documents/Business/ParkingSystem/TicketboxAndroidDevelopment/paapsmtTest/` — TigerWong's test app. Has `AutoReplyPrint` printer code, `ZxingUtils` QR generation, UART/serial device handling.

## Critical Warnings

- **NO shell grep** — `logcat | grep` caused box slowdown/reboot. Use logcat's built-in `-s` tag filtering only.
- **NO Jetpack Compose** — requires minSdk 23, box is API 22. Use XML views.
- **PAAP must stay running** — it handles all hardware. Our app is overlay only. PAAP must be in foreground (resumed activity) for hardware to work.
- **Ticket button**: GPIO IO2 — PAAP logs `MainFragment:handleIo2 ioValue = 1` (press) and `0` (release) at ~30Hz polling. Debounced in `PaapEventParser` to 2 events per press cycle.
- **Hardware API doc**: `/Users/vodka/Documents/Business/ParkingSystem/TicketboxAndroidDevelopment/md/API_Android_Board.md` (SMDT/TigerWong board API — GPIO, serial, watchdog, display).
- **UDP timing**: Heartbeat every 10s, OnlineCheck every 60s.
- **Linphone/SIP**: PAAP embeds linphone 4.5.0 for cashier↔box calls. Logs under `anziot:I` tag. Call state transitions: `CallIdle → CallIncomingReceived → CallConnected → CallStreamsRunning → CallEnd → CallReleased`. Outgoing: `CallIdle → CallOutgoingInit → CallOutgoingProgress → CallOutgoingRinging → CallEnd`.
- Emulator vs box: emulator needs `TYPE_APPLICATION_OVERLAY` + Settings permission grant. Box needs `TYPE_SYSTEM_ALERT` + `appops set`.
- **PAAP detection**: `getRunningTasks`/`getRunningAppProcesses` don't work (overlay steals focus, API restrictions). Only `su -c "dumpsys activity activities | grep mResumedActivity"` reliably detects PAAP foreground state on the rooted box.

## Critical Rules — DO NOT VIOLATE

- **Ask before ambiguity** — ask me questions before making decisions and code implementation
- **NEVER create mock/simplified components** — always fix actual problems in existing codebase
- **NO FALLBACK DATA** — never add mock or fallback data
- **Always ask before making decisions** — explain concerns, don't decide independently
- **Verify changes don't break existing functionality**
- **Unit Tests Safety** — use in-memory databases or mocks only (no production DB operations)
- **NASA Power of 10**: 1. Simple Control Flow 2. Bounded Iterations 3. Static Memory 4. <60 lines/fn 5. Assert Everything 6. Minimal Scope 7. Check All Returns 8. No Meta-Programming 9. Simple Data Structures 10. Zero Warnings

## Knowledge Reference

Full UDP protocol, hardware APIs, tested approaches:
`/Users/vodka/Documents/baysachko_obsidian/Parking_Integration/ParkingEdge/_android_ticket_box_knowledge.md`
