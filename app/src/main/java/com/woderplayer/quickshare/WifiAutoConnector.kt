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
    var boundNetwork: Network? = null

    fun connectToHotspot(
        context: Context,
        ssid: String,
        pass: String,
        onConnected: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null) {
                onConnected()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
                if (pass.isNotEmpty()) {
                    specifierBuilder.setWpa2Passphrase(pass)
                }

                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifierBuilder.build())
                    .build()

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        boundNetwork = network
                        try {
                            cm.bindProcessToNetwork(network)
                        } catch (_: Throwable) {}
                        onConnected()
                    }

                    override fun onUnavailable() {
                        onFailed("Hotspot unavailable")
                    }
                }

                activeCallback = callback
                cm.requestNetwork(request, callback)
            } else {
                onConnected()
            }
        } catch (e: Throwable) {
            onFailed(e.message ?: "Connect error")
        }
    }

    fun releaseNetwork(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            activeCallback?.let { cm?.unregisterNetworkCallback(it) }
            cm?.bindProcessToNetwork(null)
            activeCallback = null
            boundNetwork = null
        } catch (_: Throwable) {}
    }
}
