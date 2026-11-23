package com.example.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object JustChatNotifications {
    const val CHANNEL_MESSAGES = "just_chat_messages_high"
    const val CHANNEL_CALLS = "just_chat_calls_high"
    const val EXTRA_CONTACT_EMAIL = "extra_contact_email"

    private const val CALL_NOTIFICATION_ID = 9001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return

        val messageChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            appContext.getString(R.string.notification_channel_messages_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = appContext.getString(R.string.notification_channel_messages_desc)
            enableVibration(true)
        }

        val callChannel = NotificationChannel(
            CHANNEL_CALLS,
            appContext.getString(R.string.notification_channel_calls_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = appContext.getString(R.string.notification_channel_calls_desc)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
        }

        manager.createNotificationChannel(messageChannel)
        manager.createNotificationChannel(callChannel)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        val appContext = context.applicationContext
        val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return runtimeGranted && NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission")
    fun showIncomingMessageNotification(
        context: Context,
        senderName: String,
        senderEmail: String,
        messageText: String
    ) {
        val appContext = context.applicationContext
        if (!hasNotificationPermission(appContext)) return
        createChannels(appContext)

        val openChatIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONTACT_EMAIL, senderEmail)
        }
        val requestCode = senderEmail.lowercase().hashCode()
        val contentIntent = PendingIntent.getActivity(
            appContext,
            requestCode,
            openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = senderName.ifBlank { senderEmail.substringBefore("@").ifBlank { "Just Chat" } }
        val body = messageText.ifBlank { "New message" }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        NotificationManagerCompat.from(appContext).notify(requestCode, notification)
    }

    @SuppressLint("MissingPermission")
    fun showIncomingCallNotification(
        context: Context,
        callerName: String,
        callerEmail: String,
        isVideo: Boolean
    ) {
        val appContext = context.applicationContext
        if (!hasNotificationPermission(appContext)) return
        createChannels(appContext)

        val openCallIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            CALL_NOTIFICATION_ID,
            openCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = callerName.ifBlank { callerEmail.substringBefore("@").ifBlank { "Incoming Call" } }
        val body = if (isVideo) {
            appContext.getString(R.string.notification_incoming_video_call)
        } else {
            appContext.getString(R.string.notification_incoming_voice_call)
        }

        val builder = NotificationCompat.Builder(appContext, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(contentIntent)

        // Avoid double-ringing when CallRingtonePlayer is active or ringtone is muted in Settings
        if (CallRingtonePlayer.isCurrentlyRinging() || !CallRingtoneSettings.isRingtoneEnabled(appContext)) {
            builder.setSilent(true)
        }

        NotificationManagerCompat.from(appContext).notify(CALL_NOTIFICATION_ID, builder.build())
    }

    fun cancelIncomingCallNotification(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(CALL_NOTIFICATION_ID)
    }
}
