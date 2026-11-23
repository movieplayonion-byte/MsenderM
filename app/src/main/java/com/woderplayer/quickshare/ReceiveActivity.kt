package com.woderplayer.quickshare

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.woderplayer.quickshare.engine.TransferReceiverService

class ReceiveActivity : AppCompatActivity() {

    private lateinit var ivQrCode: ImageView
    private lateinit var tvStatus: TextView
    private var receiverServiceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receive)

        ivQrCode = findViewById(R.id.ivQrCode)
        tvStatus = findViewById(R.id.tvReceiveStatus)
        val btnBack = findViewById<Button>(R.id.btnBackReceive)

        btnBack.setOnClickListener {
            stopReceiverService()
            HotspotManager.stopHotspot()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        NetworkRequirements.ensureReceiver(this) {
            // The HTTP receiver server must be listening BEFORE the sender can
            // connect and upload — start it first, then bring up the hotspot.
            startReceiverService()
            setupReceiverHotspot()
        }
    }

    override fun onDestroy() {
        stopReceiverService()
        HotspotManager.stopHotspot()
        super.onDestroy()
    }

    private fun startReceiverService() {
        val serviceIntent = Intent(this, TransferReceiverService::class.java).apply {
            action = TransferReceiverService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            receiverServiceStarted = true
        } catch (e: Exception) {
            tvStatus.text = "Receiver Error: ${e.message}"
        }
    }

    private fun stopReceiverService() {
        if (!receiverServiceStarted) return
        receiverServiceStarted = false
        val serviceIntent = Intent(this, TransferReceiverService::class.java).apply {
            action = TransferReceiverService.ACTION_STOP
        }
        try {
            stopService(serviceIntent)
        } catch (_: Exception) {}
    }

    private fun setupReceiverHotspot() {
        tvStatus.text = "Starting clone hotspot..."
        HotspotManager.startHotspot(
            this,
            onSuccess = { ssid, pass, ip ->
                generateQR(ssid, pass, ip)
            },
            onError = { err ->
                tvStatus.text = "Hotspot Error: $err"
            }
        )
    }

    private fun generateQR(ssid: String, pass: String, ip: String) {
        try {
            // Standard Wi-Fi QR only: hotspot login. The sender finds the server
            // IP itself after joining (link properties + /ping handshake).
            val qrContent = "WIFI:T:WPA;S:${escapeWifiField(ssid)};P:${escapeWifiField(pass)};;"

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(qrContent, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }

            ivQrCode.setImageBitmap(bitmap)
            tvStatus.text = "Hotspot: $ssid\nServer: $ip:${TransferReceiverService.PORT}\nScan QR with sender to auto-connect"
        } catch (e: Exception) {
            tvStatus.text = "QR Error: ${e.message}"
        }
    }

    // WIFI QR spec: \ ; , " inside field values must be backslash-escaped.
    private fun escapeWifiField(value: String): String {
        val sb = StringBuilder()
        for (c in value) {
            if (c == '\\' || c == ';' || c == ',' || c == '"') sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }
}
