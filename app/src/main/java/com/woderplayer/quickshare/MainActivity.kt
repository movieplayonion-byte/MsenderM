package com.woderplayer.quickshare

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var lvApps: ListView
    private lateinit var btnSend: Button
    private lateinit var btnSelectFile: Button

    private var server: LocalFileServer? = null
    private var sharedFile: File? = null
    private var sharedUri: Uri? = null
    private var sharedFileName: String = ""
    private var sharedFileSize: Long = 0
    private var currentPort = 8080

    data class AppItem(val name: String, val packageName: String, val apkPath: String)

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val uri = result.data?.data
            if (uri != null) {
                sharedUri = uri
                sharedFile = null
                getUriDetails(uri)
                Toast.makeText(this, "Selected: $sharedFileName", Toast.LENGTH_SHORT).show()
                startLocalServer()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        lvApps = findViewById(R.id.lvApps)
        btnSend = findViewById(R.id.btnSend)
        btnSelectFile = findViewById(R.id.btnSelectFile)

        checkPermissions()
        loadInstalledApps()

        btnSelectFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            filePickerLauncher.launch(Intent.createChooser(intent, "Select File to Share"))
        }

        btnSend.setOnClickListener {
            startLocalServer()
        }
    }

    private fun getUriDetails(uri: Uri) {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                sharedFileName = cursor.getString(nameIndex) ?: "shared_file"
                sharedFileSize = cursor.getLong(sizeIndex)
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
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
            sharedFile = f
            sharedUri = null
            sharedFileName = "${selected.name.replace(" ", "_")}.apk"
            sharedFileSize = f.length()
            Toast.makeText(this, "Selected APK: ${selected.name}", Toast.LENGTH_SHORT).show()
            startLocalServer()
        }
    }

    private fun startLocalServer() {
        if (sharedFile == null && sharedUri == null) {
            Toast.makeText(this, "Please pick a file or select an app first!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            server?.stop()
            server = null
            Thread.sleep(100)
        } catch (_: Exception) {}

        var started = false
        var attempts = 0

        while (!started && attempts < 5) {
            try {
                server = LocalFileServer(currentPort)
                server?.start()
                started = true
            } catch (e: Exception) {
                currentPort += 1
                attempts++
            }
        }

        if (started) {
            val ip = getLocalIpAddress()
            tvStatus.text = "Sharing: $sharedFileName\nLink: http://$ip:$currentPort\n(Open in any browser)"
            Toast.makeText(this, "Server running on port $currentPort", Toast.LENGTH_LONG).show()
        } else {
            tvStatus.text = "Failed to bind port. Please restart app."
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt != 0) {
                return InetAddress.getByAddress(
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ipInt).array()
                ).hostAddress ?: "127.0.0.1"
            }
        } catch (_: Exception) {}
        return "192.168.43.1"
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            server?.stop()
        } catch (_: Exception) {}
    }

    inner class LocalFileServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            return try {
                val stream: InputStream = if (sharedFile != null) {
                    FileInputStream(sharedFile!!)
                } else if (sharedUri != null) {
                    contentResolver.openInputStream(sharedUri!!) ?: throw Exception("Cannot open file")
                } else {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No file selected")
                }

                val res = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/octet-stream",
                    stream,
                    sharedFileSize
                )
                res.addHeader("Content-Disposition", "attachment; filename=\"$sharedFileName\"")
                res
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
            }
        }
    }
}
