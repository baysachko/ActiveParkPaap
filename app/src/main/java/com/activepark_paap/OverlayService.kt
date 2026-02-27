package com.activepark_paap

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.activepark_paap.ui.entry.EntryIdleView
import kotlinx.coroutines.*

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var rootContainer: FrameLayout? = null
    private val events = mutableListOf<PaapEvent>()
    private lateinit var adapter: EventAdapter
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var idleView: EntryIdleView? = null
    private var debugView: View? = null
    private var showingDebug = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
        setupDebugView()
        showIdle()

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
        idleView!!.onDebugRequested = { showDebug() }
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
            showIdle()
        }

        debugView!!.findViewById<Button>(R.id.btnClose).setOnClickListener {
            stopSelf()
        }

        updateDebugVisibility(tvEmpty, recycler)
    }

    private fun showIdle() {
        assert(rootContainer != null) { "rootContainer null" }
        assert(idleView != null) { "idleView null" }
        rootContainer!!.removeAllViews()
        rootContainer!!.addView(
            idleView!!.view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        showingDebug = false
    }

    private fun showDebug() {
        assert(rootContainer != null) { "rootContainer null" }
        assert(debugView != null) { "debugView null" }
        rootContainer!!.removeAllViews()
        rootContainer!!.addView(
            debugView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        showingDebug = true
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
                if (showingDebug) {
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
                if (showingDebug) {
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
        scope.cancel()
        if (rootContainer != null) {
            windowManager?.removeView(rootContainer)
            rootContainer = null
        }
    }
}
