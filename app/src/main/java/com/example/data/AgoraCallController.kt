package com.example.data

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.BuildConfig
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest

data class AgoraCallStats(
    val latencyMs: Int,
    val bitrateKbps: Int,
    val totalDataKb: Long,
    val packetLossPercent: Float
)

interface AgoraCallListener {
    fun onJoinedChannel(channel: String, uid: Int)
    fun onRemoteUserJoined(uid: Int)
    fun onRemoteUserLeft(uid: Int, reason: Int)
    fun onStatsUpdated(stats: AgoraCallStats)
    fun onNetworkQualityChanged(txQuality: Int, rxQuality: Int) = Unit
    fun onError(errorCode: Int, message: String)
}

/**
 * Manages the Agora Voice SDK (`io.agora.rtc:voice-sdk:4.5.0`) lifecycle
 * for real-time voice calls.
 *
 * Credentials and optional channel overrides are injected securely via
 * [BuildConfig.AGORA_APP_ID], [BuildConfig.AGORA_TEMP_TOKEN], and
 * [BuildConfig.AGORA_CHANNEL_NAME] from the AI Studio Secrets panel.
 */
class AgoraCallController(
    context: Context,
    private val myAppId: String = BuildConfig.AGORA_APP_ID.trim(),
    private val token: String = BuildConfig.AGORA_TEMP_TOKEN.trim(),
    private val configuredChannelName: String = BuildConfig.AGORA_CHANNEL_NAME.trim(),
    private val tokenProvider: AgoraTokenProvider = AgoraTokenProvider()
) {
    private val appContext: Context = context.applicationContext
    private val audioManager: AudioManager? by lazy {
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tokenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var mRtcEngine: RtcEngine? = null

    @Volatile
    private var activeListener: AgoraCallListener? = null

    @Volatile
    private var isInChannel: Boolean = false

    @Volatile
    private var activeChannelName: String = ""

    @Volatile
    private var activeUid: Int = 0

    @Volatile
    private var tokenJob: Job? = null

    @Volatile
    private var renewalJob: Job? = null

    val isConfigured: Boolean
        get() = isValidAppId(myAppId)

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun showToast(message: String) {
        runOnMainThread {
            try {
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to show toast: $message", t)
            }
        }
    }

    private fun enterVoiceCallAudioMode() {
        try {
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to set AudioManager.MODE_IN_COMMUNICATION", t)
        }
    }

    private fun resetAudioMode() {
        try {
            audioManager?.mode = AudioManager.MODE_NORMAL
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to reset AudioManager.MODE_NORMAL", t)
        }
    }

    private val mRtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            super.onJoinChannelSuccess(channel, uid, elapsed)
            isInChannel = true
            val joinedChannel = channel.orEmpty()
            showToast("Joined channel $joinedChannel")
            runOnMainThread {
                activeListener?.onJoinedChannel(joinedChannel, uid)
            }
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            super.onUserJoined(uid, elapsed)
            showToast("User joined: $uid")
            runOnMainThread {
                activeListener?.onRemoteUserJoined(uid)
            }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            super.onUserOffline(uid, reason)
            showToast("User offline: $uid")
            runOnMainThread {
                activeListener?.onRemoteUserLeft(uid, reason)
            }
        }

        override fun onRtcStats(stats: RtcStats?) {
            if (stats == null) return
            val totalBitrate = (stats.txKBitRate + stats.rxKBitRate).coerceAtLeast(0)
            val totalKb = ((stats.txBytes.toLong() + stats.rxBytes.toLong()) / 1024L).coerceAtLeast(0L)
            val packetLoss = maxOf(stats.txPacketLossRate, stats.rxPacketLossRate).toFloat()
            val mappedStats = AgoraCallStats(
                latencyMs = stats.lastmileDelay.coerceAtLeast(0),
                bitrateKbps = totalBitrate,
                totalDataKb = totalKb,
                packetLossPercent = packetLoss
            )
            runOnMainThread {
                activeListener?.onStatsUpdated(mappedStats)
            }
        }

        override fun onNetworkQuality(uid: Int, txQuality: Int, rxQuality: Int) {
            super.onNetworkQuality(uid, txQuality, rxQuality)
            // uid == 0 reports the local user's uplink (txQuality) and downlink (rxQuality) stability.
            if (uid != 0) return
            runOnMainThread {
                activeListener?.onNetworkQualityChanged(txQuality, rxQuality)
            }
        }

        override fun onError(err: Int) {
            Log.e(TAG, "Agora RTC error code: $err")
            val message = describeAgoraError(err)
            runOnMainThread {
                activeListener?.onError(err, message)
            }
        }

        override fun onTokenPrivilegeWillExpire(token: String?) {
            super.onTokenPrivilegeWillExpire(token)
            renewDynamicTokenIfConfigured()
        }

        override fun onRequestToken() {
            super.onRequestToken()
            renewDynamicTokenIfConfigured()
        }
    }

    private fun renewDynamicTokenIfConfigured() {
        val channel = activeChannelName
        val uid = activeUid
        if (!tokenProvider.isConfigured || channel.isBlank()) return
        renewalJob?.cancel()
        renewalJob = tokenScope.launch {
            tokenProvider.fetchToken(
                channelName = channel,
                uid = uid,
                role = AgoraTokenProvider.ROLE_PUBLISHER
            ).onSuccess { freshToken ->
                synchronized(lock) {
                    if (activeChannelName == channel) {
                        runCatching { mRtcEngine?.renewToken(freshToken) }
                            .onFailure { Log.w(TAG, "Failed to renew Agora RTC token", it) }
                    }
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                Log.w(TAG, "Unable to refresh Agora RTC token: ${err.message}")
            }
        }
    }

    /**
     * Initializes [mRtcEngine] using [RtcEngineConfig] if not already created.
     */
    private fun initializeAgoraVoiceSDK(): RtcEngine {
        mRtcEngine?.let { return it }
        try {
            val config = RtcEngineConfig().apply {
                mContext = appContext
                mAppId = myAppId
                mEventHandler = mRtcEventHandler
                mChannelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            }
            return RtcEngine.create(config).also { engine ->
                mRtcEngine = engine
            }
        } catch (t: Throwable) {
            Log.e(INIT_ERROR_TAG, "Error initializing RTC engine: ${t.message}", t)
            throw IllegalStateException("Error initializing RTC engine: ${t.message}", t)
        }
    }

    /**
     * Joins an Agora voice channel with [ChannelMediaOptions].
     * Uses `AGORA_CHANNEL_NAME` when explicitly configured (for Agora Console temporary tokens),
     * otherwise falls back to [targetChannelName].
     */
    private fun joinChannel(
        resolvedChannel: String,
        resolvedToken: String?,
        uid: Int
    ): Int {
        val options = ChannelMediaOptions().apply {
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            publishMicrophoneTrack = true
            autoSubscribeAudio = true
        }
        activeChannelName = resolvedChannel
        activeUid = uid
        return mRtcEngine?.joinChannel(resolvedToken, resolvedChannel, uid, options) ?: -1
    }

    fun joinCall(
        channelName: String,
        isSpeakerDefault: Boolean,
        isLowDataMode: Boolean,
        listener: AgoraCallListener,
        uid: Int = 0
    ): Boolean {
        if (!isConfigured) {
            Log.w(INIT_ERROR_TAG, "Agora App ID is missing or still set to a placeholder.")
            return false
        }

        val resolvedChannel = resolveChannelName(configuredChannelName, channelName)
        val staticToken = normalizeToken(token)

        // When a dynamic token server URL is configured and no static token override is provided,
        // fetch a short-lived RolePublisher RTC token asynchronously before joining.
        if (staticToken == null && tokenProvider.isConfigured) {
            synchronized(lock) {
                tokenJob?.cancel()
                activeListener = listener
                activeChannelName = resolvedChannel
                activeUid = uid
                tokenJob = tokenScope.launch {
                    val tokenResult = tokenProvider.fetchToken(
                        channelName = resolvedChannel,
                        uid = uid,
                        role = AgoraTokenProvider.ROLE_PUBLISHER
                    )
                    tokenResult.onSuccess { dynamicToken ->
                        if (activeListener === listener) {
                            joinCallWithToken(
                                resolvedChannel = resolvedChannel,
                                resolvedToken = dynamicToken,
                                uid = uid,
                                isSpeakerDefault = isSpeakerDefault,
                                isLowDataMode = isLowDataMode,
                                listener = listener
                            )
                        }
                    }.onFailure { err ->
                        if (err is CancellationException) throw err
                        Log.e(INIT_ERROR_TAG, "Failed to fetch dynamic Agora RTC token", err)
                        runOnMainThread {
                            listener.onError(
                                Constants.ERR_INVALID_TOKEN,
                                "Unable to fetch Agora token: ${err.localizedMessage ?: "server unreachable"}"
                            )
                        }
                    }
                }
            }
            return true
        }

        return joinCallWithToken(
            resolvedChannel = resolvedChannel,
            resolvedToken = staticToken,
            uid = uid,
            isSpeakerDefault = isSpeakerDefault,
            isLowDataMode = isLowDataMode,
            listener = listener
        )
    }

    private fun joinCallWithToken(
        resolvedChannel: String,
        resolvedToken: String?,
        uid: Int,
        isSpeakerDefault: Boolean,
        isLowDataMode: Boolean,
        listener: AgoraCallListener
    ): Boolean {
        synchronized(lock) {
            activeListener = listener
            return try {
                enterVoiceCallAudioMode()
                val engine = initializeAgoraVoiceSDK()

                if (isInChannel) {
                    engine.leaveChannel()
                    isInChannel = false
                }

                engine.enableAudio()
                engine.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
                engine.setAudioProfile(
                    if (isLowDataMode) {
                        Constants.AUDIO_PROFILE_SPEECH_STANDARD
                    } else {
                        Constants.AUDIO_PROFILE_DEFAULT
                    },
                    Constants.AUDIO_SCENARIO_DEFAULT
                )
                engine.setDefaultAudioRoutetoSpeakerphone(isSpeakerDefault)
                engine.setEnableSpeakerphone(isSpeakerDefault)
                engine.muteLocalAudioStream(false)

                val result = joinChannel(
                    resolvedChannel = resolvedChannel,
                    resolvedToken = resolvedToken,
                    uid = uid
                )
                if (result != 0) {
                    val errorText = describeJoinError(result)
                    Log.e(INIT_ERROR_TAG, "Agora joinChannel failed (code=$result): $errorText")
                    resetAudioMode()
                    runOnMainThread { listener.onError(result, errorText) }
                    false
                } else {
                    true
                }
            } catch (t: Throwable) {
                Log.e(INIT_ERROR_TAG, "Failed to initialize or join Agora channel: ${t.localizedMessage}", t)
                resetAudioMode()
                runOnMainThread {
                    listener.onError(
                        -1,
                        "Unable to initialize Agora RTC engine on this device"
                    )
                }
                false
            }
        }
    }

    fun setMuted(muted: Boolean) {
        synchronized(lock) {
            try {
                mRtcEngine?.muteLocalAudioStream(muted)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to toggle mute", t)
            }
        }
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        synchronized(lock) {
            try {
                mRtcEngine?.setEnableSpeakerphone(enabled)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to toggle speakerphone", t)
            }
        }
    }

    fun leaveCall() {
        synchronized(lock) {
            tokenJob?.cancel()
            tokenJob = null
            renewalJob?.cancel()
            renewalJob = null
            activeListener = null
            activeChannelName = ""
            val engine = mRtcEngine
            if (engine != null) {
                try {
                    if (isInChannel) {
                        engine.leaveChannel()
                        isInChannel = false
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Error while leaving Agora channel", t)
                }
            }
            resetAudioMode()
        }
    }

    fun destroy() {
        synchronized(lock) {
            tokenJob?.cancel()
            tokenJob = null
            renewalJob?.cancel()
            renewalJob = null
            activeListener = null
            activeChannelName = ""
            val engine = mRtcEngine
            if (engine != null) {
                try {
                    if (isInChannel) {
                        engine.leaveChannel()
                        isInChannel = false
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Error stopping Agora engine prior to destroy", t)
                }
                mRtcEngine = null
            }
            resetAudioMode()
        }
        try {
            RtcEngine.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "Error destroying Agora RtcEngine", t)
        }
    }

    companion object {
        private const val TAG = "AgoraCallController"
        private const val INIT_ERROR_TAG = "AgoraInitError"

        /**
         * `io.agora.rtc:voice-sdk:4.5.0` is audio-only.
         */
        const val SUPPORTS_VIDEO: Boolean = false

        private val PLACEHOLDER_VALUES = setOf(
            "",
            "YOUR_AGORA_APP_ID",
            "<YOUR APP ID>",
            "YAHAN_APNI_AGORA_APP_ID_PASTE_KAREIN",
            "AGORA_APP_ID"
        )

        fun isValidAppId(rawAppId: String): Boolean {
            val cleaned = rawAppId.trim()
            if (cleaned.length < 16) return false
            if (cleaned.uppercase() in PLACEHOLDER_VALUES) return false
            if (cleaned.contains("YOUR", ignoreCase = true) ||
                cleaned.contains("PASTE", ignoreCase = true)
            ) {
                return false
            }
            return cleaned.all { it.isLetterOrDigit() }
        }

        fun normalizeToken(rawToken: String): String? {
            val cleaned = rawToken.trim()
            if (cleaned.isEmpty()) return null
            if (cleaned.equals("OPTIONAL_AGORA_TEMP_TOKEN", ignoreCase = true) ||
                cleaned.equals("<Your token>", ignoreCase = true) ||
                cleaned.equals("NONE", ignoreCase = true) ||
                cleaned.startsWith("YOUR_", ignoreCase = true) ||
                cleaned.startsWith("OPTIONAL_", ignoreCase = true) ||
                (cleaned.startsWith("<") && cleaned.endsWith(">"))
            ) {
                return null
            }
            return cleaned
        }

        fun resolveChannelName(configuredOverride: String, fallbackChannelName: String): String {
            val cleaned = configuredOverride.trim()
            val isPlaceholder = cleaned.isEmpty() ||
                cleaned.equals("OPTIONAL_AGORA_CHANNEL_NAME", ignoreCase = true) ||
                cleaned.equals("<Your channel name>", ignoreCase = true) ||
                cleaned.equals("NONE", ignoreCase = true) ||
                cleaned.startsWith("YOUR_", ignoreCase = true) ||
                cleaned.startsWith("OPTIONAL_", ignoreCase = true) ||
                (cleaned.startsWith("<") && cleaned.endsWith(">"))
            return if (isPlaceholder) fallbackChannelName else cleaned
        }

        /**
         * Derives a deterministic, privacy-safe Agora channel name (<= 64 ASCII chars)
         * from two users' Gmail addresses so both peers automatically join the same room.
         */
        fun buildChannelName(emailA: String, emailB: String): String {
            val normalized = listOf(
                emailA.trim().lowercase().ifEmpty { "peer_a" },
                emailB.trim().lowercase().ifEmpty { "peer_b" }
            ).sorted()
            val seed = "${normalized[0]}|${normalized[1]}"
            val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }.take(24)
            return "jc_$hex"
        }

        fun describeAgoraError(code: Int): String = when (code) {
            Constants.ERR_INVALID_APP_ID ->
                "Invalid Agora App ID ($code). Check AGORA_APP_ID in AI Studio Secrets."
            Constants.ERR_TOKEN_EXPIRED,
            Constants.ERR_INVALID_TOKEN ->
                "Agora token error ($code). Set AGORA_TEMP_TOKEN in AI Studio Secrets or enable Testing Mode in Agora Console."
            Constants.ERR_JOIN_CHANNEL_REJECTED ->
                "Join channel rejected ($code)."
            Constants.ERR_INVALID_CHANNEL_NAME ->
                "Invalid Agora channel name ($code)."
            Constants.ERR_NO_PERMISSION ->
                "Microphone permission is required for Agora voice calling ($code)."
            else -> "Agora error code: $code"
        }

        fun describeJoinError(code: Int): String = when (code) {
            Constants.ERR_INVALID_APP_ID,
            Constants.ERR_TOKEN_EXPIRED,
            Constants.ERR_INVALID_TOKEN,
            Constants.ERR_JOIN_CHANNEL_REJECTED,
            Constants.ERR_INVALID_CHANNEL_NAME,
            Constants.ERR_NO_PERMISSION -> describeAgoraError(code)
            else -> "Join failed (code: $code)"
        }
    }
}
