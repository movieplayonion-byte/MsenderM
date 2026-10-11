package com.woderplayer.quickshare

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File

class MediaPreviewActivity : AppCompatActivity() {

    companion object {
        var previewList: List<MediaItem> = emptyList()
        var initialIndex: Int = 0
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var tvHeader: TextView
    private lateinit var audioPanel: View
    private lateinit var btnPlayPause: Button
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_preview)

        viewPager = findViewById(R.id.mediaViewPager)
        tvHeader = findViewById(R.id.tvPreviewHeader)
        audioPanel = findViewById(R.id.audioControlPanel)
        btnPlayPause = findViewById(R.id.btnAudioPlayPause)
        val btnBack = findViewById<Button>(R.id.btnBackPreview)
        val btnPrev = findViewById<Button>(R.id.btnAudioPrev)
        val btnNext = findViewById<Button>(R.id.btnAudioNext)

        btnBack.setOnClickListener { finish() }

        val category = previewList.firstOrNull()?.category ?: Category.IMAGES
        if (category == Category.VIDEOS) {
            viewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        } else {
            viewPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        }

        viewPager.adapter = PreviewAdapter(previewList)
        viewPager.setCurrentItem(initialIndex, false)

        if (category == Category.AUDIO) {
            audioPanel.visibility = View.VISIBLE
            playAudioAtIndex(initialIndex)
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position in previewList.indices) {
                    tvHeader.text = previewList[position].name
                    if (category == Category.AUDIO) {
                        playAudioAtIndex(position)
                    }
                }
            }
        })

        btnPlayPause.setOnClickListener {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    btnPlayPause.text = "▶ PLAY"
                } else {
                    player.start()
                    btnPlayPause.text = "⏸ PAUSE"
                }
            }
        }

        btnPrev.setOnClickListener {
            val cur = viewPager.currentItem
            if (cur > 0) {
                viewPager.currentItem = cur - 1
            }
        }

        btnNext.setOnClickListener {
            val cur = viewPager.currentItem
            if (cur < previewList.size - 1) {
                viewPager.currentItem = cur + 1
            }
        }
    }

    private fun playAudioAtIndex(index: Int) {
        val item = previewList.getOrNull(index) ?: return
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                val stream = if (item.uri != null) contentResolver.openInputStream(item.uri) else item.file?.inputStream()
                if (stream != null) {
                    val temp = File.createTempFile("aud_", ".tmp", cacheDir)
                    temp.outputStream().use { stream.copyTo(it) }
                    setDataSource(temp.absolutePath)
                    prepare()
                    start()
                    btnPlayPause.text = "⏸ PAUSE"
                }
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    inner class PreviewAdapter(private val items: List<MediaItem>) : RecyclerView.Adapter<PreviewAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivImage: ImageView = v.findViewById(R.id.ivPageImage)
            val vvVideo: VideoView = v.findViewById(R.id.vvPageVideo)
            val scrollDoc: View = v.findViewById(R.id.scrollPageDoc)
            val tvDoc: TextView = v.findViewById(R.id.tvPageDoc)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_preview_page, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.ivImage.visibility = View.GONE
            holder.vvVideo.visibility = View.GONE
            holder.scrollDoc.visibility = View.GONE

            when (item.category) {
                Category.IMAGES -> {
                    holder.ivImage.visibility = View.VISIBLE
                    if (item.uri != null) holder.ivImage.setImageURI(item.uri)
                    else if (item.file != null) holder.ivImage.setImageURI(Uri.fromFile(item.file))
                }
                Category.VIDEOS -> {
                    holder.vvVideo.visibility = View.VISIBLE
                    val targetUri = item.uri ?: Uri.fromFile(item.file)
                    val mc = MediaController(this@MediaPreviewActivity)
                    mc.setAnchorView(holder.vvVideo)
                    holder.vvVideo.setMediaController(mc)
                    holder.vvVideo.setVideoURI(targetUri)
                    holder.vvVideo.start()
                }
                Category.DOCS -> {
                    holder.scrollDoc.visibility = View.VISIBLE
                    try {
                        val stream = if (item.uri != null) contentResolver.openInputStream(item.uri) else item.file?.inputStream()
                        val text = stream?.bufferedReader()?.use { it.readText() } ?: "Document Content"
                        holder.tvDoc.text = if (text.length > 5000) text.substring(0, 5000) + "\n...[Truncated]" else text
                    } catch (_: Exception) {
                        holder.tvDoc.text = "Binary / Encrypted Document ready for sharing."
                    }
                }
                else -> {}
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
