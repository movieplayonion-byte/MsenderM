package com.woderplayer.quickshare

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import com.woderplayer.quickshare.engine.NetworkUtils
import com.woderplayer.quickshare.engine.TransferReceiverService
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import java.io.File
import java.io.InputStream
import java.net.URLEncoder

data class TransferItem(val name: String, val size: Long, val file: File? = null, val uri: Uri? = null)
data class AppItem(val name: String, val packageName: String, val apkPath: String)

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvSelectedCount: TextView
    private lateinit var lvApps: ListView
    private lateinit var btnSend: Button
    private lateinit var btnReceive: Button
    private lateinit var btnPickCustomFile: Button
    private lateinit var qrContainer: View
    private lateinit var ivQrCode: ImageView
    private lateinit var progressContainer: View
    private lateinit var tvCurrentTransferFile: TextView
    private lateinit var tvTransferSpeed: TextView
    private lateinit var tvTransferPercent: TextView
    private lateinit var transferProgressBar: ProgressBar

    private val selectedItems = mutableListOf<TransferItem>()
    private val installedApps = mutableListOf<AppItem>()
    private val httpClient = OkHttpClient.Builder().build()

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
            handleScannedQr(result.contents)
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
        progressContainer = findViewById(R.id.progressContainer)
        tvCurrentTransferFile = findViewById(R.id.tvCurrentTransferFile)
        tvTransferSpeed = findViewById(R.id.tvTransferSpeed)
        tvTransferPercent = findViewById(R.id.tvTransferPercent)
        transferProgressBar = findViewById(R.id.transferProgressBar)

        loadInstalledApps()

        TransferReceiverService.onFileReceivedListener = { fileName, size ->
            runOnUiThread {
                val mb = String.format("%.2f", size / (1024.0 * 1024.0))
                tvStatus.text = "Received: $fileName ($mb MB)\nSaved in Downloads/MsenderM"
                Toast.makeText(this, "Received: $fileName", Toast.LENGTH_SHORT).show()
            }
        }

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
                Toast.makeText(this, "Pehle files ya apps select karo!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!checkAllPermissionsGranted()) {
                requestMissingPermissions()
                return@setOnClickListener
            }
            val options = ScanOptions().apply {
                setPrompt("Receiver ka QR Code Scan karo")
                setBeepEnabled(true)
                setOrientationLocked(true)
                captureActivity = com.journeyapps.barcodescanner.CaptureActivity::class.java
            }
            qrScannerLauncher.launch(options)
        }

        btnReceive.setOnClickListener {
            if (!checkAllPermissionsGranted()) {
                requestMissingPermissions()
                return@setOnClickListener
            }
            startReceiverEngine()
        }
    }

    private fun startReceiverEngine() {
        val serviceIntent = Intent(this, TransferReceiverService::class.java).apply {
            action = TransferReceiverService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val ip = NetworkUtils.getLocalIp()
        val port = TransferReceiverService.PORT
        val qrPayload = "MSENDER|http://$ip:$port"

        showQrCode(qrPayload)
        tvStatus.text = "Receiver Mode ON\nIP: http://$ip:$port"
    }

    private fun handleScannedQr(payload: String) {
        val targetUrl = if (payload.startsWith("MSENDER|")) {
            payload.removePrefix("MSENDER|")
        } else {
            payload
        }
        startUploadStream("$targetUrl/upload")
    }

    private fun startUploadStream(uploadUrl: String) {
        runOnUiThread {
            progressContainer.visibility = View.VISIBLE
            qrContainer.visibility = View.GONE
        }

        Thread {
            try {
                for ((index, item) in selectedItems.withIndex()) {
                    val totalBytes = item.size
                    var transferredBytes = 0L
                    var lastTime = System.currentTimeMillis()
                    var bytesSinceLastTime = 0L

                    runOnUiThread {
                        tvCurrentTransferFile.text = "[${index + 1}/${selectedItems.size}] ${item.name}"
                        transferProgressBar.progress = 0
                    }

                    val streamingBody = object : RequestBody() {
                        override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()
                        override fun contentLength(): Long = totalBytes

                        override fun writeTo(sink: BufferedSink) {
                            val inStream: InputStream? = if (item.file != null) {
                                item.file.inputStream()
                            } else if (item.uri != null) {
                                contentResolver.openInputStream(item.uri)
                            } else null

                            if (inStream == null) return

                            val buffer = ByteArray(64 * 1024)
                            try {
                                var read = inStream.read(buffer)
                                while (read != -1) {
                                    sink.write(buffer, 0, read)
                                    transferredBytes += read
                                    bytesSinceLastTime += read

                                    val now = System.currentTimeMillis()
                                    if (now - lastTime >= 500) {
                                        val timeDiffSec = (now - lastTime) / 1000.0
                                        val speedMb = if (timeDiffSec > 0) (bytesSinceLastTime / (1024.0 * 1024.0)) / timeDiffSec else 0.0
                                        val percent = if (totalBytes > 0) ((transferredBytes * 100) / totalBytes).toInt() else 0

                                        runOnUiThread {
                                            transferProgressBar.progress = percent
                                            tvTransferPercent.text = "$percent%"
                                            tvTransferSpeed.text = String.format("%.2f MB/s", speedMb)
                                        }
                                        lastTime = now
                                        bytesSinceLastTime = 0L
                                    }
                                    read = inStream.read(buffer)
                                }
                            } finally {
                                inStream.close()
                            }
                        }
                    }

                    val request = Request.Builder()
                        .url(uploadUrl)
                        .addHeader("x-filename", URLEncoder.encode(item.name, "UTF-8"))
                        .post(streamingBody)
                        .build()

                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        throw Exception("HTTP Error: ${response.code}")
                    }
                }

                runOnUiThread {
                    progressContainer.visibility = View.GONE
                    tvStatus.text = "Transfer Finished! All files sent."
                    Toast.makeText(this@MainActivity, "Files sent successfully!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressContainer.visibility = View.GONE
                    tvStatus.text = "Transfer Error: ${e.message}"
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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

    private fun checkAllPermissionsGranted(): Boolean {
        val list = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestMissingPermissions() {
        val list = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, list.toTypedArray(), 101)
    }

    override fun onDestroy() {
        super.onDestroy()
        val serviceIntent = Intent(this, TransferReceiverService::class.java).apply {
            action = TransferReceiverService.ACTION_STOP
        }
        stopService(serviceIntent)
    }
}
