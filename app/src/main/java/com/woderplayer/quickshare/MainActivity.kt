package com.woderplayer.quickshare

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.location.LocationManager
import android.net.*
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.*
import java.net.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvSelectedCount: TextView
    private lateinit var lvApps: ListView
    private lateinit var btnSend: Button
    private lateinit var btnReceive: Button
    private lateinit var btnPickCustomFile: Button
    private lateinit var qrContainer: View
    private lateinit var ivQrCode: ImageView
    private lateinit var progressBar: ProgressBar

    data class TransferItem(val name: String, val size: Long, val file: File? = null, val uri: Uri? = null)
    private val selectedItems = mutableListOf<TransferItem>()
    private val TRANSFER_PORT = 9876
    private var activeServerSocket: ServerSocket? = null
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    data class AppItem(val name: String, val packageName: String, val apkPath: String)
    private val installedApps = mutableListOf<AppItem>()

    private var activeDialog: BottomSheetDialog? = null
    private var isPrepForSender = true
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    private val multiFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data
            var count = 0
            if (data?.clipData != null) {
                val clip = data.clipData!!
                for (i in 0 until clip.itemCount) {
                    addItemFromUri(clip.getItemAt(i).uri)
                    count++
                }
            } else if (data?.data != null) {
                addItemFromUri(data.data!!)
                count++
            }
            updateSelectionStatus()
            Toast.makeText(this, "Added $count file(s)", Toast.LENGTH_SHORT).show()
        }
    }

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleReceivedQr(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        lvApps = findViewById(R.id.lvApps)
        btnSend = findViewById(R.id.btnSend)
        btnReceive = findViewById(R.id.btnReceive)
        btnPickCustomFile = findViewById(R.id.btnPickCustomFile)
        qrContainer = findViewById(R.id.qrContainer)
        ivQrCode = findViewById(R.id.ivQrCode)
        progressBar = findViewById(R.id.progressBar)

        loadInstalledApps()

        btnPickCustomFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            multiFilePickerLauncher.launch(Intent.createChooser(intent, "Select Files"))
        }

        btnSend.setOnClickListener {
            if (selectedItems.isEmpty()) {
                Toast.makeText(this, "Select files/apps first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isPrepForSender = true
            showPreparationsDialog(true)
        }

        btnReceive.setOnClickListener {
            isPrepForSender = false
            showPreparationsDialog(false)
        }
    }

    private fun handleReceivedQr(qrData: String) {
        val parts = qrData.split("|")
        if (parts.size < 5 || parts[0] != "MSENDER") {
            Toast.makeText(this, "Invalid QR Code!", Toast.LENGTH_SHORT).show()
            return
        }

        val ssid = parts[1]
        val password = parts[2]
        val targetIp = parts[3]
        val targetPort = parts[4].toIntOrNull() ?: TRANSFER_PORT

        if (ssid == "MANUAL" || ssid.isEmpty()) {
            connectAndReceiveBatch(targetIp, targetPort, null)
            return
        }

        tvStatus.text = "Connecting to $ssid..."
        connectToSenderWifi(ssid, password) { network ->
            connectAndReceiveBatch(targetIp, targetPort, network)
        }
    }

    private fun connectToSenderWifi(ssid: String, pass: String, onConnected: (Network?) -> Unit) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifier = if (pass.isNotEmpty()) {
                WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(pass).build()
            } else {
                WifiNetworkSpecifier.Builder().setSsid(ssid).build()
            }

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            connectivityCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    cm.bindProcessToNetwork(network)
                    runOnUiThread { tvStatus.text = "Connected! Starting transfer..." }
                    onConnected(network)
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    runOnUiThread { tvStatus.text = "Connect to $ssid manually in Wi-Fi settings" }
                    onConnected(null)
                }
            }
            connectivityCallback = callback
            cm.requestNetwork(request, callback, 15000)
        } else {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.disconnect()
            val conf = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (pass.isNotEmpty()) preSharedKey = "\"$pass\"" else allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            }
            val netId = wm.addNetwork(conf)
            wm.enableNetwork(netId, true)
            wm.reconnect()
            Thread.sleep(3000)
            onConnected(null)
        }
    }

    private fun showPreparationsDialog(forSender: Boolean) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_preparations, null)
        dialog.setContentView(view)
        activeDialog = dialog

        val tvWlanTitle = view.findViewById<TextView>(R.id.tvWlanTitle)
        val btnFixWlan = view.findViewById<Button>(R.id.btnFixWlan)
        val btnFixGps = view.findViewById<Button>(R.id.btnFixGps)
        val btnFixPerm = view.findViewById<Button>(R.id.btnFixPerm)
        val btnPrepNext = view.findViewById<Button>(R.id.btnPrepNext)

        if (forSender) {
            tvWlanTitle.text = "Open Hotspot (Portable AP)"
            btnFixWlan.setOnClickListener { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        } else {
            tvWlanTitle.text = "Enable Wi-Fi"
            btnFixWlan.setOnClickListener { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }

        btnFixGps.setOnClickListener { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        btnFixPerm.setOnClickListener { requestMissingPermissions() }

        btnPrepNext.setOnClickListener {
            dialog.dismiss()
            if (forSender) {
                startSenderMode()
            } else {
                val options = ScanOptions().apply {
                    setPrompt("Scan Sender QR Code")
                    setBeepEnabled(true)
                    setOrientationLocked(true)
                    captureActivity = com.journeyapps.barcodescanner.CaptureActivity::class.java
                }
                qrScannerLauncher.launch(options)
            }
        }

        refreshDialogStatus(dialog, forSender)
        dialog.show()
    }

    private fun refreshDialogStatus(dialog: BottomSheetDialog, forSender: Boolean) {
        val iconWlan = dialog.findViewById<TextView>(R.id.iconWlan) ?: return
        val iconGps = dialog.findViewById<TextView>(R.id.iconGps) ?: return
        val iconPerm = dialog.findViewById<TextView>(R.id.iconPerm) ?: return
        val btnPrepNext = dialog.findViewById<Button>(R.id.btnPrepNext) ?: return

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val wlanOk = if (forSender) true else wm.isWifiEnabled
        val gpsOk = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val permOk = checkAllPermissionsGranted()

        iconWlan.text = if (wlanOk) "✔" else "●"
        iconWlan.setTextColor(Color.parseColor(if (wlanOk) "#00E676" else "#FF5252"))
        iconGps.text = if (gpsOk) "✔" else "●"
        iconGps.setTextColor(Color.parseColor(if (gpsOk) "#00E676" else "#FF5252"))
        iconPerm.text = if (permOk) "✔" else "●"
        iconPerm.setTextColor(Color.parseColor(if (permOk) "#00E676" else "#FF5252"))

        val allReady = wlanOk && gpsOk && permOk
        btnPrepNext.isEnabled = allReady
        btnPrepNext.setBackgroundColor(Color.parseColor(if (allReady) "#00E676" else "#333D4E"))
        btnPrepNext.setTextColor(Color.parseColor(if (allReady) "#000000" else "#888888"))
    }

    private fun checkAllPermissionsGranted(): Boolean {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return list.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestMissingPermissions() {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, list.toTypedArray(), 101)
    }

    private fun startSenderMode() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        super.onStarted(reservation)
                        hotspotReservation = reservation
                        val config = reservation.wifiConfiguration
                        val ssid = config?.SSID ?: "MsenderM_Hotspot"
                        val pass = config?.preSharedKey ?: ""
                        val ip = "192.168.43.1"

                        val qrPayload = "MSENDER|$ssid|$pass|$ip|$TRANSFER_PORT"
                        showQrCode(qrPayload)
                        startSocketSenderServer()
                    }

                    override fun onFailed(reason: Int) {
                        super.onFailed(reason)
                        fallbackSenderManual()
                    }
                }, null)
            } catch (e: Exception) {
                fallbackSenderManual()
            }
        } else {
            fallbackSenderManual()
        }
    }

    private fun fallbackSenderManual() {
        val ip = "192.168.43.1"
        val qrPayload = "MSENDER|MANUAL||$ip|$TRANSFER_PORT"
        showQrCode(qrPayload)
        startSocketSenderServer()
        Toast.makeText(this, "Hotspot active! Keep hotspot ON.", Toast.LENGTH_LONG).show()
    }

    private fun startSocketSenderServer() {
        try { activeServerSocket?.close() } catch (_: Exception) {}

        thread {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(TRANSFER_PORT))
                activeServerSocket = server

                runOnUiThread { tvStatus.text = "Sender Ready!\nWaiting for receiver..." }

                val client = server.accept()
                val totalBatch = selectedItems.size

                runOnUiThread {
                    tvStatus.text = "Sending $totalBatch item(s)..."
                    progressBar.visibility = View.VISIBLE
                }

                val out = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
                out.writeInt(totalBatch)

                for ((idx, item) in selectedItems.withIndex()) {
                    runOnUiThread { tvStatus.text = "Sending [${idx + 1}/$totalBatch]: ${item.name}" }
                    out.writeUTF(item.name)
                    out.writeLong(item.size)

                    val stream: InputStream = if (item.file != null) FileInputStream(item.file) else contentResolver.openInputStream(item.uri!!)!!
                    val buffer = ByteArray(64 * 1024)
                    var read = 0
                    var sent = 0L

                    while (stream.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        sent += read
                        val prog = if (item.size > 0) ((sent * 100) / item.size).toInt() else 0
                        runOnUiThread { progressBar.progress = prog }
                    }
                    out.flush()
                    stream.close()
                }

                client.close()
                server.close()
                activeServerSocket = null

                runOnUiThread {
                    tvStatus.text = "All items sent successfully!"
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Files Sent!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Transfer Error: ${e.message}"
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun connectAndReceiveBatch(targetIp: String, targetPort: Int, network: Network?) {
        thread {
            try {
                runOnUiThread {
                    tvStatus.text = "Connecting to sender socket..."
                    progressBar.visibility = View.VISIBLE
                }

                val socket = if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val s = network.socketFactory.createSocket()
                    s.connect(InetSocketAddress(targetIp, targetPort), 10000)
                    s
                } else {
                    val s = Socket()
                    s.connect(InetSocketAddress(targetIp, targetPort), 10000)
                    s
                }

                val inStream = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val totalBatch = inStream.readInt()
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

                for (i in 0 until totalBatch) {
                    val rawName = inStream.readUTF()
                    val size = inStream.readLong()

                    runOnUiThread { tvStatus.text = "Receiving [${i + 1}/$totalBatch]: $rawName" }

                    var destFile = File(dir, "MsenderM_$rawName")
                    var counter = 1
                    while (destFile.exists()) {
                        destFile = File(dir, "MsenderM_${counter}_$rawName")
                        counter++
                    }

                    val out = FileOutputStream(destFile)
                    val buffer = ByteArray(64 * 1024)
                    var read = 0
                    var received = 0L

                    while (received < size) {
                        val toRead = Math.min(buffer.size.toLong(), size - received).toInt()
                        read = inStream.read(buffer, 0, toRead)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        received += read
                        val prog = if (size > 0) ((received * 100) / size).toInt() else 0
                        runOnUiThread { progressBar.progress = prog }
                    }
                    out.flush()
                    out.close()
                }

                socket.close()
                runOnUiThread {
                    tvStatus.text = "All items received in Downloads!"
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Received successfully!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Receive Error: ${e.message}"
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun showQrCode(content: String) {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (x in 0 until 512) {
            for (y in 0 until 512) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        runOnUiThread {
            ivQrCode.setImageBitmap(bmp)
            qrContainer.visibility = View.VISIBLE
        }
    }

    private fun addItemFromUri(uri: Uri) {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                selectedItems.add(TransferItem(c.getString(nIdx) ?: "file", c.getLong(sIdx), uri = uri))
            }
        }
    }

    private fun updateSelectionStatus() {
        val totalMb = selectedItems.sumOf { it.size } / (1024 * 1024)
        tvSelectedCount.text = "Selected: ${selectedItems.size} items (~$totalMb MB)"
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        installedApps.clear()
        for (app in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || app.packageName == packageName) {
                installedApps.add(AppItem(pm.getApplicationLabel(app).toString(), app.packageName, app.sourceDir))
            }
        }
        lvApps.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, installedApps.map { it.name })
        lvApps.setOnItemClickListener { _, _, pos, _ ->
            val item = installedApps[pos]
            val f = File(item.apkPath)
            val name = "${item.name.replace(" ", "_")}.apk"
            if (lvApps.isItemChecked(pos)) {
                selectedItems.add(TransferItem(name, f.length(), file = f))
            } else {
                selectedItems.removeAll { it.name == name }
            }
            updateSelectionStatus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { activeServerSocket?.close() } catch (_: Exception) {}
        hotspotReservation?.close()
        connectivityCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
    }
}
s and ApplicationInfo.FLAG_SYSTEM) == 0 || app.packageName == packageName) {
                installedApps.add(AppItem(pm.getApplicationLabel(app).toString(), app.packageName, app.sourceDir))
            }
        }
        lvApps.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, installedApps.map { it.name })
        lvApps.setOnItemClickListener { _, _, pos, _ ->
            val item = installedApps[pos]
            val f = File(item.apkPath)
            val name = "${item.name.replace(" ", "_")}.apk"
            if (lvApps.isItemChecked(pos)) {
                selectedItems.add(TransferItem(name, f.length(), file = f))
            } else {
                selectedItems.removeAll { it.name == name }
            }
            updateSelectionStatus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { activeServerSocket?.close() } catch (_: Exception) {}
        hotspotReservation?.close()
    }
}
