package com.woderplayer.quickshare

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
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
    private val PORT = 8080

    data class AppItem(val name: String, val packageName: String, val apkPath: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        lvApps = findViewById(R.id.lvApps)
        btnSend = findViewById(R.id.btnSend)
        btnSelectFile = findViewById(R.id.btnSelectFile)

        checkPermissions()
        loadInstalledApps()

        btnSend.setOnClickListener {
            startLocalServer()
        }

        btnSelectFile.setOnClickListener {
            Toast.makeText(this, "Click any installed app below to share its APK!", Toast.LENGTH_SHORT).show()
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
            // Filter non-system apps or popular user apps
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || app.packageName == packageName) {
                val label = pm.getApplicationLabel(app).toString()
                appList.add(AppItem(label, app.packageName, app.sourceDir))
            }
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text1, appList.map { "${it.name}\n${it.packageName}" })
        lvApps.adapter = adapter

        lvApps.setOnItemClickListener { _, _, position, _ ->
            val selected = appList[position]
            sharedFile = File(selected.apkPath)
            Toast.makeText(this, "Selected APK: ${selected.name}", Toast.LENGTH_SHORT).show()
            startLocalServer()
        }
    }

    private fun startLocalServer() {
        if (sharedFile == null || !sharedFile!!.exists()) {
            Toast.makeText(this, "Please select an app first!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            server?.stop()
            server = LocalFileServer(PORT, sharedFile!!)
            server?.start()

            val ip = getLocalIpAddress()
            tvStatus.text = "Sharing on: http://$ip:$PORT\n(Receiver can open this link in any browser!)"
            Toast.makeText(this, "Server running! Connect Receiver phone to this Wi-Fi/Hotspot", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            tvStatus.text = "Error starting server: ${e.message}"
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
        return "192.168.43.1" // Default hotspot IP
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }

    inner class LocalFileServer(port: Int, private val fileToSend: File) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            return try {
                val fis = FileInputStream(fileToSend)
                val res = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/vnd.android.package-archive",
                    fis,
                    fileToSend.length()
                )
                res.addHeader("Content-Disposition", "attachment; filename=\"${fileToSend.name}\"")
                res
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
            }
        }
    }
}
