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

                    // 1 second buffer taaki local interface up ho jaye
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
                
                // Strictly exclude mobile data, cellular interfaces, vpn & tunnels
                if (name.contains("rmnet") || name.contains("ccmni") || name.contains("tun") || name.contains("dummy") || name.contains("pdp")) {
                    continue
                }

                // Strictly target Hotspot / AP / Wi-Fi Direct interfaces
                if (name.contains("ap") || name.contains("swlan") || name.contains("wlan") || name.contains("p2p")) {
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: ""
                            // Exclude cellular carrier CGNAT (100.x.x.x) and private carrier subnets (10.x.x.x)
                            if (host.startsWith("192.168.")) {
                                return host
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        // Standard Android LOHS gateway fallback
        return "192.168.49.1"
    }
}
