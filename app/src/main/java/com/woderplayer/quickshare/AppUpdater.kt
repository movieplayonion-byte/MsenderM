package com.woderplayer.quickshare

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object AppUpdater {

    fun check(activity: Activity, client: OkHttpClient) {
        Thread {
            try {
                val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode
                }

                val configUrl = "https://gist.githubusercontent.com/movieplayonion-byte/e9befe8c1956fd0982427ba0b3fc102a/raw/2c844c56557e79fadecca5e15beafd1a66c807d9/version.json"
                val req = Request.Builder()
                    .url(configUrl)
                    .build()

                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@Thread
                    val json = JSONObject(body)
                    val remoteVer = json.optInt("versionCode", currentVersionCode)
                    val downloadUrl = json.optString("downloadUrl", "")
                    val notes = json.optString("releaseNotes", "New update available!")

                    if (remoteVer > currentVersionCode) {
                        Handler(Looper.getMainLooper()).post {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                showDialog(activity, client, downloadUrl, notes)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun showDialog(activity: Activity, client: OkHttpClient, url: String, notes: String) {
        val builder = AlertDialog.Builder(activity)
            .setTitle("MsenderM Update Available")
            .setMessage(notes)
            .setCancelable(false)
            .setPositiveButton("UPDATE NOW") { _, _ ->
                checkPermissionAndDownload(activity, client, url)
            }
            .setNegativeButton("LATER") { d, _ ->
                d.dismiss()
            }

        val dialog = builder.create()
        dialog.show()
    }

    private fun checkPermissionAndDownload(activity: Activity, client: OkHttpClient, url: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                Toast.makeText(activity, "Permission allow karke wapas Update dabayein", Toast.LENGTH_LONG).show()
                return
            }
        }
        downloadWithNotification(activity, client, url)
    }

    private fun downloadWithNotification(activity: Activity, client: OkHttpClient, url: String) {
        val notifManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "update_download_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "MsenderM Update Download", NotificationManager.IMPORTANCE_LOW)
            notifManager.createNotificationChannel(channel)
        }

        val notifBuilder = NotificationCompat.Builder(activity, channelId)
            .setContentTitle("Downloading MsenderM")
            .setContentText("Starting download...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, 0, false)

        notifManager.notify(2001, notifBuilder.build())
        Toast.makeText(activity, "Downloading update in notification drawer...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val req = Request.Builder().url(url).build()
                val resp = client.newCall(req).execute()
                val body = resp.body ?: throw Exception("Empty file body")
                val totalBytes = body.contentLength()
                val apkFile = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "MsenderM-update.apk")

                val inStream = body.byteStream()
                val outStream = FileOutputStream(apkFile)
                val buffer = ByteArray(32 * 1024)
                var read: Int
                var transferred = 0L

                while (inStream.read(buffer).also { read = it } != -1) {
                    outStream.write(buffer, 0, read)
                    transferred += read
                    if (totalBytes > 0) {
                        val pct = ((transferred * 100) / totalBytes).toInt()
                        notifBuilder.setProgress(100, pct, false)
                        notifBuilder.setContentText("$pct% downloaded")
                        notifManager.notify(2001, notifBuilder.build())
                    }
                }
                outStream.flush()
                outStream.close()
                inStream.close()

                notifManager.cancel(2001)
                activity.runOnUiThread {
                    MediaViewer.installApk(activity, apkFile)
                }
            } catch (e: Exception) {
                notifManager.cancel(2001)
                activity.runOnUiThread {
                    Toast.makeText(activity, "Update Download Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
