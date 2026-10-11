package com.woderplayer.quickshare

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

object NetworkRequirements {

    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    fun isWifiEnabled(context: Context): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wm.isWifiEnabled
    }

    fun isHotspotEnabled(context: Context): Boolean {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wm) as? Boolean ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun ensureSender(activity: Activity, onPassed: () -> Unit) {
        if (!isLocationEnabled(activity)) {
            showDialog(
                activity,
                "Location Required",
                "File send karne ke liye Location (GPS) on karna mandatory hai.",
                "TURN ON LOCATION",
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            ) { ensureSender(activity, onPassed) }
            return
        }

        if (!isHotspotEnabled(activity)) {
            showDialog(
                activity,
                "Hotspot Required",
                "Receiver ko connect karne ke liye Personal Hotspot on karna zaroori hai.",
                "OPEN HOTSPOT SETTINGS",
                Intent("android.settings.TETHER_SETTINGS")
            ) { ensureSender(activity, onPassed) }
            return
        }

        onPassed()
    }

    fun ensureReceiver(activity: Activity, onPassed: () -> Unit) {
        if (!isLocationEnabled(activity)) {
            showDialog(
                activity,
                "Location Required",
                "Device search aur QR connect ke liye Location on karna mandatory hai.",
                "TURN ON LOCATION",
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            ) { ensureReceiver(activity, onPassed) }
            return
        }

        if (!isWifiEnabled(activity)) {
            showDialog(
                activity,
                "Wi-Fi Required",
                "Sender ke hotspot se offline connect hone ke liye Wi-Fi on karein.",
                "TURN ON WI-FI",
                Intent(Settings.ACTION_WIFI_SETTINGS)
            ) { ensureReceiver(activity, onPassed) }
            return
        }

        onPassed()
    }

    private fun showDialog(
        activity: Activity,
        title: String,
        msg: String,
        btnText: String,
        intent: Intent,
        onRetry: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton(btnText) { _, _ ->
                try {
                    activity.startActivity(intent)
                } catch (_: Exception) {
                    activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("CHECK AGAIN") { _, _ ->
                onRetry()
            }
            .show()
    }
}
