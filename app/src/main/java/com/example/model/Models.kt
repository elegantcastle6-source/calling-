package com.example.model

enum class MediaType {
    NONE,
    IMAGE,
    VIDEO,
    VOICE
}

enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ
}

data class ChatMessage(
    val id: String,
    val senderId: String,
    val receiverId: String,
    val text: String = "",
    val mediaType: MediaType = MediaType.NONE,
    val mediaUri: String? = null,
    val mediaDurationSeconds: Int = 0,
    val mediaThumbnail: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENT,
    val reactions: Map<String, Int> = emptyMap(),
    val replyToText: String? = null,
    val isEncrypted: Boolean = true,
    val encryptionAlgorithm: String = "AES-256-GCM"
)

data class User(
    val id: String,
    val name: String,
    val gmail: String,
    val avatarUrl: String? = null,
    val avatarColorHex: Long = 0xFF4F46E5,
    val statusMessage: String = "Available for calls",
    val isOnline: Boolean = true,
    val lastSeenText: String = "Active now",
    val securityFingerprint: String = generateSafetyNumbers(gmail)
) {
    companion object {
        fun generateSafetyNumbers(email: String): String {
            val hash = (email.hashCode().toLong() and 0xFFFFFFFFL).toString().padStart(10, '7')
            val p1 = hash.take(5)
            val p2 = hash.substring(5).padEnd(5, '3')
            val p3 = ((email.length * 31337) % 90000 + 10000).toString()
            val p4 = ((email.hashCode().inv().toLong() and 0xFFFFL) % 90000 + 10000).toString()
            val p5 = "48192"
            val p6 = "91024"
            return "$p1  $p2  $p3  $p4  $p5  $p6"
        }
    }
}

enum class CallType {
    AUDIO,
    VIDEO
}

enum class CallStatus {
    DIALING,
    INCOMING_RINGING,
    CONNECTED,
    ENDED
}

enum class NetworkStability(val activeBars: Int, val ratingLabel: String) {
    UNKNOWN(0, "Checking…"),
    EXCELLENT(4, "Excellent"),
    GOOD(3, "Good"),
    FAIR(2, "Fair"),
    POOR(1, "Weak"),
    DISCONNECTED(0, "Unstable");

    companion object {
        /**
         * Maps Agora's `onNetworkQuality` / `network-quality` codes (0 = unknown,
         * 1 = excellent, 2 = good, 3 = poor, 4 = bad, 5 = very bad, 6 = down)
         * using the worse of uplink (tx) and downlink (rx) when both are reported.
         */
        fun fromAgoraQuality(txQuality: Int, rxQuality: Int): NetworkStability {
            val effective = when {
                txQuality <= 0 && rxQuality <= 0 -> 0
                txQuality <= 0 -> rxQuality
                rxQuality <= 0 -> txQuality
                else -> maxOf(txQuality, rxQuality)
            }
            return when (effective) {
                1 -> EXCELLENT
                2 -> GOOD
                3 -> FAIR
                4, 5 -> POOR
                6 -> DISCONNECTED
                else -> UNKNOWN
            }
        }
    }
}

data class CallLogItem(
    val id: String,
    val contact: User,
    val callType: CallType,
    val isIncoming: Boolean,
    val isMissed: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Int = 0,
    val dataUsedKb: Long = 0L,
    val wasLowDataMode: Boolean = true,
    val wasEncrypted: Boolean = true
)

data class ActiveCallState(
    val callId: String,
    val contact: User,
    val callType: CallType,
    val status: CallStatus = CallStatus.DIALING,
    val isMuted: Boolean = false,
    val isCameraOn: Boolean = true,
    val isFrontCamera: Boolean = true,
    val isSpeakerOn: Boolean = true,
    val isLowDataMode: Boolean = true,
    val durationSeconds: Int = 0,
    val latencyMs: Int = 18,
    val dataRateKbps: Int = 42,
    val totalDataKb: Long = 0L,
    val isEndToEndEncrypted: Boolean = true,
    val encryptionCipher: String = "AES-256-GCM / SRTP P2P",
    val encryptionKeyFingerprint: String = contact.securityFingerprint,
    val sharedMediaUri: String? = null,
    val sharedMediaType: MediaType = MediaType.NONE,
    val sharedMediaTitle: String? = null,
    val isNoiseSuppressionEnabled: Boolean = true,
    val isEchoCancellationEnabled: Boolean = true,
    val isLowLightBoostEnabled: Boolean = false,
    val fps: Int = 60,
    val packetLossPercent: Float = 0.0f,
    val jitterMs: Int = 2,
    val networkQualityRating: String = "Checking…",
    val networkStability: NetworkStability = NetworkStability.UNKNOWN,
    val isMinimized: Boolean = false,
    val channelName: String = "",
    val remoteUid: Int? = null,
    val isAgoraConfigured: Boolean = false,
    val connectionNote: String? = null
)
