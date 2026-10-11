package com.woderplayer.quickshare

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class ReceiveActivity : AppCompatActivity() {

    private lateinit var ivQrCode: ImageView
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receive)

        ivQrCode = findViewById(R.id.ivQrCode)
        tvStatus = findViewById(R.id.tvReceiveStatus)
        val btnBack = findViewById<Button>(R.id.btnBackReceive)

        btnBack.setOnClickListener {
            HotspotManager.stopHotspot()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        NetworkRequirements.ensureReceiver(this) {
            setupReceiverHotspot()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        HotspotManager.stopHotspot()
    }

    private fun setupReceiverHotspot() {
        tvStatus.text = "Starting clone hotspot..."
        HotspotManager.startHotspot(
            this,
            onSuccess = { ssid, pass, ip ->
                generateQR(ssid, pass, ip)
            },
            onError = { err ->
                tvStatus.text = "Error: $err"
            }
        )
    }

    private fun generateQR(ssid: String, pass: String, ip: String) {
        try {
            // Format: WIFI|SSID|PASSWORD|IP|PORT
            val qrContent = "MSENDER|$ssid|$pass|$ip|8080"

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
            tvStatus.text = "Hotspot: $ssid\nScan with sender to auto-connect"
        } catch (e: Exception) {
            tvStatus.text = "QR Error: ${e.message}"
        }
    }
}
