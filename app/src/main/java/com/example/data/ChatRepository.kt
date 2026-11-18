package com.example.data

import com.example.model.CallLogItem
import com.example.model.CallStatus
import com.example.model.CallType
import com.example.model.ChatMessage
import com.example.model.MediaType
import com.example.model.MessageStatus
import com.example.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class ChatRepository(private val context: Context? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences("glidechat_prefs", Context.MODE_PRIVATE)

    private val initialUser: User
        get() {
            if (context == null) {
                return User(
                    id = "current_user",
                    name = "Me",
                    gmail = "romeow9991@gmail.com",
                    avatarColorHex = 0xFF4F46E5,
                    statusMessage = "Using Just Chat with No-Lag Low Data ⚡"
                )
            }
            val savedName = prefs?.getString("user_name", null)
            val savedEmail = prefs?.getString("user_email", null)
            val savedStatus = prefs?.getString("user_status", null) ?: "Available for low-data calls"
            return if (!savedEmail.isNullOrBlank()) {
                User(
                    id = "current_user",
                    name = savedName ?: "Me",
                    gmail = savedEmail,
                    avatarColorHex = 0xFF4F46E5,
                    statusMessage = savedStatus
                )
            } else {
                User(
                    id = "current_user",
                    name = "",
                    gmail = "",
                    avatarColorHex = 0xFF4F46E5,
                    statusMessage = "Available for low-data calls"
                )
            }
        }

    val isUserLoggedIn: Boolean
        get() = if (context != null) {
            prefs?.getBoolean("user_logged_in", false) == true
        } else {
            true
        }

    private val _currentUser = MutableStateFlow(initialUser)
    val currentUser: StateFlow<User> = _currentUser.asStateFlow()

    private val _contacts = MutableStateFlow<List<User>>(
        listOf(
            User(
                id = "c1",
                name = "Marcus Vance",
                gmail = "marcus.vance@gmail.com",
                avatarColorHex = 0xFF0EA5E9,
                statusMessage = "Ready for low-data video call! 🎥",
                isOnline = true,
                lastSeenText = "Active now"
            ),
            User(
                id = "c2",
                name = "Sarah Jenkins",
                gmail = "sarah.jenkins@gmail.com",
                avatarColorHex = 0xFF8B5CF6,
                statusMessage = "Let's connect on audio 🎧",
                isOnline = true,
                lastSeenText = "Active now"
            ),
            User(
                id = "c3",
                name = "David Chen",
                gmail = "david.chen.tech@gmail.com",
                avatarColorHex = 0xFF10B981,
                statusMessage = "Zero-lag calls are awesome",
                isOnline = false,
                lastSeenText = "Last seen 15m ago"
            ),
            User(
                id = "c4",
                name = "Emily Watson",
                gmail = "emily.watson92@gmail.com",
                avatarColorHex = 0xFFF59E0B,
                statusMessage = "Sent you the travel pictures! 📸",
                isOnline = true,
                lastSeenText = "Active now"
            ),
            User(
                id = "c5",
                name = "Alex Rivera",
                gmail = "alex.rivera.pro@gmail.com",
                avatarColorHex = 0xFFEC4899,
                statusMessage = "On Wi-Fi call",
                isOnline = false,
                lastSeenText = "Last seen 2h ago"
            )
        )
    )
    val contacts: StateFlow<List<User>> = _contacts.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(
        mapOf(
            "c1" to listOf(
                ChatMessage(
                    id = "m1",
                    senderId = "c1",
                    receiverId = "current_user",
                    text = "Hey! Did you turn on the Low Data mode for video calls?",
                    timestamp = System.currentTimeMillis() - 3600000 * 2,
                    status = MessageStatus.READ
                ),
                ChatMessage(
                    id = "m2",
                    senderId = "current_user",
                    receiverId = "c1",
                    text = "Yes, it uses under 50 KB/s with zero lag! The connection is crystal clear.",
                    timestamp = System.currentTimeMillis() - 3600000 * 2 + 60000,
                    status = MessageStatus.READ,
                    reactions = mapOf("🔥" to 1)
                ),
                ChatMessage(
                    id = "m3",
                    senderId = "c1",
                    receiverId = "current_user",
                    text = "Here is the photo from the test run:",
                    mediaType = MediaType.IMAGE,
                    mediaUri = "sample_beach",
                    timestamp = System.currentTimeMillis() - 3600000 + 120000,
                    status = MessageStatus.READ
                ),
                ChatMessage(
                    id = "m4",
                    senderId = "c1",
                    receiverId = "current_user",
                    text = "Let me know when you want to try a quick video call test!",
                    timestamp = System.currentTimeMillis() - 1800000,
                    status = MessageStatus.READ
                )
            ),
            "c2" to listOf(
                ChatMessage(
                    id = "m2_1",
                    senderId = "c2",
                    receiverId = "current_user",
                    text = "Hi! I found you easily with your Gmail address 🙌",
                    timestamp = System.currentTimeMillis() - 7200000,
                    status = MessageStatus.READ
                ),
                ChatMessage(
                    id = "m2_2",
                    senderId = "current_user",
                    receiverId = "c2",
                    text = "Awesome! Glad finding contacts by Gmail is so seamless.",
                    timestamp = System.currentTimeMillis() - 7000000,
                    status = MessageStatus.READ
                ),
                ChatMessage(
                    id = "m2_3",
                    senderId = "c2",
                    receiverId = "current_user",
                    text = "Recorded a quick voice note for the meeting notes:",
                    mediaType = MediaType.VOICE,
                    mediaDurationSeconds = 18,
                    timestamp = System.currentTimeMillis() - 3600000,
                    status = MessageStatus.READ
                )
            ),
            "c4" to listOf(
                ChatMessage(
                    id = "m4_1",
                    senderId = "c4",
                    receiverId = "current_user",
                    text = "Check out this video clip from the conference presentation:",
                    mediaType = MediaType.VIDEO,
                    mediaUri = "sample_video",
                    mediaDurationSeconds = 24,
                    timestamp = System.currentTimeMillis() - 86400000,
                    status = MessageStatus.READ
                ),
                ChatMessage(
                    id = "m4_2",
                    senderId = "current_user",
                    receiverId = "c4",
                    text = "Loaded instantly without stuttering! Nice compression.",
                    timestamp = System.currentTimeMillis() - 85000000,
                    status = MessageStatus.READ,
                    reactions = mapOf("👍" to 1, "❤️" to 1)
                )
            )
        )
    )
    val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages.asStateFlow()

    private val _callLogs = MutableStateFlow<List<CallLogItem>>(
        listOf(
            CallLogItem(
                id = "call_1",
                contact = _contacts.value[0],
                callType = CallType.VIDEO,
                isIncoming = false,
                isMissed = false,
                timestamp = System.currentTimeMillis() - 1800000,
                durationSeconds = 345,
                dataUsedKb = 14200,
                wasLowDataMode = true
            ),
            CallLogItem(
                id = "call_2",
                contact = _contacts.value[1],
                callType = CallType.AUDIO,
                isIncoming = true,
                isMissed = false,
                timestamp = System.currentTimeMillis() - 86400000,
                durationSeconds = 620,
                dataUsedKb = 3720,
                wasLowDataMode = true
            ),
            CallLogItem(
                id = "call_3",
                contact = _contacts.value[2],
                callType = CallType.VIDEO,
                isIncoming = true,
                isMissed = true,
                timestamp = System.currentTimeMillis() - 86400000 * 2,
                durationSeconds = 0,
                dataUsedKb = 0,
                wasLowDataMode = false
            )
        )
    )
    val callLogs: StateFlow<List<CallLogItem>> = _callLogs.asStateFlow()

    // Settings
    val lowDataCallsEnabled = MutableStateFlow(true)
    val lowLatencyOptimization = MutableStateFlow(true)
    val autoDownloadOnWifiOnly = MutableStateFlow(true)
    val totalDataSavedMb = MutableStateFlow(184.2f)

    fun updateCurrentUser(name: String, gmail: String, statusMessage: String) {
        val cleanName = name.trim().ifEmpty { "Me" }
        val cleanGmail = gmail.trim()
        val cleanStatus = statusMessage.trim().ifEmpty { "Available for low-data calls" }

        prefs?.edit()
            ?.putString("user_name", cleanName)
            ?.putString("user_email", cleanGmail)
            ?.putString("user_status", cleanStatus)
            ?.putBoolean("user_logged_in", true)
            ?.apply()

        _currentUser.update {
            it.copy(
                name = cleanName,
                gmail = cleanGmail,
                statusMessage = cleanStatus
            )
        }
    }

    fun signOut() {
        prefs?.edit()
            ?.putBoolean("user_logged_in", false)
            ?.apply()
    }

    fun addContactByGmail(gmail: String, name: String? = null): Result<User> {
        val trimmedEmail = gmail.trim().lowercase()
        if (!trimmedEmail.contains("@") || !trimmedEmail.endsWith(".com")) {
            return Result.failure(IllegalArgumentException("Please enter a valid Gmail address (e.g. friend@gmail.com)"))
        }

        val existing = _contacts.value.find { it.gmail.equals(trimmedEmail, ignoreCase = true) }
        if (existing != null) {
            return Result.success(existing)
        }

        val displayName = if (!name.isNullOrBlank()) {
            name.trim()
        } else {
            val username = trimmedEmail.substringBefore("@")
            username.replace(".", " ").replaceFirstChar { it.uppercase() }
        }

        val colors = listOf(0xFF4F46E5, 0xFF0EA5E9, 0xFF8B5CF6, 0xFF10B981, 0xFFF59E0B, 0xFFEC4899)
        val assignedColor = colors[(_contacts.value.size) % colors.size]

        val newUser = User(
            id = "user_${UUID.randomUUID().toString().take(8)}",
            name = displayName,
            gmail = trimmedEmail,
            avatarColorHex = assignedColor,
            statusMessage = "Connected via Gmail",
            isOnline = true,
            lastSeenText = "Active now"
        )

        _contacts.update { listOf(newUser) + it }
        
        // Add greeting message
        val welcomeMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            senderId = newUser.id,
            receiverId = "current_user",
            text = "Connected! You can now send messages, photos, videos, or start low-data audio & video calls.",
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.DELIVERED
        )
        _messages.update { current ->
            val list = current[newUser.id] ?: emptyList()
            current + (newUser.id to (list + welcomeMsg))
        }

        return Result.success(newUser)
    }

    fun sendMessage(
        contactId: String,
        text: String,
        mediaType: MediaType = MediaType.NONE,
        mediaUri: String? = null,
        mediaDurationSeconds: Int = 0,
        replyToText: String? = null
    ) {
        val newMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            senderId = "current_user",
            receiverId = contactId,
            text = text,
            mediaType = mediaType,
            mediaUri = mediaUri,
            mediaDurationSeconds = mediaDurationSeconds,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENT,
            replyToText = replyToText
        )

        _messages.update { current ->
            val list = current[contactId] ?: emptyList()
            current + (contactId to (list + newMsg))
        }

        // Simulate contact delivery & response if desired
    }

    fun toggleReaction(contactId: String, messageId: String, emoji: String) {
        _messages.update { current ->
            val list = current[contactId] ?: return@update current
            val updatedList = list.map { msg ->
                if (msg.id == messageId) {
                    val currentCount = msg.reactions[emoji] ?: 0
                    val newReactions = msg.reactions.toMutableMap()
                    if (currentCount > 0) {
                        newReactions.remove(emoji)
                    } else {
                        newReactions[emoji] = 1
                    }
                    msg.copy(reactions = newReactions)
                } else msg
            }
            current + (contactId to updatedList)
        }
    }

    fun recordCompletedCall(
        contact: User,
        callType: CallType,
        durationSeconds: Int,
        wasLowDataMode: Boolean,
        dataUsedKb: Long
    ) {
        val newCallLog = CallLogItem(
            id = UUID.randomUUID().toString(),
            contact = contact,
            callType = callType,
            isIncoming = false,
            isMissed = durationSeconds == 0,
            timestamp = System.currentTimeMillis(),
            durationSeconds = durationSeconds,
            dataUsedKb = dataUsedKb,
            wasLowDataMode = wasLowDataMode,
            wasEncrypted = true
        )

        _callLogs.update { listOf(newCallLog) + it }

        // Update cumulative data saved (approx 70% saved if Low Data mode used)
        if (wasLowDataMode && durationSeconds > 0) {
            val savedMb = (dataUsedKb * 2.3f) / 1024f
            totalDataSavedMb.update { it + (savedMb / 1000f) }
        }

        // Add call notice to chat
        val callNoticeText = when {
            durationSeconds == 0 -> "Missed ${callType.name.lowercase()} call"
            else -> "${callType.name.lowercase().replaceFirstChar { it.uppercase() }} call ended (${formatDuration(durationSeconds)}) • 🔒 E2EE"
        }
        val callMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            senderId = "current_user",
            receiverId = contact.id,
            text = "$callNoticeText • ${dataUsedKb / 1024}MB used",
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.READ,
            isEncrypted = true
        )
        _messages.update { current ->
            val list = current[contact.id] ?: emptyList()
            current + (contact.id to (list + callMsg))
        }
    }

    private fun formatDuration(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return "%02d:%02d".format(mins, secs)
    }
}
