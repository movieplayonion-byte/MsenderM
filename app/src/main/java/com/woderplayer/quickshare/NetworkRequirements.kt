package com.woderplayer.quickshare

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object NetworkRequirements {

    const val REQ_CODE_PERMISSIONS = 4001

    fun hasNearbyPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isLocationSwitchOn(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    fun isWifiSwitchOn(context: Context): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wm.isWifiEnabled
    }

    // SENDER FLOW: Nearby Permission -> Location Permission -> GPS Switch -> Wi-Fi Switch
    fun ensureSender(activity: Activity, onPassed: () -> Unit) {
        val missingPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNearbyPermission(activity)) {
            missingPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (!hasLocationPermission(activity)) {
            missingPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (missingPermissions.isNotEmpty()) {
            showMandatoryDialog(
                activity,
                "Permissions Required",
                "Nearby Devices aur Location permissions allow karna mandatory hai taaki receiver ko scan aur connect kiya ja sake.",
                "GRANT PERMISSIONS",
                {
                    ActivityCompat.requestPermissions(
                        activity,
                        missingPermissions.toTypedArray(),
                        REQ_CODE_PERMISSIONS
                    )
                },
                { ensureSender(activity, onPassed) }
            )
            return
        }

        if (!isLocationSwitchOn(activity)) {
            showMandatoryDialog(
                activity,
                "Location (GPS) Required",
                "Wi-Fi search ke liye phone ki Location (GPS) ON karna mandatory hai.",
                "TURN ON LOCATION",
                {
                    activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                },
                { ensureSender(activity, onPassed) }
            )
            return
        }

        if (!isWifiSwitchOn(activity)) {
            showMandatoryDialog(
                activity,
                "Wi-Fi Required",
                "Receiver ke Hotspot se auto-connect hone ke liye Wi-Fi ON karein.",
                "TURN ON WI-FI",
                {
                    activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                },
                { ensureSender(activity, onPassed) }
            )
            return
        }

        onPassed()
    }

    // RECEIVER FLOW: Nearby Permission -> Location Permission -> GPS Switch (Hotspot Android khud manage karega)
    fun ensureReceiver(activity: Activity, onPassed: () -> Unit) {
        val missingPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNearbyPermission(activity)) {
            missingPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (!hasLocationPermission(activity)) {
            missingPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (missingPermissions.isNotEmpty()) {
            showMandatoryDialog(
                activity,
                "Nearby Devices Permission Required",
                "Secure clone hotspot create karne ke liye 'Nearby devices' aur Location permissions allow karna zaroori hai.",
                "ALLOW PERMISSION",
                {
                    ActivityCompat.requestPermissions(
                        activity,
                        missingPermissions.toTypedArray(),
                        REQ_CODE_PERMISSIONS
                    )
                },
                { ensureReceiver(activity, onPassed) }
            )
            return
        }

        if (!isLocationSwitchOn(activity)) {
            showMandatoryDialog(
                activity,
                "Location (GPS) Required",
                "Android clone hotspot enable karne ke liye GPS Location ON hona mandatory hai.",
                "TURN ON LOCATION",
                {
                    activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                },
                { ensureReceiver(activity, onPassed) }
            )
            return
        }

        onPassed()
    }

    fun ensureSenderRequirements(activity: Activity, onPassed: () -> Unit) {
        ensureSender(activity, onPassed)
    }

    fun ensureReceiverRequirements(activity: Activity, onPassed: () -> Unit) {
        ensureReceiver(activity, onPassed)
    }

    private fun showMandatoryDialog(
        activity: Activity,
        title: String,
        msg: String,
        btnText: String,
        onAction: () -> Unit,
        onRetry: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton(btnText) { _, _ ->
                onAction()
            }
            .setNegativeButton("CHECK AGAIN") { _, _ ->
                onRetry()
            }
            .show()
    }
}
