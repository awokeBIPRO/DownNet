package com.downnet.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.downnet.R

object Notifications {

    const val CHANNEL_DOWNLOADS = "downloads"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOADS,
                context.getString(R.string.channel_downloads),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_downloads_desc) }
        )
    }

    /** "It's in your gallery" notification; tapping opens the media. */
    fun downloadComplete(context: Context, id: Int, title: String, uri: Uri, mime: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            return
        }
        val open = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = android.app.PendingIntent.getActivity(
            context, id, open,
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_shortcut_save)
            .setContentTitle(context.getString(R.string.download_complete_title))
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
