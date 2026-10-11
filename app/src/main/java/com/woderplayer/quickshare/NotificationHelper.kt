package com.woderplayer.quickshare

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File

object NotificationHelper {

    private const val CHANNEL_ID = "transfer_channel"
    private const val NOTIF_ID = 3001

    private fun initChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Transfer Progress",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    // isSender = true -> Upload arrow, isSender = false -> Download arrow
    fun updateProgress(context: Context, isSender: Boolean, fileName: String, progress: Int) {
        initChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val icon = if (isSender) {
            android.R.drawable.stat_sys_upload
        } else {
            android.R.drawable.stat_sys_download
        }

        val title = if (isSender) "Sending: $fileName" else "Receiving: $fileName"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText("$progress% completed")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        manager.notify(NOTIF_ID, builder.build())
    }

    fun showSuccess(context: Context, isSender: Boolean, file: File?) {
        initChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val icon = android.R.drawable.stat_sys_upload_done
        val title = if (isSender) "Sent successfully" else "Received successfully"
        val text = file?.name ?: "File transfer completed"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)

        if (!isSender && file != null && file.exists()) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pendingIntent)
        }

        manager.notify(NOTIF_ID, builder.build())
    }

    fun showFailure(context: Context, isSender: Boolean, reason: String) {
        initChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val title = if (isSender) "Sending Failed" else "Receiving Failed"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(reason)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)

        manager.notify(NOTIF_ID, builder.build())
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIF_ID)
    }
}
