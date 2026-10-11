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
        try {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dl.walkTopDown().maxDepth(2).filter { it.isFile && it.extension.equals("apk", true) }.forEach { f ->
                list.add(MediaItem("app_apk_${f.absolutePath}", f.name, f.length(), file = f, category = Category.APPS, isInstalledApp = false))
            }
        } catch (_: Exception) {}
        return list
    }

    fun loadImages(context: Context): List<MediaItem> = queryStore(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Category.IMAGES)

    fun loadVideos(context: Context): List<MediaItem> = queryStore(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Category.VIDEOS)

    fun loadAudio(context: Context): List<MediaItem> = queryStore(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Category.AUDIO)

    fun loadDocuments(context: Context): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
            val selection = "${MediaStore.MediaColumns.DATA} LIKE '%.pdf' OR " +
                            "${MediaStore.MediaColumns.DATA} LIKE '%.doc%' OR " +
                            "${MediaStore.MediaColumns.DATA} LIKE '%.txt' OR " +
                            "${MediaStore.MediaColumns.DATA} LIKE '%.xls%' OR " +
                            "${MediaStore.MediaColumns.DATA} LIKE '%.ppt%' OR " +
                            "${MediaStore.MediaColumns.DATA} LIKE '%.zip'"

            context.contentResolver.query(uri, projection, selection, null, "${MediaStore.MediaColumns._ID} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "doc_$id"
                    val size = cursor.getLong(sizeCol)
                    val itemUri = ContentUris.withAppendedId(uri, id)
                    list.add(MediaItem("doc_$id", name, size, uri = itemUri, category = Category.DOCS))
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun queryStore(context: Context, uri: Uri, cat: Category): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        try {
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
            context.contentResolver.query(uri, projection, null, null, "${MediaStore.MediaColumns._ID} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "file_$id"
                    val size = cursor.getLong(sizeCol)
                    val itemUri = ContentUris.withAppendedId(uri, id)
                    list.add(MediaItem("${cat}_$id", name, size, uri = itemUri, category = cat))
                }
            }
        } catch (_: Exception) {}
        return list
    }
}
