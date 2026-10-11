package com.woderplayer.quickshare

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

object HotspotManager {
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun startHotspot(
        context: Context,
        onSuccess: (ssid: String, pass: String, ip: String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onError("Android 8.0+ required for dynamic hotspot")
            return
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        try {
            stopHotspot() // Agar pehle se chal raha ho toh reset
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    super.onStarted(res)
                    reservation = res
                    val config = res.wifiConfiguration
                    val ssid = config?.SSID ?: "MsenderM_${(1000..9999).random()}"
                    val password = config?.preSharedKey ?: ""
                    val ip = "192.168.43.1" // Default standard gateway for LocalOnlyHotspot

                    Handler(Looper.getMainLooper()).post {
                        onSuccess(ssid, password, ip)
                    }
                }

                override fun onStopped() {
                    super.onStopped()
                    reservation = null
                }

                override fun onFailed(reason: Int) {
                    super.onFailed(reason)
                    reservation = null
                    Handler(Looper.getMainLooper()).post {
                        onError("Hotspot start failed (code: $reason)")
                    }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            onError(e.message ?: "Failed to start clone hotspot")
        }
    }

    fun stopHotspot() {
        try {
            reservation?.close()
            reservation = null
        } catch (_: Exception) {}
    }
}
