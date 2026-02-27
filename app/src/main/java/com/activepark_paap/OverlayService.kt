package com.activepark_paap

import android.app.AlarmManager
import android.app.PendingIntent
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
import com.activepark_paap.ui.entry.EntryIdleView
import com.activepark_paap.ui.entry.EntryTransactionView
import com.activepark_paap.ui.exit.CompletedExitView
import com.activepark_paap.ui.exit.ExitTransactionView
import kotlinx.coroutines.*

class OverlayService : Service() {

    enum class Page { IDLE, EXIT_IDLE, TRANSACTION, EXIT_TRANSACTION, COMPLETED_EXIT, DEBUG }

    private var windowManager: WindowManager? = null
    private var rootContainer: FrameLayout? = null
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        loadEventHistory()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
        collectEvents()
        monitorPaap()
    }

    @Suppress("DEPRECATION")
    private fun createOverlay() {
        rootContainer = FrameLayout(this)

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
            windowManager!!.addView(rootContainer, params)
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to add overlay", e)
            stopSelf()
            return
        }
    }

    private fun setupIdleView() {
        idleView = EntryIdleView(this)
        idleView!!.startClock(scope)
        idleView!!.onDebugRequested = { showPage(Page.DEBUG) }
    }

    private fun setupExitIdleView() {
        exitIdleView = EntryIdleView(this)
        exitIdleView!!.startClock(scope)
        exitIdleView!!.setMode(isExit = true)
        exitIdleView!!.onDebugRequested = { showPage(Page.DEBUG) }
    }

    private fun setupTransactionView() {
        transactionView = EntryTransactionView(this)
        transactionView!!.startClock(scope)
        transactionView!!.onDebugRequested = { showPage(Page.DEBUG) }
        // Sample data
        transactionView!!.setPlate("CB12345")
        transactionView!!.setTypeBadge("TEMPORARY")
        transactionView!!.setEntryDate("27-02-2026")
        transactionView!!.setStatusLabel("WELCOME", Color.parseColor("#010062"))
    }

    private fun setupExitTransactionView() {
        exitTransactionView = ExitTransactionView(this)
        exitTransactionView!!.startClock(scope)
        exitTransactionView!!.onDebugRequested = { showPage(Page.DEBUG) }
        // Sample data
        exitTransactionView!!.setPlate("CB12345")
        exitTransactionView!!.setParkingTime("02:35:12")
        exitTransactionView!!.setPayAmount("$4.00")
        exitTransactionView!!.setStatusLabel("EXITING", Color.parseColor("#E8A000"))
    }

    private fun setupCompletedExitView() {
        completedExitView = CompletedExitView(this)
        completedExitView!!.startClock(scope)
        completedExitView!!.onDebugRequested = { showPage(Page.DEBUG) }
        completedExitView!!.setPlate("CB12345")
        completedExitView!!.setTypeBadge("TEMPORARY")
        completedExitView!!.setExitDate("02/27/26 14:32:05")
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
        assert(rootContainer != null) { "rootContainer null" }
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
            Page.DEBUG -> {
                assert(debugView != null) { "debugView null" }
                debugView!!
            }
        }
        rootContainer!!.removeAllViews()
        rootContainer!!.addView(
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
        scheduleRestart()
        scope.cancel()
        if (rootContainer != null) {
            windowManager?.removeView(rootContainer)
            rootContainer = null
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

    private fun scheduleRestart() {
        val restartIntent = Intent(this, BootReceiver::class.java).apply {
            action = BootReceiver.ACTION_RESTART
        }
        val pending = PendingIntent.getBroadcast(
            this, 0, restartIntent, 0
        )
        val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 8000, pending)
        Log.e("OverlayService", "Restart scheduled in 3s")
    }
}
