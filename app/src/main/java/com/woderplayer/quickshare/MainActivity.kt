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
import com.woderplayer.quickshare.server.FileServerService
import com.woderplayer.quickshare.server.NetworkHelper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

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
    private lateinit var progressBar: ProgressBar

    private val selectedItems = mutableListOf<TransferItem>()
    private val installedApps = mutableListOf<AppItem>()
    private val okHttpClient = OkHttpClient()

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
            if (!checkAllPermissionsGranted()) {
                requestMissingPermissions()
                return@setOnClickListener
            }
            val options = ScanOptions().apply {
                setPrompt("Scan Receiver's QR Code")
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
            startReceiverMode()
        }
    }

    private fun startReceiverMode() {
        val serviceIntent = Intent(this, FileServerService::class.java).apply {
            action = FileServerService.ACTION_START_SERVER
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val localIp = NetworkHelper.getLocalIpAddress(this) ?: "127.0.0.1"
        val port = 8080
        val qrPayload = "MSENDER|http://$localIp:$port"

        showQrCode(qrPayload)
        tvStatus.text = "Receiver Ready!\nIP: http://$localIp:$port\nWaiting for Sender..."
    }

    private fun handleScannedQr(payload: String) {
        val targetUrl = if (payload.startsWith("MSENDER|")) {
            payload.removePrefix("MSENDER|")
        } else {
            payload
        }

        uploadFilesToReceiver("$targetUrl/upload")
    }

    private fun uploadFilesToReceiver(uploadUrl: String) {
        runOnUiThread {
            tvStatus.text = "Uploading to: $uploadUrl"
            progressBar.visibility = View.VISIBLE
        }

        Thread {
            try {
                for ((index, item) in selectedItems.withIndex()) {
                    runOnUiThread {
                        tvStatus.text = "Sending [${index + 1}/${selectedItems.size}]: ${item.name}"
                    }

                    val requestBodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)

                    val fileBytes = if (item.file != null) {
                        item.file.readBytes()
                    } else if (item.uri != null) {
                        contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
                    } else null

                    if (fileBytes != null) {
                        val body = fileBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                        requestBodyBuilder.addFormDataPart("file", item.name, body)

                        val request = Request.Builder()
                            .url(uploadUrl)
                            .post(requestBodyBuilder.build())
                            .build()

                        val response = okHttpClient.newCall(request).execute()
                        if (!response.isSuccessful) {
                            throw IOException("Failed to send: ${response.code}")
                        }
                    }
                }

                runOnUiThread {
                    tvStatus.text = "All items sent successfully!"
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Files Sent!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Transfer Error: ${e.message}"
                    progressBar.visibility = View.GONE
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
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestMissingPermissions() {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, list.toTypedArray(), 101)
    }

    override fun onDestroy() {
        super.onDestroy()
        val serviceIntent = Intent(this, FileServerService::class.java).apply {
            action = FileServerService.ACTION_STOP_SERVER
        }
        stopService(serviceIntent)
    }
}
