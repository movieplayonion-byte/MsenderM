package com.woderplayer.quickshare

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File

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

object MediaViewer {
    private var player: MediaPlayer? = null

    fun open(context: Context, item: MediaItem) {
        if (item.category == Category.APPS) {
            item.file?.let { installApk(context, it) }
            return
        }

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_inapp_preview, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvPreviewTitle)
        val ivImage = view.findViewById<ImageView>(R.id.ivPreviewImage)
        val vvVideo = view.findViewById<VideoView>(R.id.vvPreviewVideo)
        val scrollDoc = view.findViewById<ScrollView>(R.id.scrollDocPreview)
        val tvDoc = view.findViewById<TextView>(R.id.tvDocContent)
        val btnClose = view.findViewById<Button>(R.id.btnClosePreview)

        tvTitle.text = item.name
        val dialog = AlertDialog.Builder(context).setView(view).create()

        when (item.category) {
            Category.IMAGES -> {
                ivImage.visibility = View.VISIBLE
                if (item.uri != null) ivImage.setImageURI(item.uri)
                else if (item.file != null) ivImage.setImageURI(Uri.fromFile(item.file))
            }
            Category.VIDEOS -> {
                vvVideo.visibility = View.VISIBLE
                val targetUri = item.uri ?: Uri.fromFile(item.file)
                val mc = MediaController(context)
                mc.setAnchorView(vvVideo)
                vvVideo.setMediaController(mc)
                vvVideo.setVideoURI(targetUri)
                vvVideo.start()
            }
            Category.AUDIO -> {
                try {
                    player?.release()
                    player = MediaPlayer().apply {
                        val stream = if (item.uri != null) context.contentResolver.openInputStream(item.uri) else item.file?.inputStream()
                        if (stream != null) {
                            val temp = File.createTempFile("p_audio", ".tmp", context.cacheDir)
                            temp.outputStream().use { stream.copyTo(it) }
                            setDataSource(temp.absolutePath)
                            prepare()
                            start()
                        }
                    }
                    Toast.makeText(context, "Playing audio...", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
                return
            }
            Category.DOCS -> {
                scrollDoc.visibility = View.VISIBLE
                try {
                    val stream = if (item.uri != null) context.contentResolver.openInputStream(item.uri) else item.file?.inputStream()
                    val text = stream?.bufferedReader()?.use { it.readText() } ?: "Document File"
                    tvDoc.text = if (text.length > 4000) text.substring(0, 4000) + "\n...[Truncated]" else text
                } catch (_: Exception) {
                    tvDoc.text = "Document ready for transfer."
                }
            }
            else -> {}
        }

        btnClose.setOnClickListener {
            vvVideo.stopPlayback()
            dialog.dismiss()
        }
        dialog.show()
    }

    fun release() {
        player?.release()
        player = null
    }

    fun installApk(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot install: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
