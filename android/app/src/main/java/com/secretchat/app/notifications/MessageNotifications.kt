package com.secretchat.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.secretchat.app.MainActivity
import com.secretchat.app.R

/** Which chat is on screen right now, so we don't notify about it. */
object ActiveChat {
    @Volatile
    var conversationId: String? = null

    @Volatile
    var appVisible: Boolean = false
}

object MessageNotifications {

    const val CHANNEL_ID = "messages"
    const val EXTRA_CONVERSATION_ID = "conversation_id"
    const val EXTRA_TITLE = "conversation_title"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "New chat messages"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(
        context: Context,
        conversationId: String,
        title: String,
        body: String,
        avatar: Bitmap?,
    ) {
        if (ActiveChat.appVisible && ActiveChat.conversationId == conversationId) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val openChat = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_CONVERSATION_ID, conversationId)
            .putExtra(EXTRA_TITLE, title)
        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            openChat,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setLargeIcon(avatar)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(conversationId.hashCode(), notification)
    }

    /** Clears a chat's notification once the user opens that chat. */
    fun cancel(context: Context, conversationId: String) {
        NotificationManagerCompat.from(context).cancel(conversationId.hashCode())
    }
}
