package com.woderplayer.quickshare

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.woderplayer.quickshare.engine.NetworkUtils

object HotspotManager {
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    const val PORT = 6789
    private const val IP_RESOLVE_ATTEMPTS = 10
    private const val IP_RESOLVE_INTERVAL_MS = 300L

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

                    // The hotspot interface gets its address a moment after onStarted;
                    // resolve the REAL IP instead of assuming the standard gateway.
                    resolveHotspotIp { ip ->
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
            onError(e.message ?: "Failed to start hotspot")
        }
    }

    /**
     * Polls for the hotspot interface address for up to ~3 seconds.
     * Falls back to the standard LocalOnlyHotspot gateway if it never appears.
     */
    private fun resolveHotspotIp(attempt: Int = 0, onResolved: (String) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val ip = NetworkUtils.findLocalIp()
        if (ip != null || attempt >= IP_RESOLVE_ATTEMPTS) {
            handler.post { onResolved(ip ?: NetworkUtils.FALLBACK_IP) }
        } else {
            handler.postDelayed({ resolveHotspotIp(attempt + 1, onResolved) }, IP_RESOLVE_INTERVAL_MS)
        }
    }

    fun stopHotspot() {
        try {
            reservation?.close()
            reservation = null
        } catch (_: Exception) {}
    }
}
