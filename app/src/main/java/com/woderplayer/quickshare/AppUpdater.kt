package com.woderplayer.quickshare

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object AppUpdater {
    const val CURRENT_VERSION = 1
    private const val CONFIG_URL = "https://raw.githubusercontent.com/movieplayonion-byte/MsenderM/main/version.json"

    fun check(activity: Activity, client: OkHttpClient) {
        Thread {
            try {
                val req = Request.Builder().url(CONFIG_URL).build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@Thread
                    val json = JSONObject(body)
                    val remoteVer = json.optInt("versionCode", CURRENT_VERSION)
                    val downloadUrl = json.optString("downloadUrl", "")
                    val notes = json.optString("releaseNotes", "New update available!")

                    if (remoteVer > CURRENT_VERSION) {
                        activity.runOnUiThread {
                            showDialog(activity, client, downloadUrl, notes)
                        }
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun showDialog(activity: Activity, client: OkHttpClient, url: String, notes: String) {
        AlertDialog.Builder(activity)
            .setTitle("Mandatory Update")
            .setMessage(notes)
            .setCancelable(false)
            .setPositiveButton("Update Now") { _, _ ->
                checkPermissionAndDownload(activity, client, url)
            }
            .show()
    }

    private fun checkPermissionAndDownload(activity: Activity, client: OkHttpClient, url: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                Toast.makeText(activity, "Enable install permission and retry update", Toast.LENGTH_LONG).show()
                return
            }
        }
        download(activity, client, url)
    }

    private fun download(activity: Activity, client: OkHttpClient, url: String) {
        val dialog = ProgressDialog(activity).apply {
            setMessage("Downloading update APK...")
            setCancelable(false)
            show()
        }

        Thread {
            try {
                val req = Request.Builder().url(url).build()
                val resp = client.newCall(req).execute()
                val apkFile = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(apkFile).use { output -> input.copyTo(output) }
                }
                activity.runOnUiThread {
                    dialog.dismiss()
                    MediaViewer.installApk(activity, apkFile)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(activity, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
