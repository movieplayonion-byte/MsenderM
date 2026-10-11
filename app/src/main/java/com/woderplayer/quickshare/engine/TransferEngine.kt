package com.woderplayer.quickshare.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.NetworkInterface
import java.net.URLDecoder
import java.util.Collections

class TransferReceiverService : Service() {
    private var server: ReceiverHttpServer? = null

    companion object {
        const val PORT = 6789
        const val CHANNEL_ID = "transfer_receiver_channel"
        const val NOTIF_ID = 1001
        const val ACTION_START = "ACTION_START_RECEIVER"
        const val ACTION_STOP = "ACTION_STOP_RECEIVER"
        var onFileReceivedListener: ((String, Long) -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val notification = createNotification("MsenderM Receiver", "Ready for incoming files...", 0, 0)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIF_ID, notification)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                if (server == null) {
                    server = ReceiverHttpServer(PORT, applicationContext, this)
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

    fun updateNotification(title: String, text: String, max: Int, progress: Int) {
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = createNotification(title, text, max, progress)
        notifManager.notify(NOTIF_ID, notif)
    }

    private fun createNotification(title: String, text: String, max: Int, progress: Int): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (max > 0) {
            builder.setProgress(max, progress, false)
        }
        return builder.build()
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
                "MsenderM Transfer Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Transfer status and progress"
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}

class ReceiverHttpServer(
    port: Int,
    private val context: Context,
    private val service: TransferReceiverService
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        if (uri == "/ping") return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "PONG")

        if (session.method == Method.POST) {
            return try {
                val rawName = session.headers["x-filename"] ?: "file_${System.currentTimeMillis()}"
                val fileName = URLDecoder.decode(rawName, "UTF-8")
                val totalLen = session.headers["content-length"]?.toLongOrNull() ?: 0L
                val inputStream = session.inputStream

                val isSaved = saveIncomingStream(fileName, inputStream, totalLen)
                if (isSaved) {
                    TransferReceiverService.onFileReceivedListener?.invoke(fileName, totalLen)
                    service.updateNotification("MsenderM Receiver", "Received: $fileName", 0, 0)
                    newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "SAVED")
                } else {
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Save Failed")
                }
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Error")
            }
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "MsenderM Ready")
    }

    private fun saveIncomingStream(fileName: String, inputStream: InputStream, length: Long): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MsenderM")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        copyBytesWithProgress(fileName, inputStream, out, length)
                    }
                    true
                } else {
                    saveFallback(fileName, inputStream, length)
                }
            } else {
                saveFallback(fileName, inputStream, length)
            }
        } catch (_: Exception) {
            saveFallback(fileName, inputStream, length)
        }
    }

    private fun saveFallback(fileName: String, inputStream: InputStream, length: Long): Boolean {
        return try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MsenderM")
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, fileName)
            FileOutputStream(target).use { out -> copyBytesWithProgress(fileName, inputStream, out, length) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun copyBytesWithProgress(fileName: String, inStream: InputStream, outStream: OutputStream, expectedLength: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = if (expectedLength > 0) expectedLength else Long.MAX_VALUE
        var transferred = 0L
        var lastNotifTime = System.currentTimeMillis()

        while (remaining > 0) {
            val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
            val bytesRead = inStream.read(buffer, 0, toRead)
            if (bytesRead == -1) break
            outStream.write(buffer, 0, bytesRead)
            transferred += bytesRead
            if (expectedLength > 0) remaining -= bytesRead

            val now = System.currentTimeMillis()
            if (now - lastNotifTime >= 500 && expectedLength > 0) {
                val pct = ((transferred * 100) / expectedLength).toInt()
                service.updateNotification("Receiving $fileName", "$pct%", 100, pct)
                lastNotifTime = now
            }
        }
        outStream.flush()
    }
}

object NetworkUtils {
    fun getLocalIp(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                if (name.contains("wlan") || name.contains("ap") || name.contains("p2p") || name.contains("rndis")) {
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: ""
                            if (host.indexOf(':') < 0 && (host.startsWith("192.168.") || host.startsWith("10."))) {
                                return host
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.43.1"
    }
}
