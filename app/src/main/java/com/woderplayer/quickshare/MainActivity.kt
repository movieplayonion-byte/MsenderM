package com.woderplayer.quickshare

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
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

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvSelectedCount: TextView
    private lateinit var gvContent: GridView
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

    private lateinit var gridAdapter: GridMediaAdapter
    private lateinit var listAdapter: AudioListAdapter
    private val httpClient = OkHttpClient.Builder().build()

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) handleScannedQr(result.contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        gvContent = findViewById(R.id.gvContent)
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

        gridAdapter = GridMediaAdapter()
        listAdapter = AudioListAdapter()
        gvContent.adapter = gridAdapter
        lvContent.adapter = listAdapter

        setupTabs()
        checkPermissionsAndLoad()
        AppUpdater.check(this, httpClient)

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
                Toast.makeText(this, "Select items to send!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val options = ScanOptions().apply {
                setPrompt("Scan receiver QR code")
                setBeepEnabled(true)
                setOrientationLocked(true)
                captureActivity = com.journeyapps.barcodescanner.CaptureActivity::class.java
            }
            qrScannerLauncher.launch(options)
        }

        btnReceive.setOnClickListener { startReceiverEngine() }
        btnCloseQr.setOnClickListener { qrContainer.visibility = View.GONE }

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
        if (currentCategory == Category.AUDIO) {
            gvContent.visibility = View.GONE
            lvContent.visibility = View.VISIBLE
        } else {
            gvContent.visibility = View.VISIBLE
            lvContent.visibility = View.GONE
        }
    }

    private fun checkPermissionsAndLoad() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 102)
        } else {
            loadMediaAsync()
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, p, res)
        loadMediaAsync()
    }

    private fun loadMediaAsync() {
        Thread {
            val list = mutableListOf<MediaItem>()
            val pm = packageManager
            for (app in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                if ((app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || app.packageName == packageName) {
                    val label = pm.getApplicationLabel(app).toString()
                    val icon = pm.getApplicationIcon(app)
                    val f = File(app.sourceDir)
                    list.add(MediaItem("app_inst_${app.packageName}", "$label.apk", f.length(), file = f, category = Category.APPS, isInstalledApp = true, appIcon = icon))
                }
            }
            scanApkFiles(list)
            queryMediaStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Category.IMAGES, list)
            queryMediaStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Category.VIDEOS, list)
            queryMediaStore(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Category.AUDIO, list)
            scanStrictDocuments(list)

            runOnUiThread {
                allCategoryItems.clear()
                allCategoryItems.addAll(list)
                updateTabStyles()
                filterDisplayedItems()
            }
        }.start()
    }

    private fun scanApkFiles(targetList: MutableList<MediaItem>) {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.walkTopDown().filter { it.isFile && it.extension.equals("apk", true) }.take(50).forEach { f ->
                targetList.add(MediaItem("app_uninst_${f.absolutePath}", f.name, f.length(), file = f, category = Category.APPS))
            }
        } catch (_: Exception) {}
    }

    private fun scanStrictDocuments(targetList: MutableList<MediaItem>) {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val docExts = setOf("pdf", "doc", "docx", "txt", "xlsx", "xls", "ppt", "pptx", "zip", "rar")
            dir.walkTopDown().filter { it.isFile && docExts.contains(it.extension.lowercase()) }.take(100).forEach { f ->
                targetList.add(MediaItem("doc_${f.absolutePath}", f.name, f.length(), file = f, category = Category.DOCS))
            }
        } catch (_: Exception) {}
    }

    private fun queryMediaStore(uri: Uri, cat: Category, targetList: MutableList<MediaItem>) {
        try {
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
            contentResolver.query(uri, proj, null, null, "${MediaStore.MediaColumns._ID} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nCol) ?: "file_$id"
                    val size = cursor.getLong(sCol)
                    val itemUri = ContentUris.withAppendedId(uri, id)
                    targetList.add(MediaItem("${cat}_$id", name, size, uri = itemUri, category = cat))
                }
            }
        } catch (_: Exception) {}
    }

    private fun filterDisplayedItems() {
        displayedItems.clear()
        displayedItems.addAll(allCategoryItems.filter { it.category == currentCategory })
        gridAdapter.notifyDataSetChanged()
        listAdapter.notifyDataSetChanged()
    }

    private fun updateSelectionHeader() {
        val totalMb = selectedItemsMap.values.sumOf { it.size } / (1024 * 1024)
        tvSelectedCount.text = "${selectedItemsMap.size} selected (~$totalMb MB)"
    }

    private fun startReceiverEngine() {
        val intent = Intent(this, TransferReceiverService::class.java).apply {
            action = TransferReceiverService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        val ip = NetworkUtils.getLocalIp()
        val port = TransferReceiverService.PORT
        showQrCode("MSENDER|http://$ip:$port")
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
                    val inStream: InputStream? = if (item.file != null) item.file.inputStream()
                    else if (item.uri != null) contentResolver.openInputStream(item.uri) else null

                    if (inStream == null) continue
                    val totalBytes = item.size
                    var transferred = 0L
                    var lastTime = System.currentTimeMillis()
                    var bytesSince = 0L

                    runOnUiThread {
                        tvCurrentTransferFile.text = "[${index + 1}/${transferList.size}] ${item.name}"
                        transferProgressBar.progress = 0
                    }

                    val body = object : RequestBody() {
                        override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()
                        override fun contentLength(): Long = totalBytes
                        override fun writeTo(sink: BufferedSink) {
                            val buf = ByteArray(64 * 1024)
                            try {
                                var r = inStream.read(buf)
                                while (r != -1) {
                                    sink.write(buf, 0, r)
                                    transferred += r
                                    bytesSince += r
                                    val now = System.currentTimeMillis()
                                    if (now - lastTime >= 500) {
                                        val sec = (now - lastTime) / 1000.0
                                        val mbSpeed = if (sec > 0) (bytesSince / (1024.0 * 1024.0)) / sec else 0.0
                                        val pct = if (totalBytes > 0) ((transferred * 100) / totalBytes).toInt() else 0
                                        runOnUiThread {
                                            transferProgressBar.progress = pct
                                            tvTransferPercent.text = "$pct%"
                                            tvTransferSpeed.text = String.format("%.2f MB/s", mbSpeed)
                                        }
                                        lastTime = now
                                        bytesSince = 0L
                                    }
                                    r = inStream.read(buf)
                                }
                            } finally { inStream.close() }
                        }
                    }

                    val req = Request.Builder()
                        .url(uploadUrl)
                        .addHeader("x-filename", URLEncoder.encode(item.name, "UTF-8"))
                        .post(body)
                        .build()

                    val resp = httpClient.newCall(req).execute()
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                }

                runOnUiThread {
                    progressContainer.visibility = View.GONE
                    selectedItemsMap.clear()
                    updateSelectionHeader()
                    gridAdapter.notifyDataSetChanged()
                    listAdapter.notifyDataSetChanged()
                    tvStatus.text = "All files transferred!"
                    Toast.makeText(this@MainActivity, "Files sent successfully!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressContainer.visibility = View.GONE
                    tvStatus.text = "Error: ${e.message}"
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun toggleSelection(item: MediaItem) {
        if (selectedItemsMap.containsKey(item.id)) selectedItemsMap.remove(item.id)
        else selectedItemsMap[item.id] = item
        updateSelectionHeader()
        gridAdapter.notifyDataSetChanged()
        listAdapter.notifyDataSetChanged()
    }

    inner class GridMediaAdapter : BaseAdapter() {
        override fun getCount(): Int = displayedItems.size
        override fun getItem(p: Int): Any = displayedItems[p]
        override fun getItemId(p: Int): Long = p.toLong()

        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val v = cv ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_grid_media, parent, false)
            val item = displayedItems[p]
            val iv = v.findViewById<ImageView>(R.id.ivThumbnail)
            val tv = v.findViewById<TextView>(R.id.tvGridTitle)
            val cb = v.findViewById<CheckBox>(R.id.cbGridSelected)

            tv.text = item.name
            cb.isChecked = selectedItemsMap.containsKey(item.id)

            when (item.category) {
                Category.APPS -> {
                    if (item.appIcon != null) iv.setImageDrawable(item.appIcon)
                    else iv.setImageResource(android.R.drawable.sym_def_app_icon)
                }
                Category.IMAGES -> {
                    if (item.uri != null) iv.setImageURI(item.uri)
                    else if (item.file != null) iv.setImageURI(Uri.fromFile(item.file))
                }
                Category.VIDEOS -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && item.uri != null) {
                            iv.setImageBitmap(contentResolver.loadThumbnail(item.uri, Size(120, 120), null))
                        } else if (item.file != null) {
                            iv.setImageBitmap(ThumbnailUtils.createVideoThumbnail(item.file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND))
                        }
                    } catch (_: Exception) { iv.setImageResource(android.R.drawable.ic_media_play) }
                }
                Category.DOCS -> iv.setImageResource(android.R.drawable.ic_menu_agenda)
                else -> iv.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            cb.setOnClickListener { toggleSelection(item) }
            v.setOnClickListener { MediaViewer.open(this@MainActivity, item) }
            return v
        }
    }

    inner class AudioListAdapter : BaseAdapter() {
        override fun getCount(): Int = displayedItems.size
        override fun getItem(p: Int): Any = displayedItems[p]
        override fun getItemId(p: Int): Long = p.toLong()

        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val v = cv ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_file, parent, false)
            val item = displayedItems[p]
            v.findViewById<TextView>(R.id.tvItemTitle).text = item.name
            v.findViewById<TextView>(R.id.tvItemSub).text = String.format("%.2f MB", item.size / (1024.0 * 1024.0))
            val cb = v.findViewById<CheckBox>(R.id.cbSelected)
            cb.isChecked = selectedItemsMap.containsKey(item.id)
            cb.setOnClickListener { toggleSelection(item) }
            v.setOnClickListener { MediaViewer.open(this@MainActivity, item) }
            return v
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        MediaViewer.release()
        stopService(Intent(this, TransferReceiverService::class.java).apply { action = TransferReceiverService.ACTION_STOP })
    }
}
