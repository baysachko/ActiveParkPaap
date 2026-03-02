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
import kotlinx.coroutines.*

class OverlayService : Service() {

    enum class Page { IDLE, EXIT_IDLE, TRANSACTION, EXIT_TRANSACTION, COMPLETED_EXIT, DEBUG }

    companion object {
        private val ACTIVE_CALL_STATES = setOf(
            "CallIncomingReceived", "CallConnected", "CallStreamsRunning",
            "CallOutgoingInit", "CallOutgoingProgress", "CallOutgoingRinging"
        )
    }

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
    private var completedExitView: CompletedExitView? = null
    private var debugView: View? = null
    private var currentPage = Page.IDLE
    private var callActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        loadEventHistory()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
        collectEvents()
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
            showPage(if (currentPage == Page.DEBUG) Page.IDLE else currentPage)
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
            guardPrefs.edit().putBoolean("paused", nowPaused).apply()
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
            rolePrefs.edit().putString("role", next).apply()
            updateRoleBtn()
            showPage(if (next == "exit") Page.EXIT_IDLE else Page.IDLE)
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
            showPage(Page.EXIT_TRANSACTION)
        }

        debugView!!.findViewById<Button>(R.id.btnPageComplete).setOnClickListener {
            showPage(Page.COMPLETED_EXIT)
        }

        updateDebugVisibility(tvEmpty, recycler)
    }

    fun showPage(page: Page) {
        assert(contentContainer != null) { "contentContainer null" }

        if (page == Page.DEBUG) {
            // Debug takes over entire root — hide persistent chrome
            assert(debugView != null) { "debugView null" }
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
                assert(exitTransactionView != null) { "exitTransactionView null" }
                exitTransactionView!!.view
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
        currentPage = page
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
                    output.contains("com.anziot.park")
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

    private fun collectEvents() {
        scope.launch {
            LogcatReaderService.events.collect { event ->
                events.add(0, event)
                if (events.size > 200) events.subList(200, events.size).clear()
                adapter.notifyDataSetChanged()
                if (event is PaapEvent.DisplayUpdate) handleDisplayUpdate(event)
                if (event is PaapEvent.LinphoneCall) {
                    callActive = event.toState in ACTIVE_CALL_STATES
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
        if (currentPage == Page.DEBUG) return

        val text1 = event.texts["text1"]?.text ?: return
        val text3 = event.texts["text3"]?.text ?: ""
        val text4 = event.texts["text4"]?.text ?: ""
        Log.e("OverlayService", "DisplayUpdate text1=$text1 text3=$text3 text4=$text4")

        val role = getSharedPreferences("box_state", MODE_PRIVATE)
            .getString("role", "entry") ?: "entry"

        val page = if (role == "entry") {
            when {
                text1.contains("Welcome", ignoreCase = true) -> Page.IDLE
                else -> {
                    transactionView!!.setPlate(text1)
                    if (text3.isNotEmpty()) transactionView!!.setTypeBadge(text3.uppercase())
                    if (text4.isNotEmpty()) transactionView!!.setEntryDate(text4)
                    transactionView!!.setStatusLabel("WELCOME", Color.parseColor("#010062"))
                    Page.TRANSACTION
                }
            }
        } else {
            when {
                text1.contains("GoodBye", ignoreCase = true) -> Page.EXIT_IDLE
                text3.contains("Parking time", ignoreCase = true) -> {
                    exitTransactionView!!.setPlate(text1)
                    exitTransactionView!!.setParkingTime(text3.substringAfter(":").trim())
                    exitTransactionView!!.setPayAmount(text4.substringAfter(":").trim())
                    exitTransactionView!!.setStatusLabel("EXITING", Color.parseColor("#E8A000"))
                    Page.EXIT_TRANSACTION
                }
                else -> {
                    completedExitView!!.setPlate(text1)
                    completedExitView!!.setTypeBadge(text3.uppercase())
                    completedExitView!!.setExitDate(text4)
                    Page.COMPLETED_EXIT
                }
            }
        }

        showPage(page)
    }

    private fun restoreRole() {
        val role = getSharedPreferences("box_state", MODE_PRIVATE)
            .getString("role", "entry") ?: "entry"
        if (role == "exit") showPage(Page.EXIT_IDLE)
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
