package com.woderplayer.quickshare

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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

enum class Category { APPS, IMAGES, VIDEOS, AUDIO, DOCS }

data class MediaItem(
    val id: String,
    val name: String,
    val size: Long,
    val uri: Uri? = null,
    val file: File? = null,
    val category: Category
)

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvSelectedCount: TextView
    private lateinit var lvContent: ListView
    private lateinit var btnSend: ImageButton
    private lateinit var btnReceive: ImageButton
    private lateinit var btnCloseQr: Button
    private lateinit var qrContainer: View
    private lateinit var ivQrCode: ImageView
    private lateinit var progressContainer: View
    private lateinit var tvCurrentTransferFile: TextView
    private lateinit var tvTransferSpeed: TextView
    private lateinit var tvTransferPercent: TextView
    private lateinit var transferProgressBar: ProgressBar

    private lateinit var tabApps: Button
    private lateinit var tabImages: Button
    private lateinit var tabVideos: Button
    private lateinit var tabAudio: Button
    private lateinit var tabDocs: Button

    private var currentCategory = Category.APPS
    private val allCategoryItems = mutableListOf<MediaItem>()
    private val displayedItems = mutableListOf<MediaItem>()
    private val selectedItemsMap = LinkedHashMap<String, MediaItem>()
    private lateinit var adapter: FileListAdapter

    private val httpClient = OkHttpClient.Builder().build()

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
        lvContent = findViewById(R.id.lvContent)
        btnSend = findViewById(R.id.btnSend)
        btnReceive = findViewById(R.id.btnReceive)
        btnCloseQr = findViewById(R.id.btnCloseQr)
        qrContainer = findViewById(R.id.qrContainer)
        ivQrCode = findViewById(R.id.ivQrCode)
        progressContainer = findViewById(R.id.progressContainer)
        tvCurrentTransferFile = findViewById(R.id.tvCurrentTransferFile)
        tvTransferSpeed = findViewById(R.id.tvTransferSpeed)
        tvTransferPercent = findViewById(R.id.tvTransferPercent)
        transferProgressBar = findViewById(R.id.transferProgressBar)

        tabApps = findViewById(R.id.tabApps)
        tabImages = findViewById(R.id.tabImages)
        tabVideos = findViewById(R.id.tabVideos)
        tabAudio = findViewById(R.id.tabAudio)
        tabDocs = findViewById(R.id.tabDocs)

        adapter = FileListAdapter()
        lvContent.adapter = adapter

        setupTabs()
        checkPermissionsAndLoad()

        TransferReceiverService.onFileReceivedListener = { fileName, size ->
            runOnUiThread {
                val mb = String.format("%.2f", size / (1024.0 * 1024.0))
                tvStatus.text = "Received: $fileName ($mb MB)\nSaved in Downloads/MsenderM"
                Toast.makeText(this, "Received: $fileName", Toast.LENGTH_SHORT).show()
                loadMediaAsync()
            }
        }

        btnSend.setOnClickListener {
            if (selectedItemsMap.isEmpty()) {
                Toast.makeText(this, "Bhejne ke liye pehle items select karo!", Toast.LENGTH_SHORT).show()
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
            startReceiverEngine()
        }

        btnCloseQr.setOnClickListener {
            qrContainer.visibility = View.GONE
        }

        if (intent?.getBooleanExtra("EXTRA_START_RECEIVE", false) == true) {
            startReceiverEngine()
        }
    }

    private fun setupTabs() {
        val tabs = listOf(
            tabApps to Category.APPS,
            tabImages to Category.IMAGES,
            tabVideos to Category.VIDEOS,
            tabAudio to Category.AUDIO,
            tabDocs to Category.DOCS
        )

        for ((btn, cat) in tabs) {
            btn.setOnClickListener {
                currentCategory = cat
                updateTabStyles()
                filterDisplayedItems()
            }
        }
    }

    private fun updateTabStyles() {
        val tabs = listOf(
            tabApps to Category.APPS,
            tabImages to Category.IMAGES,
            tabVideos to Category.VIDEOS,
            tabAudio to Category.AUDIO,
            tabDocs to Category.DOCS
        )
        for ((btn, cat) in tabs) {
            if (cat == currentCategory) {
                btn.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_blue_bright)
                btn.setTextColor(Color.WHITE)
            } else {
                btn.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.darker_gray)
                btn.setTextColor(Color.LTGRAY)
            }
        }
    }

    private fun checkPermissionsAndLoad() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 102)
        } else {
            loadMediaAsync()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadMediaAsync()
    }

    private fun loadMediaAsync() {
        Thread {
            val list = mutableListOf<MediaItem>()
            val pm = packageManager
            for (app in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                if ((app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || app.packageName == packageName) {
                    val label = pm.getApplicationLabel(app).toString()
                    val f = File(app.sourceDir)
                    list.add(MediaItem("app_${app.packageName}", "$label.apk", f.length(), file = f, category = Category.APPS))
                }
            }

            queryMediaStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Category.IMAGES, list)
            queryMediaStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Category.VIDEOS, list)
            queryMediaStore(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Category.AUDIO, list)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                queryMediaStore(MediaStore.Downloads.EXTERNAL_CONTENT_URI, Category.DOCS, list)
            }

            runOnUiThread {
                allCategoryItems.clear()
                allCategoryItems.addAll(list)
                updateTabStyles()
                filterDisplayedItems()
            }
        }.start()
    }

    private fun queryMediaStore(uri: Uri, cat: Category, targetList: MutableList<MediaItem>) {
        try {
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
            contentResolver.query(uri, projection, null, null, "${MediaStore.MediaColumns._ID} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "file_$id"
                    val size = cursor.getLong(sizeCol)
                    val itemUri = ContentUris.withAppendedId(uri, id)
                    targetList.add(MediaItem("${cat}_$id", name, size, uri = itemUri, category = cat))
                }
            }
        } catch (_: Exception) {}
    }

    private fun filterDisplayedItems() {
        displayedItems.clear()
        displayedItems.addAll(allCategoryItems.filter { it.category == currentCategory })
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionHeader() {
        val totalMb = selectedItemsMap.values.sumOf { it.size } / (1024 * 1024)
        tvSelectedCount.text = "${selectedItemsMap.size} selected (~$totalMb MB)"
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
        tvStatus.text = "Receiver Active\nIP: http://$ip:$port"
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

    private fun handleScannedQr(payload: String) {
        val targetUrl = if (payload.startsWith("MSENDER|")) payload.removePrefix("MSENDER|") else payload
        startUploadStream("$targetUrl/upload")
    }

    private fun startUploadStream(uploadUrl: String) {
        val transferList = selectedItemsMap.values.toList()
        if (transferList.isEmpty()) return

        runOnUiThread {
            progressContainer.visibility = View.VISIBLE
            qrContainer.visibility = View.GONE
        }

        Thread {
            try {
                for ((index, item) in transferList.withIndex()) {
                    val inStream: InputStream? = if (item.file != null) {
                        item.file.inputStream()
                    } else if (item.uri != null) {
                        contentResolver.openInputStream(item.uri)
                    } else null

                    if (inStream == null) continue

                    val totalBytes = item.size
                    var transferredBytes = 0L
                    var lastTime = System.currentTimeMillis()
                    var bytesSinceLastTime = 0L

                    runOnUiThread {
                        tvCurrentTransferFile.text = "[${index + 1}/${transferList.size}] ${item.name}"
                        transferProgressBar.progress = 0
                    }

                    val streamingBody = object : RequestBody() {
                        override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()
                        override fun contentLength(): Long = totalBytes

                        override fun writeTo(sink: BufferedSink) {
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
                        throw Exception("Upload error HTTP ${response.code}")
                    }
                }

                // SUCCESS: Auto-untick transferred files
                runOnUiThread {
                    progressContainer.visibility = View.GONE
                    selectedItemsMap.clear()
                    updateSelectionHeader()
                    adapter.notifyDataSetChanged()
                    tvStatus.text = "All files transferred successfully!"
                    Toast.makeText(this@MainActivity, "Transfer complete! Selection cleared.", Toast.LENGTH_LONG).show()
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

    private fun openOrPlayFile(item: MediaItem) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val uriToOpen: Uri = when {
                item.uri != null -> item.uri
                item.file != null -> {
                    FileProvider.getUriForFile(this, "$packageName.provider", item.file)
                }
                else -> return
            }

            val mimeType = when (item.category) {
                Category.IMAGES -> "image/*"
                Category.VIDEOS -> "video/*"
                Category.AUDIO -> "audio/*"
                Category.APPS -> "application/vnd.android.package-archive"
                Category.DOCS -> {
                    val ext = MimeTypeMap.getFileExtensionFromUrl(item.name)
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "*/*"
                }
            }

            intent.setDataAndType(uriToOpen, mimeType)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    inner class FileListAdapter : BaseAdapter() {
        override fun getCount(): Int = displayedItems.size
        override fun getItem(pos: Int): Any = displayedItems[pos]
        override fun getItemId(pos: Int): Long = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_file, parent, false)
            val item = displayedItems[pos]

            val tvTitle = view.findViewById<TextView>(R.id.tvItemTitle)
            val tvSub = view.findViewById<TextView>(R.id.tvItemSub)
            val cb = view.findViewById<CheckBox>(R.id.cbSelected)

            tvTitle.text = item.name
            val mb = String.format("%.2f MB", item.size / (1024.0 * 1024.0))
            tvSub.text = mb
            cb.isChecked = selectedItemsMap.containsKey(item.id)

            cb.setOnClickListener {
                toggleSelection(item, cb)
            }

            view.setOnClickListener {
                openOrPlayFile(item)
            }

            view.setOnLongClickListener {
                toggleSelection(item, cb)
                true
            }

            return view
        }

        private fun toggleSelection(item: MediaItem, cb: CheckBox) {
            if (selectedItemsMap.containsKey(item.id)) {
                selectedItemsMap.remove(item.id)
            } else {
                selectedItemsMap[item.id] = item
            }
            cb.isChecked = selectedItemsMap.containsKey(item.id)
            updateSelectionHeader()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val serviceIntent = Intent(this, TransferReceiverService::class.java).apply {
            action = TransferReceiverService.ACTION_STOP
        }
        stopService(serviceIntent)
    }
}
