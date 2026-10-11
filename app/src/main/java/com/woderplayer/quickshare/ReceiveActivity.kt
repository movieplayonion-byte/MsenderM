package com.woderplayer.quickshare

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.woderplayer.quickshare.engine.NetworkUtils
import com.woderplayer.quickshare.engine.TransferReceiverService

class ReceiveActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receive)

        val ivQr = findViewById<ImageView>(R.id.ivFullQrCode)
        val tvIp = findViewById<TextView>(R.id.tvReceiveIp)
        val btnStop = findViewById<Button>(R.id.btnStopReceive)

        val serviceIntent = Intent(this, TransferReceiverService::class.java).apply {
            action = TransferReceiverService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val ip = NetworkUtils.getLocalIp()
        val port = TransferReceiverService.PORT
        val url = "http://$ip:$port"
        tvIp.text = "Receiver IP: $url"

        val bitMatrix = QRCodeWriter().encode("MSENDER|$url", BarcodeFormat.QR_CODE, 512, 512)
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (x in 0 until 512) {
            for (y in 0 until 512) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        ivQr.setImageBitmap(bmp)

        btnStop.setOnClickListener {
            val stopIntent = Intent(this, TransferReceiverService::class.java).apply {
                action = TransferReceiverService.ACTION_STOP
            }
            stopService(stopIntent)
            finish()
        }
    }
}
