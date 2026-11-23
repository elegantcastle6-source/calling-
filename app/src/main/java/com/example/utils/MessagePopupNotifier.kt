package com.example.utils

import android.content.Context
import com.example.MainActivity

/**
 * Displays high-importance message heads-up notifications routed to the target Activity.
 */
object MessagePopupNotifier {
    fun showMessagePopup(
        context: Context,
        senderName: String,
        messageBody: String,
        targetActivityClass: Class<*> = MainActivity::class.java,
        senderEmail: String = ""
    ) {
        JustChatNotifications.showIncomingMessageNotification(
            context = context.applicationContext,
            senderName = senderName,
            senderEmail = senderEmail,
            messageText = messageBody,
            targetActivityClass = targetActivityClass
        )
    }
}
