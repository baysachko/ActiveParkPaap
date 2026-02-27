package com.activepark_paap

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startService(Intent(this, LogcatReaderService::class.java))

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            // On API 23+ emulator, open settings to grant overlay permission
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 1001)
        } else {
            startOverlayAndFinish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this)) {
                startOverlayAndFinish()
            } else {
                finish() // user denied
            }
        }
    }

    private fun startOverlayAndFinish() {
        startService(Intent(this, OverlayService::class.java))
        finish()
    }
}

class EventAdapter(private val events: List<PaapEvent>) :
    RecyclerView.Adapter<EventAdapter.VH>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvDirection: TextView = view.findViewById(R.id.tvDirection)
        val tvSummary: TextView = view.findViewById(R.id.tvSummary)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val event = events[position]
        holder.tvTime.text = timeFormat.format(Date(event.timestamp))

        val dir = event.directionLabel()
        holder.tvDirection.text = if (dir == "INBOUND") "IN " else "OUT"
        holder.tvDirection.setTextColor(
            if (dir == "INBOUND") Color.parseColor("#4CAF50") else Color.parseColor("#2196F3")
        )

        holder.tvSummary.text = event.summary()
        holder.tvSummary.setTextColor(eventColor(event))
    }

    override fun getItemCount() = events.size

    private fun eventColor(event: PaapEvent): Int = when (event) {
        is PaapEvent.GateOpen -> Color.parseColor("#FF9800")
        is PaapEvent.Speak -> Color.parseColor("#9C27B0")
        is PaapEvent.PrintTicket -> Color.parseColor("#E91E63")
        is PaapEvent.DisplayUpdate -> Color.parseColor("#00BCD4")
        is PaapEvent.VehicleSensing -> Color.parseColor("#FFEB3B")
        is PaapEvent.PushButton -> Color.parseColor("#FF5722")
        is PaapEvent.Heartbeat -> Color.parseColor("#607D8B")
        is PaapEvent.OnlineCheck -> Color.parseColor("#607D8B")
        is PaapEvent.Unknown -> Color.parseColor("#9E9E9E")
    }
}
