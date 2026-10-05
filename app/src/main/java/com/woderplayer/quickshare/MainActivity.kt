package com.woderplayer.quickshare

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var lvApps: ListView
    private lateinit var btnSend: Button
    private lateinit var btnReceive: Button
    private lateinit var btnPickCustomFile: Button
    private lateinit var qrContainer: View
    private lateinit var ivQrCode: ImageView
    private lateinit var progressBar: ProgressBar

    private var selectedFile: File? = null
    private var selectedUri: Uri? = null
    private var selectedFileName: String = ""
    private var selectedFileSize: Long = 0

    private val TRANSFER_PORT = 9876
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    data class AppItem(val name: String, val packageName: String, val apkPath: String)

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            result.data?.data?.let { uri ->
                selectedUri = uri
                selectedFile = null
                getUriDetails(uri)
                tvStatus.text = "Selected: $selectedFileName (${selectedFileSize / 1024} KB)"
                Toast.makeText(this, "Ready: $selectedFileName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val qrData = result.contents
            tvStatus.text = "Connecting to sender..."
            connectAndReceiveFile(qrData)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        lvApps = findViewById(R.id.lvApps)
        btnSend = findViewById(R.id.btnSend)
        btnReceive = findViewById(R.id.btnReceive)
        btnPickCustomFile = findViewById(R.id.btnPickCustomFile)
        qrContainer = findViewById(R.id.qrContainer)
        ivQrCode = findViewById(R.id.ivQrCode)
        progressBar = findViewById(R.id.progressBar)

        checkPermissions()
        loadInstalledApps()

        btnPickCustomFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            filePickerLauncher.launch(Intent.createChooser(intent, "Choose File to Send"))
        }

        btnSend.setOnClickListener {
            if (selectedFile == null && selectedUri == null) {
                Toast.makeText(this, "Select an app or file first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startSenderMode()
        }

        btnReceive.setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt("Scan Sender's MsenderM QR Code")
                setBeepEnabled(true)
                setOrientationLocked(true)
                captureActivity = com.journeyapps.barcodescanner.CaptureActivity::class.java
            }
            qrScannerLauncher.launch(options)
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val appList = mutableListOf<AppItem>()

        for (app in apps) {
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || app.packageName == packageName) {
                val label = pm.getApplicationLabel(app).toString()
                appList.add(AppItem(label, app.packageName, app.sourceDir))
            }
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            appList.map { "${it.name}\n${it.packageName}" }
        )
        lvApps.adapter = adapter

        lvApps.setOnItemClickListener { _, _, position, _ ->
            val selected = appList[position]
            val f = File(selected.apkPath)
            selectedFile = f
            selectedUri = null
            selectedFileName = "${selected.name.replace(" ", "_")}.apk"
            selectedFileSize = f.length()
            tvStatus.text = "Selected: $selectedFileName (${selectedFileSize / 1024} KB)"
            Toast.makeText(this, "Selected APK: ${selected.name}", Toast.LENGTH_SHORT).show()
        }
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
                        val password = config?.preSharedKey ?: ""
                        val ip = "192.168.43.1"

                        val qrPayload = "MSENDER|$ssid|$password|$ip|$TRANSFER_PORT"
                        showQrCode(qrPayload)
                        startSocketSenderServer()
                    }

                    override fun onFailed(reason: Int) {
                        super.onFailed(reason)
                        startManualHotspotFallback()
                    }
                }, null)
            } catch (e: Exception) {
                startManualHotspotFallback()
            }
        } else {
            startManualHotspotFallback()
        }
    }

    private fun startManualHotspotFallback() {
        val ip = "192.168.43.1"
        val qrPayload = "MSENDER|MANUAL||$ip|$TRANSFER_PORT"
        showQrCode(qrPayload)
        startSocketSenderServer()
        Toast.makeText(this, "Please enable phone Hotspot manually", Toast.LENGTH_LONG).show()
    }

    private fun showQrCode(content: String) {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        runOnUiThread {
            ivQrCode.setImageBitmap(bmp)
            qrContainer.visibility = View.VISIBLE
            tvStatus.text = "Hotspot active! Ask receiver to scan QR."
        }
    }

    private fun startSocketSenderServer() {
        thread {
            try {
                val serverSocket = ServerSocket(TRANSFER_PORT)
                val socket = serverSocket.accept()

                runOnUiThread {
                    tvStatus.text = "Receiver connected! Sending $selectedFileName..."
                    progressBar.visibility = View.VISIBLE
                }

                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                out.writeUTF(selectedFileName)
                out.writeLong(selectedFileSize)

                val input: InputStream = if (selectedFile != null) {
                    FileInputStream(selectedFile!!)
                } else {
                    contentResolver.openInputStream(selectedUri!!)!!
                }

                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var totalSent = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                    totalSent += bytesRead
                    val progress = ((totalSent * 100) / selectedFileSize).toInt()
                    runOnUiThread { progressBar.progress = progress }
                }

                out.flush()
                input.close()
                socket.close()
                serverSocket.close()

                runOnUiThread {
                    tvStatus.text = "Transfer completed successfully!"
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "File Sent!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Transfer Error: ${e.message}"
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun connectAndReceiveFile(qrData: String) {
        val parts = qrData.split("|")
        if (parts.size < 5 || parts[0] != "MSENDER") {
            Toast.makeText(this, "Invalid MsenderM QR Code!", Toast.LENGTH_SHORT).show()
            return
        }

        val targetIp = parts[3]
        val targetPort = parts[4].toIntOrNull() ?: TRANSFER_PORT

        thread {
            try {
                runOnUiThread {
                    tvStatus.text = "Connecting to $targetIp:$targetPort..."
                    progressBar.visibility = View.VISIBLE
                }

                val socket = Socket()
                socket.connect(InetSocketAddress(targetIp, targetPort), 10000)

                val inStream = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val fileName = inStream.readUTF()
                val fileSize = inStream.readLong()

                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val destFile = File(downloadDir, "MsenderM_$fileName")

                val out = FileOutputStream(destFile)
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var totalReceived = 0L

                while (totalReceived < fileSize && inStream.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                    totalReceived += bytesRead
                    val progress = ((totalReceived * 100) / fileSize).toInt()
                    runOnUiThread { progressBar.progress = progress }
                }

                out.flush()
                out.close()
                socket.close()

                runOnUiThread {
                    tvStatus.text = "Received: ${destFile.name}\nSaved to Downloads"
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Saved to Downloads/${destFile.name}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Receive Error: ${e.message}"
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun getUriDetails(uri: Uri) {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                selectedFileName = cursor.getString(nameIndex) ?: "file"
                selectedFileSize = cursor.getLong(sizeIndex)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hotspotReservation?.close()
    }
}
