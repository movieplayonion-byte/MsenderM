package com.woderplayer.quickshare

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast

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
            // Fallback: If reflection fails, assume user needs to ensure it via prompt
            false
        }
    }

    // Sender Mandatory Check (Hotspot + Location)
    fun ensureSenderRequirements(activity: Activity, onPassed: () -> Unit) {
        val locationOk = isLocationEnabled(activity)
        val hotspotOk = isHotspotEnabled(activity)

        if (!locationOk) {
            showMandatoryDialog(
                activity,
                title = "Location Required",
                message = "File share karne ke liye Location (GPS) on karna mandatory hai.",
                actionText = "TURN ON LOCATION",
                actionIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            )
            return
        }

        if (!hotspotOk) {
            val hotspotIntent = Intent().apply {
                action = "android.settings.TETHER_SETTINGS"
            }
            showMandatoryDialog(
                activity,
                title = "Hotspot Required",
                message = "Offline receiver se connect hone ke liye Personal Hotspot on karna mandatory hai.",
                actionText = "TURN ON HOTSPOT",
                actionIntent = hotspotIntent
            )
            return
        }

        onPassed()
    }

    // Receiver Mandatory Check (Wi-Fi + Location)
    fun ensureReceiverRequirements(activity: Activity, onPassed: () -> Unit) {
        val locationOk = isLocationEnabled(activity)
        val wifiOk = isWifiEnabled(activity)

        if (!locationOk) {
            showMandatoryDialog(
                activity,
                title = "Location Required",
                message = "Receiver device scan aur QR connect ke liye Location on karna mandatory hai.",
                actionText = "TURN ON LOCATION",
                actionIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            )
            return
        }

        if (!wifiOk) {
            showMandatoryDialog(
                activity,
                title = "Wi-Fi Required",
                message = "Files receive karne ke liye Wi-Fi on karna mandatory hai.",
                actionText = "TURN ON WI-FI",
                actionIntent = Intent(Settings.ACTION_WIFI_SETTINGS)
            )
            return
        }

        onPassed()
    }

    private fun showMandatoryDialog(
        activity: Activity,
        title: String,
        message: String,
        actionText: String,
        actionIntent: Intent
    ) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(actionText) { _, _ ->
                try {
                    activity.startActivity(actionIntent)
                } catch (e: Exception) {
                    activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}
