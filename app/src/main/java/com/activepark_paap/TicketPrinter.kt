package com.activepark_paap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import com.caysn.autoreplyprint.AutoReplyPrint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.sun.jna.Pointer

/**
 * Direct USB thermal printer access via AutoReplyPrint library.
 * Used when "Enable New Print" is ON in PAAP config — PAAP skips printing,
 * our app takes over with custom ticket layout.
 */
class TicketPrinter(private val context: Context) {

    companion object {
        private const val TAG = "TicketPrinter"
        private const val PRINTER_VID = 0x0FE6
        private const val PRINTER_PID = 0x811E
        private const val BITMAP_WIDTH = 550
        private const val STATUS_TIMEOUT_MS = 5000
        private const val GREY = 0xFF666666.toInt()
        private const val DIVIDER_COLOR = 0xFFCCCCCC.toInt()
        private const val CREDIT_GREY = 0xFF999999.toInt()
    }

    fun printTicket(
        title: String,
        ticketNo: String,
        entryDate: String,
        footer1: String,
        footer2: String,
        qrUrl: String
    ): PrintResult {
        assert(ticketNo.isNotEmpty()) { "ticketNo must not be empty" }
        assert(qrUrl.isNotEmpty()) { "qrUrl must not be empty" }

        val usbDevice = findPrinterDevice() ?: return PrintResult.Error("Printer not found")
        if (!hasUsbPermission(usbDevice)) return PrintResult.Error("No USB permission")

        val handle = openDevice(usbDevice)
        if (handle == Pointer.NULL) return PrintResult.Error("Failed to open printer")

        try {
            val statusOk = checkStatus(handle)
            if (!statusOk) {
                AutoReplyPrint.INSTANCE.CP_Port_Close(handle)
                return PrintResult.Error("Printer error — check paper/cover")
            }

            val bitmap = renderTicketBitmap(title, ticketNo, entryDate, footer1, footer2, qrUrl)
            AutoReplyPrint.INSTANCE.CP_Pos_SetAlignment(handle, 1) // center
            AutoReplyPrint.CP_Pos_PrintRasterImageFromData_Helper
                .PrintRasterImageFromBitmap(
                    handle, bitmap.width, bitmap.height, bitmap,
                    AutoReplyPrint.CP_ImageBinarizationMethod_Thresholding,
                    AutoReplyPrint.CP_ImageCompressionMethod_None
                )
            AutoReplyPrint.INSTANCE.CP_Pos_FeedAndHalfCutPaper(handle)
            Log.e(TAG, "Printed: $ticketNo")
            return PrintResult.Success
        } finally {
            AutoReplyPrint.INSTANCE.CP_Port_Close(handle)
        }
    }

