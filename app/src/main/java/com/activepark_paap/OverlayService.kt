package com.activepark_paap

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.activepark_paap.ui.common.FontHelper
import com.activepark_paap.ui.common.OverlayBarHelper
import com.activepark_paap.ui.entry.EntryIdleView
import com.activepark_paap.ui.entry.EntryTransactionView
import com.activepark_paap.ui.exit.CompletedExitView
import com.activepark_paap.ui.exit.ExitTransactionView
import com.activepark_paap.ui.exit.ExitTransactionPaymentView
import com.activepark_paap.payment.PaymentApiClient
import com.activepark_paap.payment.PaymentConfig
import com.activepark_paap.payment.PaymentManager
import com.activepark_paap.payment.PaymentState
import kotlinx.coroutines.*

private typealias Page = PageRouter.Page

class OverlayService : Service() {

    companion object

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var contentContainer: FrameLayout? = null
    private var barHelper: OverlayBarHelper? = null
    private val events = mutableListOf<PaapEvent>()
    private lateinit var adapter: EventAdapter
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var idleView: EntryIdleView? = null
    private var exitIdleView: EntryIdleView? = null
    private var transactionView: EntryTransactionView? = null
    private var exitTransactionView: ExitTransactionView? = null
    private var exitTransactionPaymentView: ExitTransactionPaymentView? = null
    private var completedExitView: CompletedExitView? = null
    private var debugView: View? = null
    private var currentPage = Page.IDLE
    private var pendingPage: Page? = null
    private var callActive = false
    private var paymentManager: PaymentManager? = null
    private var currentCardNo: String? = null
    private var countdownJob: Job? = null
    private var gateTimeoutJob: Job? = null
    private var adbStatusJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        loadEventHistory()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
        setupPaymentManager()
        collectEvents()
        ensureAdbOnStartup()
        restoreRole()
        monitorPaap()
    }

    @Suppress("DEPRECATION")
    private fun createOverlay() {
        val inflater = LayoutInflater.from(this)
        rootView = inflater.inflate(R.layout.overlay_root, null)
        contentContainer = rootView!!.findViewById(R.id.contentContainer)
        assert(contentContainer != null) { "contentContainer not found" }

        barHelper = OverlayBarHelper(rootView!!)
        barHelper!!.onDebugRequested = { showPage(Page.DEBUG) }
        barHelper!!.startClock(scope)
        FontHelper.applyFonts(this, rootView!!)

        @Suppress("DEPRECATION")
        val windowType = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        setupIdleView()
        setupExitIdleView()
        setupTransactionView()
        setupExitTransactionView()
        setupExitTransactionPaymentView()
        setupCompletedExitView()
        setupDebugView()
        showPage(Page.IDLE)

        try {
            windowManager!!.addView(rootView, params)
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to add overlay", e)
            stopSelf()
            return
        }
    }

    private fun setupIdleView() {
        idleView = EntryIdleView(this)
    }

    private fun setupExitIdleView() {
        exitIdleView = EntryIdleView(this)
        exitIdleView!!.setMode(isExit = true)
    }

    private fun setupTransactionView() {
        transactionView = EntryTransactionView(this)
    }

    private fun setupExitTransactionView() {
        exitTransactionView = ExitTransactionView(this)
    }

    private fun setupExitTransactionPaymentView() {
        exitTransactionPaymentView = ExitTransactionPaymentView(this)
    }

    private fun setupCompletedExitView() {
        completedExitView = CompletedExitView(this)
    }

    private fun setupDebugView() {
        val inflater = LayoutInflater.from(this)
        debugView = inflater.inflate(R.layout.activity_main, null)

        val recycler = debugView!!.findViewById<RecyclerView>(R.id.recyclerEvents)
        val tvEmpty = debugView!!.findViewById<TextView>(R.id.tvEmpty)
        adapter = EventAdapter(events)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        debugView!!.findViewById<Button>(R.id.btnClear).setOnClickListener {
            events.clear()
            adapter.notifyDataSetChanged()
            updateDebugVisibility(tvEmpty, recycler)
        }

        debugView!!.findViewById<Button>(R.id.btnTest).setOnClickListener {
            injectTestEvents()
        }

        debugView!!.findViewById<Button>(R.id.btnBack).setOnClickListener {
            if (currentPage == Page.DEBUG) {
                val target = pendingPage ?: run {
                    val role = getSharedPreferences("box_state", MODE_PRIVATE)
                        .getString("role", "entry") ?: "entry"
                    PageRouter.initialPageForRole(role)
                }
                pendingPage = null
                showPage(target)
            }
        }

        val btnAuto = debugView!!.findViewById<Button>(R.id.btnAutoRestart)
        val guardPrefs = getSharedPreferences("guard_state", MODE_PRIVATE)
        val paused = guardPrefs.getBoolean("paused", false)
        btnAuto.text = if (paused) "Auto:OFF" else "Auto:ON"
        btnAuto.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (paused) Color.parseColor("#666666") else Color.parseColor("#388E3C")
        )
        btnAuto.setOnClickListener {
            val nowPaused = !guardPrefs.getBoolean("paused", false)
            guardPrefs.edit().putBoolean("paused", nowPaused).commit()
            if (nowPaused) {
                startService(Intent(this, GuardService::class.java).apply {
                    action = GuardService.ACTION_PAUSE
                })
            } else {
                startService(Intent(this, GuardService::class.java))
            }
            btnAuto.text = if (nowPaused) "Auto:OFF" else "Auto:ON"
            btnAuto.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (nowPaused) Color.parseColor("#666666") else Color.parseColor("#388E3C")
            )
        }

        // Role toggle button
        val rolePrefs = getSharedPreferences("box_state", MODE_PRIVATE)
        val btnRole = debugView!!.findViewById<Button>(R.id.btnRole)
        fun updateRoleBtn() {
            val role = rolePrefs.getString("role", "entry") ?: "entry"
            btnRole.text = role.uppercase()
            btnRole.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (role == "entry") Color.parseColor("#010062") else Color.parseColor("#E8A000")
            )
        }
        updateRoleBtn()
        btnRole.setOnClickListener {
            val current = rolePrefs.getString("role", "entry") ?: "entry"
            val next = if (current == "entry") "exit" else "entry"
            rolePrefs.edit().putString("role", next).commit()
            addDebugLog("Role saved: $next")
            updateRoleBtn()
            showPage(PageRouter.initialPageForRole(next))
        }

        // ADB remote access — server IP config
        val etServerIp = debugView!!.findViewById<EditText>(R.id.etServerIp)
        val btnSaveIp = debugView!!.findViewById<Button>(R.id.btnSaveIp)
        val tvAdbStatus = debugView!!.findViewById<TextView>(R.id.tvAdbStatus)

        fun startAdbStatusPolling() {
            adbStatusJob?.cancel()
            adbStatusJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val enabled = AdbRemoteHelper.isAdbEnabled()
                    val ip = AdbRemoteHelper.getSavedIp(this@OverlayService)
                    withContext(Dispatchers.Main) {
                        tvAdbStatus.text = if (enabled && ip.isNotEmpty()) "ADB:$ip" else "ADB:OFF"
                        tvAdbStatus.setTextColor(Color.parseColor(if (enabled) "#22C55E" else "#FF5555"))
                    }
                    delay(5000)
                }
            }
        }
        etServerIp.setText(AdbRemoteHelper.getSavedIp(this))
        startAdbStatusPolling()

        btnSaveIp.setOnClickListener {
            val ip = etServerIp.text.toString().trim()
            if (ip.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"))) {
                AdbRemoteHelper.saveIp(this, ip)
                addDebugLog("ADB IP saved: $ip")
                scope.launch(Dispatchers.IO) {
                    val logs = AdbRemoteHelper.persistAdbPort()
                    AdbRemoteHelper.enableAdbWithFirewall(ip)
                    withContext(Dispatchers.Main) { logs.forEach { addDebugLog(it) } }
                }
            } else {
                etServerIp.setError("Invalid IP")
                addDebugLog("ADB IP save failed: invalid IP \"$ip\"")
            }
        }

        // Payment config
        val etApiUrl = debugView!!.findViewById<EditText>(R.id.etApiUrl)
        val etApiKey = debugView!!.findViewById<EditText>(R.id.etApiKey)
        val etPollInterval = debugView!!.findViewById<EditText>(R.id.etPollInterval)
        val btnPaymentToggle = debugView!!.findViewById<Button>(R.id.btnPaymentToggle)
        val btnSavePayment = debugView!!.findViewById<Button>(R.id.btnSavePayment)
        val tvPaymentConfigStatus = debugView!!.findViewById<TextView>(R.id.tvPaymentConfigStatus)

        fun updatePaymentStatus() {
            val cfg = PaymentConfig.load(this)
            tvPaymentConfigStatus.text = if (cfg.enabled) "PAY:ON" else "PAY:OFF"
            tvPaymentConfigStatus.setTextColor(Color.parseColor(if (cfg.enabled) "#22C55E" else "#FF5555"))
            btnPaymentToggle.text = if (cfg.enabled) "ON" else "OFF"
            btnPaymentToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor(if (cfg.enabled) "#388E3C" else "#666666")
            )
        }

        val paymentCfg = PaymentConfig.load(this)
        etApiUrl.setText(paymentCfg.baseUrl)
        etApiKey.setText(paymentCfg.apiKey)
        etPollInterval.setText(paymentCfg.pollIntervalMs.toString())
        updatePaymentStatus()

        btnPaymentToggle.setOnClickListener {
            val cfg = PaymentConfig.load(this)
            PaymentConfig.save(this, cfg.copy(enabled = !cfg.enabled))
            updatePaymentStatus()
        }

        btnSavePayment.setOnClickListener {
            val poll = etPollInterval.text.toString().toLongOrNull() ?: 10_000L
            assert(poll > 0) { "poll interval must be positive" }
            val cfg = PaymentConfig(
                baseUrl = etApiUrl.text.toString().trim(),
                apiKey = etApiKey.text.toString().trim(),
                pollIntervalMs = if (poll > 0) poll else 10_000L,
                enabled = PaymentConfig.load(this).enabled
            )
            PaymentConfig.save(this, cfg)
            addDebugLog("Payment saved: url=${cfg.baseUrl}, poll=${cfg.pollIntervalMs}ms, enabled=${cfg.enabled}")
            updatePaymentStatus()
            setupPaymentManager()
        }

        debugView!!.findViewById<Button>(R.id.btnClose).setOnClickListener {
            stopSelf()
        }

        debugView!!.findViewById<Button>(R.id.btnPageIdle).setOnClickListener {
            showPage(Page.IDLE)
        }

        debugView!!.findViewById<Button>(R.id.btnPageTransaction).setOnClickListener {
            showPage(Page.TRANSACTION)
        }

        debugView!!.findViewById<Button>(R.id.btnPageExitIdle).setOnClickListener {
            showPage(Page.EXIT_IDLE)
        }

        debugView!!.findViewById<Button>(R.id.btnPageExitTxn).setOnClickListener {
            if (PaymentConfig.load(this).enabled) {
                exitTransactionPaymentView!!.clearPayment()
                exitTransactionPaymentView!!.setPlate("CB12345")
                exitTransactionPaymentView!!.setParkingTime("02:35:12")
                exitTransactionPaymentView!!.setPayAmount("$4.00")
                exitTransactionPaymentView!!.setStatusLabel("EXITING", Color.parseColor("#E8A000"))
                val demoBitmap = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.demo_payway)
                if (demoBitmap != null) exitTransactionPaymentView!!.setQrBitmap(demoBitmap)
                exitTransactionPaymentView!!.setPaymentStatus("Awaiting Payment...", Color.parseColor("#E8A000"))
                exitTransactionPaymentView!!.setPaymentTimer("Expires in 04:32")
            } else {
                exitTransactionView!!.setPlate("CB12345")
                exitTransactionView!!.setParkingTime("02:35:12")
                exitTransactionView!!.setPayAmount("$4.00")
                exitTransactionView!!.setStatusLabel("EXITING", Color.parseColor("#E8A000"))
            }
            showPage(Page.EXIT_TRANSACTION)
        }

        debugView!!.findViewById<Button>(R.id.btnPagePaid).setOnClickListener {
            val green = Color.parseColor("#22C55E")
            exitTransactionPaymentView!!.setPlate("CB12345")
            exitTransactionPaymentView!!.setParkingTime("02:35:12")
            exitTransactionPaymentView!!.setPayAmount("$4.00")
            exitTransactionPaymentView!!.clearPayment()
            exitTransactionPaymentView!!.showCheckmark()
            exitTransactionPaymentView!!.setStatusLabel("EXITING", green)
            exitTransactionPaymentView!!.setPayLabel("PAID")
            exitTransactionPaymentView!!.setPayAmountColor(green)
            exitTransactionPaymentView!!.setPaymentStatus("Processing...", green)
            exitTransactionPaymentView!!.setPaymentTimer("")
            showPage(Page.EXIT_TRANSACTION)
        }

        debugView!!.findViewById<Button>(R.id.btnPageComplete).setOnClickListener {
            completedExitView!!.resetThankYou()
            showPage(Page.COMPLETED_EXIT)
        }

        debugView!!.findViewById<Button>(R.id.btnPagePaidComplete).setOnClickListener {
            completedExitView!!.setPlate("CB12345")
            completedExitView!!.setTypeBadge("TEMPORARY")
            completedExitView!!.setExitDate("03/04/26 14:32:05")
            completedExitView!!.setPaymentConfirmed("Payment Confirmed, Paid via QR")
            showPage(Page.COMPLETED_EXIT)
        }

        debugView!!.findViewById<Button>(R.id.btnPageExpired).setOnClickListener {
            val red = Color.parseColor("#FF5555")
            exitTransactionPaymentView!!.clearPayment()
            exitTransactionPaymentView!!.setPlate("CB12345")
            exitTransactionPaymentView!!.setParkingTime("02:35:12")
            exitTransactionPaymentView!!.setPayAmount("$4.00")
            exitTransactionPaymentView!!.setStatusLabel("EXPIRED", red)
            exitTransactionPaymentView!!.showExpiredIcon()
            exitTransactionPaymentView!!.setPaymentStatus("Payment Expired", red)
            exitTransactionPaymentView!!.setPaymentTimer("Please scan ticket again")
            showPage(Page.EXIT_TRANSACTION)
        }

        debugView!!.findViewById<Button>(R.id.btnPageError).setOnClickListener {
            val red = Color.parseColor("#FF5555")
            exitTransactionPaymentView!!.clearPayment()
            exitTransactionPaymentView!!.setPlate("CB12345678901234")
            exitTransactionPaymentView!!.setParkingTime("02:35:12")
            exitTransactionPaymentView!!.setPayAmount("$4.00")
            exitTransactionPaymentView!!.setStatusLabel("ERROR", red)
            exitTransactionPaymentView!!.showErrorIcon()
            exitTransactionPaymentView!!.setPaymentStatus("Network error, please try again", red)
            exitTransactionPaymentView!!.setPaymentTimer("Please scan ticket again")
            exitTransactionPaymentView!!.setPaymentTimerColor(Color.parseColor("#888888"))
            showPage(Page.EXIT_TRANSACTION)
        }

        debugView!!.findViewById<Button>(R.id.btnPageGateFail).setOnClickListener {
            val green = Color.parseColor("#22C55E")
            val yellow = Color.parseColor("#E8A000")
            exitTransactionPaymentView!!.clearPayment()
            exitTransactionPaymentView!!.showWarningIcon()
            exitTransactionPaymentView!!.setPlate("CB12345")
            exitTransactionPaymentView!!.setParkingTime("02:35:12")
            exitTransactionPaymentView!!.setPayAmount("$4.00")
            exitTransactionPaymentView!!.setStatusLabel("EXITING", green)
            exitTransactionPaymentView!!.setPayLabel("PAID")
            exitTransactionPaymentView!!.setPayAmountColor(green)
            exitTransactionPaymentView!!.setPaymentStatus("Payment confirmed, gate not responding.", yellow)
            exitTransactionPaymentView!!.setPaymentTimer("")
            exitTransactionPaymentView!!.setHelpText("Please contact parking staff.", Color.parseColor("#FF5555"))
            showPage(Page.EXIT_TRANSACTION)
        }

        updateDebugVisibility(tvEmpty, recycler)
    }

    fun showPage(page: Page) {
        assert(contentContainer != null) { "contentContainer null" }

        if (page == Page.DEBUG) {
            // Debug takes over entire root — hide persistent chrome
            assert(debugView != null) { "debugView null" }
            pendingPage = currentPage
            val root = rootView as FrameLayout
            root.removeAllViews()
            root.addView(debugView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            currentPage = page
            return
        }

        // Restore persistent root if coming from debug
        if (currentPage == Page.DEBUG) {
            adbStatusJob?.cancel()
            val root = rootView as FrameLayout
            root.removeAllViews()
            val inflater = LayoutInflater.from(this)
            val fresh = inflater.inflate(R.layout.overlay_root, root, false)
            // Copy children from fresh into root
            val freshFrame = fresh as FrameLayout
            while (freshFrame.childCount > 0) {
                val child = freshFrame.getChildAt(0)
                freshFrame.removeViewAt(0)
                root.addView(child)
            }
            contentContainer = root.findViewById(R.id.contentContainer)
            barHelper = OverlayBarHelper(root)
            barHelper!!.onDebugRequested = { showPage(Page.DEBUG) }
            barHelper!!.startClock(scope)
            barHelper!!.setCallActive(callActive)
            FontHelper.applyFonts(this, root)
        }

        val targetView = when (page) {
            Page.IDLE -> {
                assert(idleView != null) { "idleView null" }
                idleView!!.view
            }
            Page.EXIT_IDLE -> {
                assert(exitIdleView != null) { "exitIdleView null" }
                exitIdleView!!.view
            }
            Page.TRANSACTION -> {
                assert(transactionView != null) { "transactionView null" }
                transactionView!!.view
            }
            Page.EXIT_TRANSACTION -> {
                if (PaymentConfig.load(this).enabled) {
                    assert(exitTransactionPaymentView != null) { "exitTransactionPaymentView null" }
                    exitTransactionPaymentView!!.view
                } else {
                    assert(exitTransactionView != null) { "exitTransactionView null" }
                    exitTransactionView!!.view
                }
            }
            Page.COMPLETED_EXIT -> {
                assert(completedExitView != null) { "completedExitView null" }
                completedExitView!!.view
            }
            Page.DEBUG -> throw IllegalStateException("handled above")
        }

        // Detach from previous parent if needed
        (targetView.parent as? ViewGroup)?.removeView(targetView)

        contentContainer!!.removeAllViews()
        contentContainer!!.addView(
            targetView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Hide site QR card on payment exit transaction (has its own QR)
        val hideQr = page == Page.EXIT_TRANSACTION && PaymentConfig.load(this).enabled
        rootView?.findViewById<View>(R.id.qrCard)?.visibility =
            if (hideQr) View.GONE else View.VISIBLE

        currentPage = page
        paymentManager?.onPageChanged(page)
    }

    @Suppress("DEPRECATION")
    private fun monitorPaap() {
        scope.launch {
            while (isActive) {
                val paapResumed = try {
                    val proc = Runtime.getRuntime().exec(arrayOf("su", "-c",
                        "dumpsys activity activities | grep mResumedActivity"))
                    val output = proc.inputStream.bufferedReader().readText()
                    proc.waitFor()
                    PageRouter.isPaapResumed(output)
                } catch (e: Exception) {
                    Log.e("OverlayService", "dumpsys failed", e)
                    true
                }
                Log.d("OverlayService", "PAAP resumed: $paapResumed")
                if (currentPage == Page.DEBUG) {
                    val warning = debugView?.findViewById<TextView>(R.id.tvPaapWarning)
                    warning?.visibility = if (paapResumed) View.GONE else View.VISIBLE
                }
                delay(5000)
            }
        }
    }

    private fun ensureAdbOnStartup() {
        scope.launch(Dispatchers.IO) {
            val logs = AdbRemoteHelper.ensureAdbConfigured(this@OverlayService)
            withContext(Dispatchers.Main) {
                logs.forEach { addDebugLog(it) }
            }
        }
    }

    private fun addDebugLog(message: String) {
        events.add(0, PaapEvent.DebugLog(message))
        if (events.size > 200) events.subList(200, events.size).clear()
        adapter.notifyDataSetChanged()
    }

    private fun collectEvents() {
        scope.launch {
            LogcatReaderService.events.collect { event ->
                events.add(0, event)
                if (events.size > 200) events.subList(200, events.size).clear()
                adapter.notifyDataSetChanged()
                if (event is PaapEvent.DisplayUpdate) handleDisplayUpdate(event)
                if (event is PaapEvent.LinphoneCall) {
                    callActive = PageRouter.isCallActive(event.toState)
                    barHelper?.setCallActive(callActive)
                }
                if (currentPage == Page.DEBUG) {
                    val recycler = debugView?.findViewById<RecyclerView>(R.id.recyclerEvents)
                    val tvEmpty = debugView?.findViewById<TextView>(R.id.tvEmpty)
                    recycler?.scrollToPosition(0)
                    if (tvEmpty != null && recycler != null) {
                        updateDebugVisibility(tvEmpty, recycler)
                    }
                }
            }
        }
    }

    private fun handleDisplayUpdate(event: PaapEvent.DisplayUpdate) {
        assert(transactionView != null) { "transactionView null in handleDisplayUpdate" }
        assert(exitTransactionView != null) { "exitTransactionView null in handleDisplayUpdate" }
        assert(completedExitView != null) { "completedExitView null in handleDisplayUpdate" }
        val inDebug = currentPage == Page.DEBUG

        val text1 = event.texts["text1"]?.text ?: return
        val text3 = event.texts["text3"]?.text ?: ""
        val text4 = event.texts["text4"]?.text ?: ""
        Log.e("OverlayService", "DisplayUpdate text1=$text1 text3=$text3 text4=$text4")

        val role = getSharedPreferences("box_state", MODE_PRIVATE)
            .getString("role", "entry") ?: "entry"

        val page = PageRouter.decidePage(text1, text3, text4, role) ?: return

        // Apply side effects based on decided page
        when (page) {
            Page.TRANSACTION -> {
                transactionView!!.setPlate(text1)
                if (text3.isNotEmpty()) transactionView!!.setTypeBadge(text3.uppercase())
                if (text4.isNotEmpty()) transactionView!!.setEntryDate(text4)
                transactionView!!.setStatusLabel("WELCOME", Color.parseColor("#010062"))
            }
            Page.EXIT_TRANSACTION -> {
                val plate = text1
                val time = text3.substringAfter(":").trim()
                val amount = text4.substringAfter(":").trim()
                val statusColor = Color.parseColor("#E8A000")
                exitTransactionView!!.setPlate(plate)
                exitTransactionView!!.setParkingTime(time)
                exitTransactionView!!.setPayAmount(amount)
                exitTransactionView!!.setStatusLabel("EXITING", statusColor)
                exitTransactionPaymentView!!.setPlate(plate)
                exitTransactionPaymentView!!.setParkingTime(time)
                exitTransactionPaymentView!!.setPayAmount(amount)
                exitTransactionPaymentView!!.setStatusLabel("EXITING", statusColor)
                currentCardNo = text1
                if (PaymentConfig.load(this).isReady()) {
                    paymentManager?.startPayment(text1)
                }
            }
            Page.COMPLETED_EXIT -> {
                completedExitView!!.setPlate(text1)
                completedExitView!!.setTypeBadge(text3.uppercase())
                completedExitView!!.setExitDate(text4)
                val pmState = paymentManager?.state?.value
                val paymentWasUsed = pmState is PaymentState.Confirmed ||
                    pmState is PaymentState.AwaitingPayment
                if (paymentWasUsed) {
                    completedExitView!!.setPaymentConfirmed("Payment Confirmed, Paid via QR")
                } else {
                    completedExitView!!.resetThankYou()
                }
                gateTimeoutJob?.cancel()
                paymentManager?.destroy()
                setupPaymentManager()
            }
            else -> { /* IDLE, EXIT_IDLE — no view updates needed */ }
        }

        if (inDebug) {
            pendingPage = page
        } else {
            showPage(page)
        }
    }

    private fun restoreRole() {
        val role = getSharedPreferences("box_state", MODE_PRIVATE)
            .getString("role", "entry") ?: "entry"
        addDebugLog("Role restored: $role")
        showPage(PageRouter.initialPageForRole(role))
    }

    private fun setupPaymentManager() {
        paymentManager?.destroy()
        paymentManager = null
        val config = PaymentConfig.load(this)
        if (!config.isReady()) return
        addDebugLog("Payment init: url=${config.baseUrl}, poll=${config.pollIntervalMs}ms")
        val apiClient = PaymentApiClient(config.baseUrl, config.apiKey)
        val mgr = PaymentManager(apiClient, config.pollIntervalMs, scope)
        paymentManager = mgr
        scope.launch {
            mgr.state.collect { state ->
                if (state !is PaymentState.Idle) addDebugLog("Payment: $state")
                updatePaymentUI(state)
            }
        }
    }

    private fun updatePaymentUI(state: PaymentState) {
        assert(exitTransactionPaymentView != null) { "paymentView null" }
        val view = exitTransactionPaymentView ?: return
        countdownJob?.cancel()
        if (state !is PaymentState.Confirmed) gateTimeoutJob?.cancel()
        when (state) {
            is PaymentState.Initiating -> {
                view.setPaymentStatus("Connecting...", Color.parseColor("#E8A000"))
                view.setPaymentTimer("")
            }
            is PaymentState.AwaitingPayment -> {
                if (state.currency.isNotEmpty()) {
                    val current = view.getPayAmount()
                    if (!current.startsWith(state.currency)) {
                        view.setPayAmount("${state.currency} $current")
                    }
                }
                view.setQrBitmap(state.qrBitmap)
                view.setPaymentStatus("Awaiting Payment...", Color.parseColor("#E8A000"))
                countdownJob = scope.launch {
                    while (isActive) {
                        val remaining = state.expiresAtUnix - (System.currentTimeMillis() / 1000)
                        if (remaining <= 0) break
                        val mm = remaining / 60
                        val ss = remaining % 60
                        view.setPaymentTimer("Expires in %02d:%02d".format(mm, ss))
                        delay(1000)
                    }
                }
            }
            is PaymentState.Confirmed -> {
                val green = Color.parseColor("#22C55E")
                view.clearPayment()
                view.showCheckmark()
                view.setPaymentStatus("Processing...", green)
                view.setPaymentTimer("")
                view.setStatusLabel("EXITING", green)
                view.setPayLabel("PAID")
                view.setPayAmountColor(green)
                gateTimeoutJob?.cancel()
                gateTimeoutJob = scope.launch {
                    delay(20_000)
                    val yellow = Color.parseColor("#E8A000")
                    view.showWarningIcon()
                    view.setPaymentStatus("Payment confirmed, gate not responding.", yellow)
                    view.setPaymentTimer("")
                    view.setHelpText(
                        "Please contact parking staff.",
                        Color.parseColor("#FF5555")
                    )
                }
            }
            is PaymentState.Expired -> {
                val red = Color.parseColor("#FF5555")
                view.clearPayment()
                view.showExpiredIcon()
                view.setStatusLabel("EXPIRED", red)
                view.setPaymentStatus("Payment Expired", red)
                view.setPaymentTimer("Please scan ticket again")
            }
            is PaymentState.Error -> {
                val red = Color.parseColor("#FF5555")
                view.clearPayment()
                view.showErrorIcon()
                view.setStatusLabel("ERROR", red)
                view.setPaymentStatus(state.message, red)
                view.setPaymentTimer("Please scan ticket again")
                view.setPaymentTimerColor(Color.parseColor("#888888"))
            }
            is PaymentState.FeatureUnavailable -> {
                view.setPaymentStatus("Online payment unavailable", Color.parseColor("#888888"))
                view.clearPayment()
            }
            is PaymentState.Idle -> view.clearPayment()
            is PaymentState.NotConfigured -> view.clearPayment()
        }
    }

    private fun updateDebugVisibility(tvEmpty: TextView, recycler: RecyclerView) {
        if (events.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }

    private fun injectTestEvents() {
        val testLines = listOf(
            """E/PRETTY_LOGGER: UdpManager:handleUdpReadData data = {"Vehicle Sensing":"car comming"}""",
            """E/PRETTY_LOGGER: UdpManager:handleUdpReadData data = {"command":"Print","title":"Parking System","titleSize":40,"content":"CB12345","contentSize":30,"QRcode":"https://site.com/car1/123"}""",
            """E/PRETTY_LOGGER: UdpWriterManager:send data = {"PushButton":"PushButton"}""",
            """E/PRETTY_LOGGER: UdpManager:handleUdpReadData data = {"command":"openDoor","delay":1000,"io":0}""",
            """E/PRETTY_LOGGER: UdpManager:handleUdpReadData data = {"text1":{"text1Text":"CB12345","text1Color":"#00FF00","text1Size":50,"text1Gravity":"LEFT"},"text2":{"text2Text":"Temporary","text2Color":"#00FF00","text2Size":40,"text2Gravity":"CENTER"}}""",
            """E/PRETTY_LOGGER: UdpManager:handleUdpReadData data = {"command":"speakOut","speakText":"Welcome to park Please take the ticket","language":"ENGLISH","speechRate":"1.5"}""",
            """E/PRETTY_LOGGER: UdpWriterManager:send data = {"heartbeat":"heartbeat"}""",
            """E/PRETTY_LOGGER: UdpManager:handleUdpReadData data = {"command":"OnLine"}""",
        )
        testLines.forEach { LogcatReaderService.injectLine(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        paymentManager?.destroy()
        countdownJob?.cancel()
        gateTimeoutJob?.cancel()
        scope.cancel()
        if (rootView != null) {
            windowManager?.removeView(rootView)
            rootView = null
        }
    }

    private fun loadEventHistory() {
        val logDir = java.io.File(filesDir, "logs")
        if (!logDir.exists()) return
        val log = EventLog(logDir)
        val history = log.loadToday()
        if (history.isNotEmpty()) {
            events.addAll(history.reversed())
        }
    }
}
