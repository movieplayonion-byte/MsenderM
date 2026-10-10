package com.woderplayer.quickshare.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
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
        const val PORT = 8888
        const val CHANNEL_ID = "transfer_receiver_channel"
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
        if (session.uri == "/ping") {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "PONG")
        }

        if (session.method == Method.POST && session.uri.startsWith("/upload")) {
            return try {
                // Filename header ya query param se lo
                val rawName = session.headers["x-filename"] 
                    ?: session.parameters["filename"]?.firstOrNull() 
                    ?: "file_${System.currentTimeMillis()}"
                val fileName = URLDecoder.decode(rawName, "UTF-8")

                val totalLen = session.headers["content-length"]?.toLongOrNull() ?: 0L
                val inputStream = session.inputStream

                val savedFile = saveIncomingStream(fileName, inputStream, totalLen)

                if (savedFile != null) {
                    TransferReceiverService.onFileReceivedListener?.invoke(fileName, savedFile.length())
                    newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "SAVED")
                } else {
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Save Failed")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Error")
            }
        }

        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "MsenderM Ready")
    }

    private fun saveIncomingStream(fileName: String, inputStream: InputStream, length: Long): File? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ MediaStore API - 100% visible in Downloads & Gallery
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MsenderM")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        copyBytes(inputStream, out, length)
                    }
                    File("/storage/emulated/0/Download/MsenderM/$fileName")
                } else {
                    saveFallback(fileName, inputStream, length)
                }
            } else {
                saveFallback(fileName, inputStream, length)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            saveFallback(fileName, inputStream, length)
        }
    }

    private fun saveFallback(fileName: String, inputStream: InputStream, length: Long): File? {
        return try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MsenderM")
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, fileName)
            FileOutputStream(target).use { out ->
                copyBytes(inputStream, out, length)
            }
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
            target
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun copyBytes(inStream: InputStream, outStream: OutputStream, expectedLength: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = if (expectedLength > 0) expectedLength else Long.MAX_VALUE
        while (remaining > 0) {
            val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
            val bytesRead = inStream.read(buffer, 0, toRead)
            if (bytesRead == -1) break
            outStream.write(buffer, 0, bytesRead)
            if (expectedLength > 0) {
                remaining -= bytesRead
            }
        }
        outStream.flush()
    }
}

object NetworkUtils {
    fun getLocalIp(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Priority 1: Hotspot / Wi-Fi interfaces
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                if (name.contains("wlan") || name.contains("ap") || name.contains("p2p") || name.contains("rndis")) {
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: ""
                            if (host.indexOf(':') < 0 && host.startsWith("192.168.")) {
                                return host
                            }
                        }
                    }
                }
            }
            // Priority 2: Any 192.168.x.x
            for (intf in interfaces) {
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: ""
                        if (host.indexOf(':') < 0 && host.startsWith("192.168.")) {
                            return host
                        }
                    }
                }
            }
            // Fallback
            for (intf in interfaces) {
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: ""
                        if (host.indexOf(':') < 0 && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.43.1"
    }
}
