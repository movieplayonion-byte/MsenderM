package com.woderplayer.quickshare.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.NetworkInterface
import java.util.Collections

class TransferReceiverService : Service() {
    private var server: ReceiverHttpServer? = null

    companion object {
        const val PORT = 8888
        const val CHANNEL_ID = "transfer_receiver_channel"
        const val ACTION_START = "ACTION_START_RECEIVER"
        const val ACTION_STOP = "ACTION_STOP_RECEIVER"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MsenderM Receiver")
                .setContentText("Listening on port $PORT for incoming files...")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .build()
            startForeground(1001, notification)

            try {
                if (server == null) {
                    server = ReceiverHttpServer(PORT, applicationContext)
                    server?.start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else if (intent?.action == ACTION_STOP) {
            server?.stop()
            server = null
            stopForeground(true)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Receiver Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}

class ReceiverHttpServer(port: Int, private val context: Context) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.POST && session.uri == "/upload") {
            val files = HashMap<String, String>()
            try {
                session.parseBody(files)
                val params = session.parameters
                val filename = params["filename"]?.get(0) ?: "received_file_${System.currentTimeMillis()}"
                val tmpFilePath = files["file"]

                if (tmpFilePath != null) {
                    val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val targetDir = File(downloadDir, "MsenderM")
                    if (!targetDir.exists()) targetDir.mkdirs()

                    val targetFile = File(targetDir, filename)
                    File(tmpFilePath).copyTo(targetFile, overwrite = true)
                    return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "SAVED")
                }
            } catch (e: Exception) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
            }
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "MsenderM Ready")
    }
}

object NetworkUtils {
    fun getLocalIp(): String {
        val interfaces = java.util.Collections.list(
            java.net.NetworkInterface.getNetworkInterfaces()
        )

        val candidates = interfaces
            .filter { it.isUp && !it.isLoopback }
            .sortedBy {
                when {
                    it.name.startsWith("wlan", true) -> 0
                    it.name.startsWith("swlan", true) -> 1
                    it.name.startsWith("ap", true) -> 2
                    it.name.startsWith("p2p", true) -> 3
                    else -> 10
                }
            }

        for (intf in candidates) {
            val name = intf.name.lowercase()
            if (name == "lo" ||
                name.startsWith("tun") ||
                name.startsWith("tap") ||
                name.startsWith("dummy")) continue

            val addresses = java.util.Collections.list(intf.inetAddresses)
            for (addr in addresses) {
                if (addr is java.net.Inet4Address &&
                    !addr.isLoopbackAddress &&
                    addr.isSiteLocalAddress
                ) {
                    return addr.hostAddress
                }
            }
        }

        throw java.io.IOException(
            "No local IPv4 address found. Connect both phones to a compatible Wi-Fi network."
        )
    }
}
