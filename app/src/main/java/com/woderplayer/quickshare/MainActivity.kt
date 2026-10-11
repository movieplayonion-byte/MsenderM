package com.woderplayer.quickshare

import android.Manifest
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLEncoder

enum class Category { APPS, IMAGES, VIDEOS, AUDIO, DOCS }

data class MediaItem(
    val id: String,
    val name: String,
    val size: Long,
    val uri: Uri? = null,
    val file: File? = null,
    val category: Category,
    val isInstalledApp: Boolean = false,
    val appIcon: Drawable? = null
)

class MainActivity : AppCompatActivity() {

    private val CURRENT_APP_VERSION = 1
    private val UPDATE_CONFIG_URL = "https://raw.githubusercontent.com/movieplayonion-byte/MsenderM/main/version.json"

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
    private var inAppMediaPlayer: MediaPlayer? = null

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
        checkForAppUpdate()

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
                Toast.makeText(this, "Bhejne ke liye items select karo!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val options = ScanOptions().apply {
                setPrompt("Receiver ka QR Scan karo")
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
