package com.example.data

import androidx.annotation.Keep
import com.example.model.CallLogItem
import com.example.model.CallType
import com.example.model.ChatMessage
import com.example.model.MediaType
import com.example.model.MessageStatus
import com.example.model.User
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class UserProfileDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val statusMessage: String = "Using Just Chat",
    val lowDataCallsEnabled: Boolean = true,
    val lowLatencyEnabled: Boolean = true,
    val totalDataSavedMb: Double = 0.0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toDomainUser(): User = User(
        id = userId.ifBlank { id },
        name = displayName.ifBlank { "Me" },
        gmail = email,
        avatarColorHex = 0xFF4F46E5L,
        statusMessage = statusMessage,
        isOnline = true,
        lastSeenText = "Active now"
    )
}

@Keep
@IgnoreExtraProperties
data class ContactDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val gmail: String = "",
    val avatarColorHex: Long = 0xFF4F46E5L,
    val statusMessage: String = "Connected via Gmail",
    val online: Boolean = true,
    val lastSeenText: String = "Active now",
    val securityFingerprint: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toDomainUser(): User = User(
        id = id,
        name = name.ifBlank { gmail.substringBefore("@").ifBlank { "Contact" } },
        gmail = gmail,
        avatarColorHex = avatarColorHex,
        statusMessage = statusMessage,
        isOnline = online,
        lastSeenText = lastSeenText,
        securityFingerprint = securityFingerprint.ifBlank { User.generateSafetyNumbers(gmail) }
    )
}

@Keep
@IgnoreExtraProperties
data class MessageDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val contactId: String = "",
    val senderId: String = "",
    val receiverId: String = "",
    val text: String = "",
    val status: String = MessageStatus.SENT.name,
    val reactions: Map<String, Long>? = null,
    val encrypted: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toDomainMessage(): ChatMessage {
        val parsedStatus = enumValues<MessageStatus>()
            .firstOrNull { it.name == status } ?: MessageStatus.SENT
        val timestampMs = (createdAt ?: updatedAt ?: Timestamp.now()).toDate().time
        val parsedReactions = reactions
            ?.mapValues { (_, count) -> count.toInt() }
            ?: emptyMap()
        return ChatMessage(
            id = id,
            senderId = senderId,
            receiverId = receiverId,
            text = text,
            mediaType = MediaType.NONE,
            timestamp = timestampMs,
            status = parsedStatus,
            reactions = parsedReactions,
            isEncrypted = encrypted
        )
    }
}

@Keep
@IgnoreExtraProperties
data class CallLogDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val contactId: String = "",
    val contactName: String = "",
    val contactGmail: String = "",
    val contactAvatarColorHex: Long = 0xFF4F46E5L,
    val callType: String = CallType.AUDIO.name,
    val incoming: Boolean = false,
    val missed: Boolean = false,
    val durationSeconds: Long = 0L,
    val dataUsedKb: Long = 0L,
    val lowDataMode: Boolean = true,
    val encrypted: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toDomainCallLog(): CallLogItem {
        val parsedCallType = enumValues<CallType>()
            .firstOrNull { it.name == callType } ?: CallType.AUDIO
        val timestampMs = (createdAt ?: updatedAt ?: Timestamp.now()).toDate().time
        val contact = User(
            id = contactId,
            name = contactName.ifBlank { "Contact" },
            gmail = contactGmail,
            avatarColorHex = contactAvatarColorHex
        )
        return CallLogItem(
            id = id,
            contact = contact,
            callType = parsedCallType,
            isIncoming = incoming,
            isMissed = missed,
            timestamp = timestampMs,
            durationSeconds = durationSeconds.toInt(),
            dataUsedKb = dataUsedKb,
            wasLowDataMode = lowDataMode,
            wasEncrypted = encrypted
        )
    }
}

@Keep
@IgnoreExtraProperties
data class UserDirectoryDoc(
    @DocumentId val id: String = "",
    val uid: String = "",
    val displayName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class DirectoryUser(
    val uid: String,
    val email: String,
    val displayName: String
)
