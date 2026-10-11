package com.woderplayer.quickshare

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

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
    }

    override fun onResume() {
        super.onResume()
        NetworkRequirements.ensureReceiver(this) {
            generateAndDisplayQR()
        }
    }

    private fun getDeviceIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val host = address.hostAddress ?: ""
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.43.1"
    }

    private fun generateAndDisplayQR() {
        try {
            val ip = getDeviceIp()
            val port = 8080
            // OkHttp requires valid http:// scheme
            val qrContent = "http://$ip:$port"

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
            tvStatus.text = "Receiver Ready (IP: $ip)\nScan QR to send files offline"
        } catch (e: Exception) {
            tvStatus.text = "Failed to generate QR: ${e.message}"
        }
    }
}
