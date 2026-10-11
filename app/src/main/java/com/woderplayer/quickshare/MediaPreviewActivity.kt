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
import com.bumptech.glide.Glide
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
    private var currentActiveVideoView: VideoView? = null

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

        if (previewList.isEmpty()) {
            Toast.makeText(this, "No media to preview", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val category = previewList.firstOrNull()?.category ?: Category.IMAGES
        if (category == Category.VIDEOS) {
            viewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        } else {
            viewPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        }

        val adapter = PreviewAdapter(previewList)
        viewPager.adapter = adapter
        val safeIndex = if (initialIndex in previewList.indices) initialIndex else 0
        viewPager.setCurrentItem(safeIndex, false)
        tvHeader.text = previewList.getOrNull(safeIndex)?.name ?: ""

        if (category == Category.AUDIO) {
            audioPanel.visibility = View.VISIBLE
            playAudioAtIndex(safeIndex)
        }

        // Auto-play on forward AND backward scroll (Reels / Shorts style)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position in previewList.indices) {
                    tvHeader.text = previewList[position].name

                    if (category == Category.VIDEOS) {
                        // Purane chal rahe video ko roko
                        currentActiveVideoView?.pause()
                        
                        // Current page ka VideoView dhund kar play karo
                        viewPager.post {
                            val holder = (viewPager.getChildAt(0) as? RecyclerView)
                                ?.findViewHolderForAdapterPosition(position) as? PreviewAdapter.VH
                            holder?.vvVideo?.let { vv ->
                                currentActiveVideoView = vv
                                vv.seekTo(0)
                                vv.start()
                            }
                        }
                    } else if (category == Category.AUDIO) {
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
            if (cur > 0) viewPager.currentItem = cur - 1
        }

        btnNext.setOnClickListener {
            val cur = viewPager.currentItem
            if (cur < previewList.size - 1) viewPager.currentItem = cur + 1
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
        currentActiveVideoView?.stopPlayback()
        currentActiveVideoView = null
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
                    Glide.with(this@MediaPreviewActivity)
                        .load(item.uri ?: item.file)
                        .fitCenter()
                        .into(holder.ivImage)
                }
                Category.VIDEOS -> {
                    holder.vvVideo.visibility = View.VISIBLE
                    val targetUri = item.uri ?: Uri.fromFile(item.file)
                    holder.vvVideo.setVideoURI(targetUri)

                    // Looping auto-replay (Instagram reels style)
                    holder.vvVideo.setOnPreparedListener { mp ->
                        mp.isLooping = true
                        if (holder.adapterPosition == viewPager.currentItem) {
                            currentActiveVideoView = holder.vvVideo
                            holder.vvVideo.start()
                        }
                    }
                }
                Category.DOCS -> {
                    holder.scrollDoc.visibility = View.VISIBLE
                    try {
                        val stream = if (item.uri != null) contentResolver.openInputStream(item.uri) else item.file?.inputStream()
                        val text = stream?.bufferedReader()?.use { it.readText() } ?: "Document Content"
                        holder.tvDoc.text = if (text.length > 5000) text.substring(0, 5000) + "\n...[Truncated]" else text
                    } catch (_: Exception) {
                        holder.tvDoc.text = "Document ready for sharing."
                    }
                }
                else -> {}
            }
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            holder.vvVideo.stopPlayback()
        }

        override fun getItemCount(): Int = items.size
    }
}
