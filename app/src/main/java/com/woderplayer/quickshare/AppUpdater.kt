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

                val configUrl = "https://raw.githubusercontent.com/movieplayonion-byte/MsenderM/main/version.json?timestamp=${System.currentTimeMillis()}"
                val req = Request.Builder()
                    .url(configUrl)
                    .header("Cache-Control", "no-cache")
                    .build()

                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@Thread
                    val json = JSONObject(body)
                    val remoteVer = json.optInt("versionCode", currentVersionCode)
                    val downloadUrl = json.optString("downloadUrl", "")
                    val notes = json.optString("releaseNotes", "New update available!")

                    if (remoteVer > currentVersionCode) {
                        activity.runOnUiThread {
                            showDialog(activity, client, downloadUrl, notes)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun showDialog(activity: Activity, client: OkHttpClient, url: String, notes: String) {
        AlertDialog.Builder(activity)
            .setTitle("MsenderM Update Available")
            .setMessage(notes)
            .setCancelable(false)
            .setPositiveButton("Update Now") { _, _ ->
                checkPermissionAndDownload(activity, client, url)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun checkPermissionAndDownload(activity: Activity, client: OkHttpClient, url: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                Toast.makeText(activity, "Allow installation and tap Update again", Toast.LENGTH_LONG).show()
                return
            }
        }
        downloadWithNotification(activity, client, url)
    }

    private fun downloadWithNotification(activity: Activity, client: OkHttpClient, url: String) {
        val notifManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "update_download_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "App Update Download", NotificationManager.IMPORTANCE_LOW)
            notifManager.createNotificationChannel(channel)
        }

        val notifBuilder = NotificationCompat.Builder(activity, channelId)
            .setContentTitle("Downloading MsenderM")
            .setContentText("0% downloaded")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, 0, false)

        notifManager.notify(2001, notifBuilder.build())
        Toast.makeText(activity, "Update downloading...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val req = Request.Builder().url(url).build()
                val resp = client.newCall(req).execute()
                val body = resp.body ?: throw Exception("Empty body")
                val totalBytes = body.contentLength()
                val apkFile = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")

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
                    Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
