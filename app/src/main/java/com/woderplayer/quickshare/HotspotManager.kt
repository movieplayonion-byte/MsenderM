package com.woderplayer.quickshare

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

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
            stopHotspot()
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    super.onStarted(res)
                    reservation = res
                    val config = res.wifiConfiguration
                    val ssid = config?.SSID ?: "MsenderM_${(1000..9999).random()}"
                    val password = config?.preSharedKey ?: ""

                    // Dynamic delay taaki OS interface up kar de aur IP assign ho jaye
                    Handler(Looper.getMainLooper()).postDelayed({
                        val ip = getHotspotIp()
                        onSuccess(ssid, password, ip)
                    }, 1000)
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

    private fun getHotspotIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "192.168.49.1"
            for (intf in Collections.list(interfaces)) {
                val name = intf.name.lowercase()
                if (name.contains("ap") || name.contains("wlan") || name.contains("p2p") || name.contains("swlan") || name.contains("softap")) {
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: ""
                            if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                                return host
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return "192.168.49.1"
    }
}
