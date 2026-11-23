package com.woderplayer.quickshare

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.woderplayer.quickshare.engine.TransferReceiverService
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class ReceiveActivity : AppCompatActivity() {

    private lateinit var ivQrCode: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var btnSendFiles: Button
    private var receiverServiceStarted = false

    // Set when leaving via SEND FILES: the hotspot, the receiver service and
    // the session must all survive so the peer can be sent files in return.
    private var keepSessionOnDestroy = false

    // Set once hotspot + server self-test have passed for this screen session,
    // so onResume doesn't tear everything down and restart it on every return.
    private var receiverSessionActive = false
    private var lastSsid: String? = null
    private var lastIp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receive)

        ivQrCode = findViewById(R.id.ivQrCode)
        tvStatus = findViewById(R.id.tvReceiveStatus)
        btnSendFiles = findViewById(R.id.btnSendFiles)
        val btnBack = findViewById<Button>(R.id.btnBackReceive)

        btnBack.setOnClickListener {
            stopReceiverService()
            HotspotManager.stopHotspot()
            finish()
        }

        btnSendFiles.setOnClickListener {
            // Go pick files to send back to the connected peer. The hotspot
            // and the receiver service keep running — the session survives.
            keepSessionOnDestroy = true
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("EXTRA_SEND_TO_PEER", true)
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // The server invokes this (on its own thread) when a peer says hello —
        // marshal to the UI thread before touching views.
        SessionManager.onPeerConnected = { peerIp ->
            runOnUiThread {
                val ip = lastIp
                tvStatus.text = if (ip != null) {
                    "Server: ON — $ip:${TransferReceiverService.PORT}\nConnected: $peerIp"
                } else {
                    "Connected: $peerIp"
                }
                btnSendFiles.visibility = View.VISIBLE
            }
        }
        NetworkRequirements.ensureReceiver(this) {
            if (receiverSessionActive && TransferReceiverService.isServerRunning && HotspotManager.isRunning) {
                // This screen session is already fully set up — just refresh
                // the status instead of restarting hotspot and server.
                val ssid = lastSsid
                val ip = lastIp
                val peerIp = SessionManager.peerIp
                if (SessionManager.active && !peerIp.isNullOrEmpty() && ip != null) {
                    tvStatus.text = "Server: ON — $ip:${TransferReceiverService.PORT}\nConnected: $peerIp"
                    btnSendFiles.visibility = View.VISIBLE
                } else if (ssid != null && ip != null) {
                    tvStatus.text = "Hotspot: $ssid\nServer: ON — $ip:${TransferReceiverService.PORT}\nScan QR with sender to auto-connect"
                }
            } else {
                // The HTTP receiver server must be listening BEFORE the sender can
                // connect and upload — start it first, then bring up the hotspot.
                startReceiverService()
                setupReceiverHotspot()
            }
        }
    }

    override fun onPause() {
        SessionManager.onPeerConnected = null
        super.onPause()
    }

    override fun onDestroy() {
        // Leaving via SEND FILES destroys this screen (CLEAR_TOP) but must not
        // tear anything down — the session has to survive for the return trip.
        if (!keepSessionOnDestroy) {
            stopReceiverService()
            HotspotManager.stopHotspot()
        }
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
        receiverSessionActive = false
        SessionManager.clear()
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
                // Don't present the QR as ready until the receiver server has
                // proven it is actually listening — otherwise the sender joins
                // the hotspot and finds nobody home.
                tvStatus.text = "Hotspot: $ssid\nStarting server..."
                runServerSelfTest(ssid, pass, ip)
            },
            onError = { err ->
                tvStatus.text = "Hotspot Error: $err"
            }
        )
    }

    /**
     * Self-test on a background thread: the receiver server must accept a local
     * connection and answer /ping with PONG before we call this session ready.
     * Retries for up to ~6 seconds because the service start is asynchronous.
     */
    private fun runServerSelfTest(ssid: String, pass: String, ip: String) {
        Thread {
            val serverOk = selfTestServer()
            runOnUiThread {
                if (serverOk) {
                    receiverSessionActive = true
                    lastSsid = ssid
                    lastIp = ip
                    generateQR(ssid, pass, ip)
                } else {
                    receiverSessionActive = false
                    val err = TransferReceiverService.lastServerError ?: "unknown"
                    tvStatus.text = "Server failed to start: $err"
                }
            }
        }.start()
    }

    private fun selfTestServer(): Boolean {
        val deadline = System.currentTimeMillis() + 6_000L
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", TransferReceiverService.PORT), 800)
                }
                // Port is listening — confirm it is really our server via /ping.
                if (pingLocalServer()) return true
            } catch (_: Exception) {}
            try {
                Thread.sleep(400)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun pingLocalServer(): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:${TransferReceiverService.PORT}/ping").openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val body = try {
                conn.inputStream?.bufferedReader()?.readText()?.trim()
            } catch (_: Exception) {
                null
            }
            conn.disconnect()
            code == 200 && body == "PONG"
        } catch (_: Exception) {
            false
        }
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
            tvStatus.text = "Hotspot: $ssid\nServer: ON — $ip:${TransferReceiverService.PORT}\nScan QR with sender to auto-connect"
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
