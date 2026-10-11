package com.woderplayer.quickshare

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.text.format.Formatter

object WifiAutoConnector {
    var boundNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun getGatewayIp(context: Context): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wm.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                Formatter.formatIpAddress(dhcp.gateway)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun connectToHotspot(
        context: Context,
        ssid: String,
        pass: String,
        onConnected: () -> Unit,
        onError: () -> Unit = {}
    ) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        disconnect(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .apply {
                    if (pass.isNotEmpty()) {
                        setWpa2Passphrase(pass)
                    }
                }
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    boundNetwork = network
                    try {
                        cm.bindProcessToNetwork(network)
                    } catch (_: Throwable) {}
                    onConnected()
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    onError()
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    if (boundNetwork == network) {
                        boundNetwork = null
                    }
                }
            }

            try {
                cm.requestNetwork(request, networkCallback!!)
            } catch (e: Exception) {
                onError()
            }
        } else {
            onError()
        }
    }

    fun disconnect(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
            networkCallback = null
            boundNetwork = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.bindProcessToNetwork(null)
            }
        } catch (_: Throwable) {}
    }
}
