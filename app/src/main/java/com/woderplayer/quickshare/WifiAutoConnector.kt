package com.woderplayer.quickshare

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build

object WifiAutoConnector {

    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    fun connectToHotspot(
        context: Context,
        ssid: String,
        pass: String,
        onConnected: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Purely offline transfer
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    cm.bindProcessToNetwork(network) // Bind app traffic to this hotspot socket
                    onConnected()
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    onFailed("Connection timeout or rejected")
                }
            }

            activeCallback = callback
            cm.requestNetwork(request, callback)
        } else {
            // Older Android fallback
            onConnected()
        }
    }

    fun releaseNetwork(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            activeCallback?.let { cm.unregisterNetworkCallback(it) }
            cm.bindProcessToNetwork(null)
            activeCallback = null
        } catch (_: Exception) {}
    }
}
