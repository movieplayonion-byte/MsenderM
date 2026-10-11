package com.woderplayer.quickshare

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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
    private lateinit var etFuzzySearch: EditText
    private lateinit var gvContent: GridView
    private lateinit var lvContent: ListView
    private lateinit var btnSend: ImageButton
    private lateinit var btnReceive: ImageButton
    private lateinit var btnSelectAll: Button
    private lateinit var btnDeleteSelected: Button

    private lateinit var filesSubBar: View
    private lateinit var btnInternalStorage: Button
    private lateinit var btnBigFiles: Button

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
    private lateinit var tabFiles: Button

    private var currentCategory = Category.APPS
    private var isBigFilesMode = false
    private var currentFolder: File = Environment.getExternalStorageDirectory()

    private val categoryCache = mutableMapOf<Category, List<MediaItem>>()
    private val displayedItems = mutableListOf<MediaItem>()
    private val selectedItemsMap = LinkedHashMap<String, MediaItem>()
    private var currentSearchQuery = ""

    private lateinit var gridAdapter: GridMediaAdapter
    private lateinit var listAdapter: GenericListAdapter
    private val httpClient = OkHttpClient.Builder().build()

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) handleScannedQr(result.contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        etFuzzySearch = findViewById(R.id.etFuzzySearch)
        gvContent = findViewById(R.id.gvContent)
        lvContent = findViewById(R.id.lvContent)
        btnSend = findViewById(R.id.btnSend)
        btnReceive = findViewById(R.id.btnReceive)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)

        filesSubBar = findViewById(R.id.filesSubBar)
        btnInternalStorage = findViewById(R.id.btnInternalStorage)
        btnBigFiles = findViewById(R.id.btnBigFiles)

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
        tabFiles = findViewById(R.id.tabFiles)

        gridAdapter = GridMediaAdapter()
        listAdapter = GenericListAdapter()
        gvContent.adapter = gridAdapter
        lvContent.adapter = listAdapter

        setupTabs()
        setupFuzzySearch()
        setupSelectionAndActions()
        checkPermissionsAndLoad()
        AppUpdater.check(this, httpClient)

        TransferReceiverService.onFileReceivedListener = { fileName, size ->
            runOnUiThread {
                val mb = String.format("%.2f", size / (1024.0 * 1024.0))
                tvStatus.text = "Received: $fileName ($mb MB)\nSaved in Downloads/MsenderM"
                Toast.makeText(this, "Received: $fileName", Toast.LENGTH_SHORT).show()
                categoryCache.clear()
                loadCategoryAsync(currentCategory)
            }
        }

        btnSend.setOnClickListener {
            if (selectedItemsMap.isEmpty()) {
                Toast.makeText(this, "Select items to send!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            NetworkRequirements.ensureSender(this) {
                val options = ScanOptions().apply {
                    setPrompt("Scan receiver QR code")
                    setBeepEnabled(true)
                    setOrientationLocked(true)
                    captureActivity = com.journeyapps.barcodescanner.CaptureActivity::class.java
                }
                qrScannerLauncher.launch(options)
            }
        }

        btnReceive.setOnClickListener {
            NetworkRequirements.ensureReceiverRequirements(this) { startActivity(Intent(this, ReceiveActivity::class.java)) }
        }

        if (intent?.getBooleanExtra("EXTRA_START_RECEIVE", false) == true) {
            NetworkRequirements.ensureReceiverRequirements(this) { startActivity(Intent(this, ReceiveActivity::class.java)) }
        }
    }

    private fun setupSelectionAndActions() {
        btnSelectAll.setOnClickListener {
            if (displayedItems.isEmpty()) return@setOnClickListener
            val allSelected = displayedItems.all { selectedItemsMap.containsKey(it.id) }
            if (allSelected) {
                displayedItems.forEach { selectedItemsMap.remove(it.id) }
            } else {
                displayedItems.forEach {
                    if (!it.isDirectory) selectedItemsMap[it.id] = it
                }
            }
            updateSelectionHeader()
            gridAdapter.notifyDataSetChanged()
            listAdapter.notifyDataSetChanged()
        }

        btnDeleteSelected.setOnClickListener {
            if (selectedItemsMap.isEmpty()) {
                Toast.makeText(this, "No files selected to delete", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Delete Files")
                .setMessage("Are you sure you want to permanently delete ${selectedItemsMap.size} selected file(s)?")
                .setPositiveButton("Delete") { _, _ ->
                    deleteSelectedFiles()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnInternalStorage.setOnClickListener {
            isBigFilesMode = false
            btnInternalStorage.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_blue_bright)
            btnBigFiles.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.darker_gray)
            loadCategoryAsync(Category.FILES)
        }

        btnBigFiles.setOnClickListener {
            isBigFilesMode = true
            btnBigFiles.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_blue_bright)
            btnInternalStorage.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.darker_gray)
            loadCategoryAsync(Category.FILES)
        }
    }

    private fun deleteSelectedFiles() {
        Thread {
            var deletedCount = 0
            for (item in selectedItemsMap.values.toList()) {
                try {
                    var success = false
                    if (item.file != null && item.file.exists()) {
                        success = item.file.delete()
                    } else if (item.uri != null) {
                        val rows = contentResolver.delete(item.uri, null, null)
                        success = rows > 0
                    }
                    if (success) {
                        deletedCount++
                        selectedItemsMap.remove(item.id)
                    }
                } catch (_: Exception) {}
            }
            runOnUiThread {
                Toast.makeText(this, "Deleted $deletedCount file(s)", Toast.LENGTH_SHORT).show()
                categoryCache.clear()
                updateSelectionHeader()
                loadCategoryAsync(currentCategory)
            }
        }.start()
    }

    private fun setupFuzzySearch() {
        etFuzzySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                filterDisplayedItems()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupTabs() {
        val tabs = listOf(
            tabApps to Category.APPS,
            tabImages to Category.IMAGES,
            tabVideos to Category.VIDEOS,
            tabAudio to Category.AUDIO,
            tabDocs to Category.DOCS,
            tabFiles to Category.FILES
        )
        for ((btn, cat) in tabs) {
            btn.setOnClickListener {
                currentCategory = cat
                updateTabStyles()
                loadCategoryAsync(cat)
            }
        }
    }

    private fun updateTabStyles() {
        val tabs = listOf(
            tabApps to Category.APPS,
            tabImages to Category.IMAGES,
            tabVideos to Category.VIDEOS,
            tabAudio to Category.AUDIO,
            tabDocs to Category.DOCS,
            tabFiles to Category.FILES
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

        filesSubBar.visibility = if (currentCategory == Category.FILES) View.VISIBLE else View.GONE

        // Sirf Photos aur Videos Grid me rahenge, Apps, Audio, DOCS aur FILES hamesha clean LIST me rahenge!
        if (currentCategory == Category.IMAGES || currentCategory == Category.VIDEOS) {
            gvContent.visibility = View.VISIBLE
            lvContent.visibility = View.GONE
        } else {
            gvContent.visibility = View.GONE
            lvContent.visibility = View.VISIBLE
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
            checkStorageManagerPermission()
        }
    }

    private fun checkStorageManagerPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    Toast.makeText(this, "Enable All Files Access for reading Documents & Storage!", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}
            }
        }
        loadCategoryAsync(currentCategory)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, p, res)
        checkStorageManagerPermission()
    }

    private fun loadCategoryAsync(cat: Category) {
        if (cat == Category.FILES) {
            Thread {
                val list = if (isBigFilesMode) {
                    MediaScanner.loadBigFiles(this)
                } else {
                    MediaScanner.loadDirectory(currentFolder)
                }
                runOnUiThread {
                    displayedItems.clear()
                    displayedItems.addAll(list)
                    filterDisplayedItems()
                }
            }.start()
            return
        }

        if (categoryCache.containsKey(cat)) {
            filterDisplayedItems()
            return
        }

        Thread {
            val list = when (cat) {
                Category.APPS -> MediaScanner.loadApps(this)
                Category.IMAGES -> MediaScanner.loadImages(this)
                Category.VIDEOS -> MediaScanner.loadVideos(this)
                Category.AUDIO -> MediaScanner.loadAudio(this)
                Category.DOCS -> MediaScanner.loadDocuments(this)
                else -> emptyList()
            }
            categoryCache[cat] = list
            runOnUiThread {
                if (currentCategory == cat) {
                    filterDisplayedItems()
                }
            }
        }.start()
    }

    private fun filterDisplayedItems() {
        val rawList = if (currentCategory == Category.FILES) displayedItems.toList() else categoryCache[currentCategory] ?: emptyList()
        displayedItems.clear()
        if (currentSearchQuery.isEmpty()) {
            displayedItems.addAll(rawList)
        } else {
            displayedItems.addAll(rawList.filter { FuzzySearcher.matches(currentSearchQuery, it.name) })
        }
        gridAdapter.notifyDataSetChanged()
        listAdapter.notifyDataSetChanged()
    }

    private fun updateSelectionHeader() {
        val totalMb = selectedItemsMap.values.sumOf { it.size } / (1024 * 1024)
        tvSelectedCount.text = "${selectedItemsMap.size} selected (~$totalMb MB)"
    }

            private fun handleScannedQr(payload: String) {
        try {
            var hostUrl = "http://192.168.43.1:8080"

            if (payload.startsWith("WIFI:")) {
                var ssid = ""
                var pass = ""
                val clean = payload.removePrefix("WIFI:").removeSuffix(";;").removeSuffix(";")
                val tokens = clean.split(";")
                for (t in tokens) {
                    if (t.startsWith("S:")) ssid = t.removePrefix("S:")
                    if (t.startsWith("P:")) pass = t.removePrefix("P:")
                    if (t.startsWith("H:")) {
                        val h = t.removePrefix("H:")
                        hostUrl = if (h.startsWith("http://") || h.startsWith("https://")) h else "http://$h"
                    }
                }

                if (ssid.isNotEmpty()) {
                    Toast.makeText(this, "Connecting to $ssid...", Toast.LENGTH_SHORT).show()
                    WifiAutoConnector.connectToHotspot(applicationContext, ssid, pass, {
                        runOnUiThread { startUploadStream("$hostUrl/upload") }
                    }, {
                        runOnUiThread {
                            Toast.makeText(this, "Direct connection...", Toast.LENGTH_SHORT).show()
                            startUploadStream("$hostUrl/upload")
                        }
                    })
                    return
                }
            } else if (payload.startsWith("http://") || payload.startsWith("https://")) {
                hostUrl = payload
            } else {
                hostUrl = "http://$payload"
            }

            startUploadStream("$hostUrl/upload")
        } catch (e: Throwable) {
            Toast.makeText(this, "Scan Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startUploadStream(uploadUrl: String) {
        val transferList = selectedItemsMap.values.toList()
        if (transferList.isEmpty()) return

        runOnUiThread { progressContainer.visibility = View.VISIBLE }

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
                    tvStatus.text = "Transfer Finished! Selection Cleared."
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
        if (item.isDirectory) return
        if (selectedItemsMap.containsKey(item.id)) selectedItemsMap.remove(item.id)
        else selectedItemsMap[item.id] = item
        updateSelectionHeader()
        gridAdapter.notifyDataSetChanged()
        listAdapter.notifyDataSetChanged()
    }

    private fun openPreview(position: Int) {
        val item = displayedItems.getOrNull(position) ?: return

        if (item.isDirectory && item.file != null) {
            currentFolder = item.file
            loadCategoryAsync(Category.FILES)
            return
        }

        if (item.category == Category.APPS) {
            item.file?.let { MediaViewer.installApk(this, it) }
            return
        }
        MediaPreviewActivity.previewList = displayedItems
        MediaPreviewActivity.initialIndex = position
        startActivity(Intent(this, MediaPreviewActivity::class.java))
    }

    override fun onBackPressed() {
        if (currentCategory == Category.FILES && !isBigFilesMode && currentFolder != Environment.getExternalStorageDirectory()) {
            currentFolder.parentFile?.let {
                currentFolder = it
                loadCategoryAsync(Category.FILES)
                return
            }
        }
        super.onBackPressed()
    }

    inner class GridMediaAdapter : BaseAdapter() {
        override fun getCount(): Int = displayedItems.size
        override fun getItem(p: Int): Any = displayedItems[p]
        override fun getItemId(p: Int): Long = p.toLong()

        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val v = cv ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_grid_media, parent, false)
            val item = displayedItems[p]
            val iv = v.findViewById<ImageView>(R.id.ivThumbnail)
            val badge = v.findViewById<ImageView>(R.id.ivPlayBadge)
            val cb = v.findViewById<CheckBox>(R.id.cbGridSelected)

            cb.isChecked = selectedItemsMap.containsKey(item.id)
            badge.visibility = if (item.category == Category.VIDEOS) View.VISIBLE else View.GONE

            val model = item.uri ?: item.file
            Glide.with(this@MainActivity)
                .load(model)
                .centerCrop()
                .placeholder(android.R.drawable.ic_menu_gallery)
                .into(iv)

            cb.setOnClickListener { toggleSelection(item) }
            v.setOnClickListener { openPreview(p) }
            return v
        }
    }

    inner class GenericListAdapter : BaseAdapter() {
        override fun getCount(): Int = displayedItems.size
        override fun getItem(p: Int): Any = displayedItems[p]
        override fun getItemId(p: Int): Long = p.toLong()

        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val v = cv ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_file, parent, false)
            val item = displayedItems[p]
            val tvTitle = v.findViewById<TextView>(R.id.tvItemTitle)
            val tvSub = v.findViewById<TextView>(R.id.tvItemSub)
            val cb = v.findViewById<CheckBox>(R.id.cbSelected)

            if (item.isDirectory) {
                tvTitle.text = "📁 ${item.name}"
                tvSub.text = "Folder"
                cb.visibility = View.INVISIBLE
            } else {
                cb.visibility = View.VISIBLE
                cb.isChecked = selectedItemsMap.containsKey(item.id)
                tvTitle.text = when {
                    item.isInstalledApp -> "App: ${item.name}"
                    item.category == Category.DOCS -> "📄 ${item.name}"
                    else -> item.name
                }
                tvSub.text = String.format("%.2f MB", item.size / (1024.0 * 1024.0))
            }

            cb.setOnClickListener { toggleSelection(item) }
            v.setOnClickListener { openPreview(p) }
            return v
        }
    }
}
