package com.woderplayer.quickshare

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

object HotspotManager {
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    const val PORT = 6789

    fun startHotspot(
        context: Context,
        onSuccess: (ssid: String, pass: String, ip: String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onError("Android 8.0+ required for LocalOnlyHotspot")
            return
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        try {
            stopHotspot()
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    super.onStarted(res)
                    reservation = res
                    val config = res.wifiConfiguration
                    val ssid = config?.SSID ?: "MsenderM_${(1000..9999).random()}"
                    val password = config?.preSharedKey ?: ""

                    // Xender clone hotspot standard gateway
                    Handler(Looper.getMainLooper()).post {
                        onSuccess(ssid, password, "192.168.49.1")
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
            onError(e.message ?: "Failed to start hotspot")
        }
    }

    fun stopHotspot() {
        try {
            reservation?.close()
            reservation = null
        } catch (_: Exception) {}
    }
}
