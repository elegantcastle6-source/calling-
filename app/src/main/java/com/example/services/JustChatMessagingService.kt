package com.example.services

import android.util.Log
import com.example.MainActivity
import com.example.utils.CallRingtonePlayer
import com.example.utils.CallWakeHelper
import com.example.utils.JustChatNotifications
import com.example.utils.MessagePopupNotifier
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Handles high-priority FCM push payloads for incoming calls ("call") and chat messages ("message").
 */
class JustChatMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed FCM registration token")
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        val type = data["type"]?.trim()?.lowercase() ?: return

        when (type) {
            "call" -> {
                val callerName = data["callerName"]
                    ?: data["sender"]
                    ?: remoteMessage.notification?.title
                    ?: "Incoming Call"
                val callerEmail = data["callerEmail"] ?: data["caller"] ?: ""
                val isVideo = data["callType"]?.equals("VIDEO", ignoreCase = true) == true

                CallWakeHelper.wakeForIncomingCall(applicationContext) {
                    JustChatNotifications.createChannels(applicationContext)
                    CallRingtonePlayer.startDefaultRingtone(applicationContext)
                    JustChatNotifications.showIncomingCallNotification(
                        context = applicationContext,
                        callerName = callerName,
                        callerEmail = callerEmail,
                        isVideo = isVideo,
                        fullScreen = true
                    )
                }
            }

            "message" -> {
                val sender = data["sender"]
                    ?: data["senderName"]
                    ?: remoteMessage.notification?.title
                    ?: "New Message"
                val senderEmail = data["senderEmail"] ?: data["email"] ?: ""
                val text = data["body"]
                    ?: remoteMessage.notification?.body
                    ?: "You received a new message"

                MessagePopupNotifier.showMessagePopup(
                    context = applicationContext,
                    senderName = sender,
                    messageBody = text,
                    targetActivityClass = MainActivity::class.java,
                    senderEmail = senderEmail
                )
            }
        }
    }

    private companion object {
        const val TAG = "JustChatFcmService"
    }
}
