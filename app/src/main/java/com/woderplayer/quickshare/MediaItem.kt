package com.woderplayer.quickshare

import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.File

enum class Category {
    APPS, IMAGES, VIDEOS, AUDIO, DOCS, FILES
}

data class MediaItem(
    val id: String,
    val name: String,
    val size: Long,
    val uri: Uri? = null,
    val file: File? = null,
    val category: Category = Category.FILES,
    val isInstalledApp: Boolean = false,
    val appIcon: Drawable? = null,
    val isDirectory: Boolean = false
)
