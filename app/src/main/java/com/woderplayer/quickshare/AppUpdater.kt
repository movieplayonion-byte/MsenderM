package com.woderplayer.quickshare

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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

                // Public repo URL with timestamp cache buster
                val configUrl = "https://raw.githubusercontent.com/movieplayonion-byte/MsenderM/main/version.json?t=${System.currentTimeMillis()}"
                val req = Request.Builder().url(configUrl).build()

                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@Thread
                    val json = JSONObject(body)
                    val remoteVer = json.optInt("versionCode", currentVersionCode)
                    val downloadUrl = json.optString("downloadUrl", "")
                    val notes = json.optString("releaseNotes", "Mandatory update required.")

                    if (remoteVer > currentVersionCode) {
                        Handler(Looper.getMainLooper()).post {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                showBlockingUpdateDialog(activity, client, downloadUrl, notes)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun showBlockingUpdateDialog(activity: Activity, client: OkHttpClient, url: String, notes: String) {
        val layout = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 30)
        }

        val tvNotes = TextView(activity).apply {
            text = notes
            textSize = 14f
            setTextColor(android.graphics.Color.LTGRAY)
        }

        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            setPadding(0, 30, 0, 10)
        }

        val tvPercent = TextView(activity).apply {
            text = "Downloading: 0%"
            textSize = 13f
            setTextColor(android.graphics.Color.CYAN)
            visibility = View.GONE
        }

        val btnUpdate = Button(activity).apply {
            text = "UPDATE NOW"
            setBackgroundColor(android.graphics.Color.parseColor("#00E676"))
            setTextColor(android.graphics.Color.BLACK)
        }

        layout.addView(tvNotes)
        layout.addView(progressBar)
        layout.addView(tvPercent)
        layout.addView(btnUpdate)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("MsenderM Mandatory Update")
            .setView(layout)
            .setCancelable(false)
            .setOnCancelListener { activity.finishAffinity() }
            .create()

        dialog.setCanceledOnTouchOutside(false)

        btnUpdate.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                    Toast.makeText(activity, "Allow permission and return to app", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            btnUpdate.isEnabled = false
            btnUpdate.text = "DOWNLOADING..."
            progressBar.visibility = View.VISIBLE
            tvPercent.visibility = View.VISIBLE

            Thread {
                try {
                    val req = Request.Builder().url(url).build()
                    val resp = client.newCall(req).execute()
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")

                    val body = resp.body ?: throw Exception("Empty APK response")
                    val totalBytes = body.contentLength()

                    // Minimum 2MB check taaki koi HTML error page download hokar install na ho
                    if (totalBytes in 1..2000000L) {
                        throw Exception("Corrupted file received from server")
                    }

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
                            Handler(Looper.getMainLooper()).post {
                                progressBar.progress = pct
                                tvPercent.text = "Downloading: $pct%"
                            }
                        }
                    }
                    outStream.flush()
                    outStream.close()
                    inStream.close()

                    Handler(Looper.getMainLooper()).post {
                        btnUpdate.text = "INSTALLING..."
                        MediaViewer.installApk(activity, apkFile)
                    }
                } catch (e: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        btnUpdate.isEnabled = true
                        btnUpdate.text = "RETRY UPDATE"
                        progressBar.visibility = View.GONE
                        tvPercent.visibility = View.VISIBLE
                        tvPercent.text = "Error: ${e.message}"
                        Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        dialog.show()
    }
}
