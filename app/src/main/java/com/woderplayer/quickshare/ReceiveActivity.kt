package com.woderplayer.quickshare

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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

        btnBack.setOnClickListener { finish() }

        generateAndDisplayQR()
    }

    override fun onResume() {
        super.onResume()
        NetworkRequirements.ensureReceiverRequirements(this) {
            // Requirements satisfied
        }
    }

    private fun generateAndDisplayQR() {
        try {
            val ip = NetworkUtils.getLocalIpAddress(this) ?: "127.0.0.1"
            val port = 8080
            val qrContent = "msender://$ip:$port"

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
            tvStatus.text = "Scan QR to send files to this device"
        } catch (e: Exception) {
            tvStatus.text = "Failed to generate QR: ${e.message}"
        }
    }
}