    // Pencil design: 550px, padding [30,40], gap 8, all text 18pt Space Grotesk medium black.
    // Scale factor S applied for 203 DPI thermal printer.
    private fun renderTicketBitmap(
        title: String,
        ticketNo: String,
        entryDate: String,
        footer1: String,
        footer2: String,
        qrUrl: String
    ): Bitmap {
        assert(ticketNo.isNotEmpty()) { "ticketNo required" }
        assert(qrUrl.isNotEmpty()) { "qrUrl required" }

        val S = 1.6f
        val padTop = 1 * S
        val padSide = 40 * S
        val gap = 8 * S
        val textSize = 18 * S
        val qrSize = (200 * S).toInt()
        val dividerW = 530 * S
        val dividerLeft = (BITMAP_WIDTH - dividerW) / 2f
        val font = ResourcesCompat.getFont(context, R.font.space_grotesk_regular)

        val cx = BITMAP_WIDTH / 2f
        val paint = makePaint(Color.BLACK, textSize, font)

        // Measure pass
        var y = padTop + textSize // title
        y += gap + 2f            // divider
        y += gap + textSize      // ticket no row
        y += gap + textSize      // entry date row
        y += gap + 2f            // divider
        y += gap + qrSize        // QR
        y += gap + textSize      // "Do not lost this ticket"
        y += gap + textSize      // "Lost not compensation"
        y += gap + 2f            // divider
        y += gap + textSize      // credit
        y += 30 * S              // bottom padding
        val height = y.toInt()

        val bitmap = Bitmap.createBitmap(BITMAP_WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        // Render
        y = padTop + textSize
        canvas.drawText(title, cx, y, paint)
        y += gap
        drawDivider(canvas, dividerLeft, dividerW, y)
        y += 2f + gap

        y += textSize
        canvas.drawText("Ticket No.  $ticketNo", cx, y, paint)
        y += gap + textSize
        canvas.drawText("Entry Date  $entryDate", cx, y, paint)
        y += gap
        drawDivider(canvas, dividerLeft, dividerW, y)
        y += 2f + gap

        val qrBitmap = generateQrBitmap(qrUrl, qrSize)
        if (qrBitmap != null) {
            canvas.drawBitmap(qrBitmap, ((BITMAP_WIDTH - qrSize) / 2).toFloat(), y, null)
        }
        y += qrSize + gap

        y += textSize
        canvas.drawText(footer1, cx, y, paint)
        y += gap + textSize
        canvas.drawText(footer2, cx, y, paint)
        y += gap
        drawDivider(canvas, dividerLeft, dividerW, y)
        y += 2f + gap

        y += textSize
        canvas.drawText("Powered by ActivePark Solutions", cx, y, paint)

        return bitmap
    }

    private fun drawDivider(canvas: Canvas, left: Float, width: Float, y: Float) {
        val paint = Paint().apply { color = DIVIDER_COLOR }
        canvas.drawRect(left, y, left + width, y + 2f, paint)
    }

    private fun makePaint(color: Int, size: Float, typeface: Typeface?): Paint {
        return Paint().apply {
            this.color = color
            textSize = size
            textAlign = Paint.Align.CENTER
            this.typeface = typeface
            isAntiAlias = true
        }
    }

    private fun generateQrBitmap(data: String, size: Int): Bitmap? {
        assert(data.isNotEmpty()) { "QR data must not be empty" }
        assert(size > 0) { "QR size must be positive" }
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 1) // minimal quiet zone
            val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size, hints)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "QR generation failed: ${e.message}")
            null
        }
    }

    private fun findPrinterDevice(): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        for (device in usbManager.deviceList.values) {
            if (device.vendorId == PRINTER_VID && device.productId == PRINTER_PID) {
                return device
            }
        }
        return null
    }

    private fun hasUsbPermission(device: UsbDevice): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.hasPermission(device)
    }

    private fun openDevice(device: UsbDevice): Pointer {
        val usbPort = String.format("VID:0x%04X,PID:0x%04X", device.vendorId, device.productId)
        val handle = AutoReplyPrint.INSTANCE.CP_Port_OpenUsb(usbPort, 0)
        Log.e(TAG, if (handle == Pointer.NULL) "OpenPort Failed" else "OpenPort Success")
        return handle
    }

    private fun checkStatus(handle: Pointer): Boolean {
        val status = AutoReplyPrint.INSTANCE.CP_Pos_QueryRTStatus(handle, 30000)
        val s = status.toLong()
        val errors = mutableListOf<String>()
        if (AutoReplyPrint.CP_RTSTATUS_Helper.CP_RTSTATUS_OFFLINE(s)) errors += "OFFLINE"
        if (AutoReplyPrint.CP_RTSTATUS_Helper.CP_RTSTATUS_COVERUP(s)) errors += "COVER_UP"
        if (AutoReplyPrint.CP_RTSTATUS_Helper.CP_RTSTATUS_NOPAPER(s)) errors += "NO_PAPER"
        if (AutoReplyPrint.CP_RTSTATUS_Helper.CP_RTSTATUS_ERROR_OCCURED(s)) errors += "ERROR"
        if (AutoReplyPrint.CP_RTSTATUS_Helper.CP_RTSTATUS_CUTTER_ERROR(s)) errors += "CUTTER"
        if (errors.isNotEmpty()) {
            Log.e(TAG, "Printer errors: $errors")
            return false
        }
        return true
    }

    sealed class PrintResult {
        object Success : PrintResult()
        data class Error(val message: String) : PrintResult()
    }
}
