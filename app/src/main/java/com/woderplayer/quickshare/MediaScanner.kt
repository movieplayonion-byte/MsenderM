package com.woderplayer.quickshare

import android.content.ContentUris
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object MediaScanner {

    fun loadApps(context: Context): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        val pm = context.packageManager
        for (app in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || app.packageName == context.packageName) {
                val label = pm.getApplicationLabel(app).toString()
                val icon = pm.getApplicationIcon(app)
                val file = File(app.sourceDir)
                list.add(MediaItem("app_inst_${app.packageName}", "$label.apk", file.length(), file = file, category = Category.APPS, isInstalledApp = true, appIcon = icon))
            }
        }
        return list
    }

    fun loadImages(context: Context): List<MediaItem> = queryStore(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Category.IMAGES)

    fun loadVideos(context: Context): List<MediaItem> = queryStore(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Category.VIDEOS)

    fun loadAudio(context: Context): List<MediaItem> = queryStore(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Category.AUDIO)

    fun loadDocuments(context: Context): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        val docExts = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "vsd", "vcf")
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
            val selection = "${MediaStore.MediaColumns.SIZE} > 0"

            context.contentResolver.query(uri, proj, selection, null, "${MediaStore.MediaColumns._ID} DESC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: ""
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (docExts.contains(ext)) {
                        val id = c.getLong(idCol)
                        val size = c.getLong(sizeCol)
                        val itemUri = ContentUris.withAppendedId(uri, id)
                        list.add(MediaItem("doc_$id", name, size, uri = itemUri, category = Category.DOCS))
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    fun loadBigFiles(context: Context): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        val minSizeBytes = 25L * 1024L * 1024L
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
            val selection = "${MediaStore.MediaColumns.SIZE} >= $minSizeBytes"

            context.contentResolver.query(uri, proj, selection, null, "${MediaStore.MediaColumns.SIZE} DESC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: "file_$id"
                    val size = c.getLong(sizeCol)
                    val itemUri = ContentUris.withAppendedId(uri, id)
                    list.add(MediaItem("big_$id", name, size, uri = itemUri, category = Category.FILES))
                }
            }
        } catch (_: Exception) {}
        return list
    }

    fun loadDirectory(directory: File): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        try {
            val files = directory.listFiles() ?: return list
            val sorted = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            for (f in sorted) {
                if (f.name.startsWith(".")) continue
                list.add(
                    MediaItem(
                        id = "file_${f.absolutePath}",
                        name = f.name,
                        size = if (f.isDirectory) 0L else f.length(),
                        file = f,
                        category = Category.FILES,
                        isDirectory = f.isDirectory
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    private fun queryStore(context: Context, uri: Uri, cat: Category): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        try {
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
            context.contentResolver.query(uri, proj, null, null, "${MediaStore.MediaColumns._ID} DESC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: "file_$id"
                    val size = c.getLong(sizeCol)
                    val itemUri = ContentUris.withAppendedId(uri, id)
                    list.add(MediaItem("${cat}_$id", name, size, uri = itemUri, category = cat))
                }
            }
        } catch (_: Exception) {}
        return list
    }
}
