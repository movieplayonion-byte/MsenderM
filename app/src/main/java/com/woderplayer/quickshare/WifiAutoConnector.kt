package com.woderplayer.quickshare

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import com.woderplayer.quickshare.engine.TransferReceiverService
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object WifiAutoConnector {
    var boundNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private const val HANDSHAKE_TIMEOUT_MS = 12_000L

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

    /**
     * Best-known IP of the receiver (hotspot owner) for the given network:
     * DHCP server address (API 30+), else the default-route gateway from the
     * network's link properties, else the legacy WifiManager DHCP gateway.
     */
    fun getServerIp(context: Context, network: Network? = boundNetwork): String? {
        val net = network ?: return getGatewayIp(context)
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp = cm.getLinkProperties(net)
            if (lp != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val dhcpServer = lp.dhcpServerAddress?.hostAddress
                    if (!dhcpServer.isNullOrEmpty() && dhcpServer != "0.0.0.0") return dhcpServer
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    for (route in lp.routes) {
                        if (route.isDefaultRoute) {
                            val gateway = route.gateway?.hostAddress
                            if (!gateway.isNullOrEmpty() && gateway != "0.0.0.0") return gateway
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return getGatewayIp(context)
    }

    /**
     * Handshake with the receiver: tries GET /ping on each candidate IP over the
     * bound hotspot network until one answers "PONG" (up to ~12 seconds total).
     * Callbacks are delivered on the main thread.
     */
    fun verifyReceiver(
        context: Context,
        onReady: (serverIp: String) -> Unit,
        onFailed: () -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        val net = boundNetwork
        if (net == null) {
            mainHandler.post { onFailed() }
            return
        }

        Thread {
            val client = OkHttpClient.Builder()
                .socketFactory(net.socketFactory)
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()

            val port = TransferReceiverService.PORT
            val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
            var foundIp: String? = null

            while (foundIp == null && System.currentTimeMillis() < deadline) {
                // Rebuild candidates each round: link properties settle a
                // moment after the network first becomes available.
                val candidates = linkedSetOf<String>()
                getServerIp(context, net)?.let { candidates.add(it) }
                getGatewayIp(context)?.let { candidates.add(it) }
                candidates.add("192.168.49.1")

                for (ip in candidates) {
                    try {
                        val request = Request.Builder()
                            .url("http://$ip:$port/ping")
                            .get()
                            .build()
                        client.newCall(request).execute().use { resp ->
                            if (resp.isSuccessful && resp.body?.string()?.trim() == "PONG") {
                                foundIp = ip
                            }
                        }
                    } catch (_: Throwable) {}
                    if (foundIp != null) break
                }

                if (foundIp == null) {
                    try {
                        Thread.sleep(800)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }

            val result = foundIp
            mainHandler.post {
                if (result != null) onReady(result) else onFailed()
            }
        }.start()
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
