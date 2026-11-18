package com.example.data

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import com.example.BuildConfig
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.VideoCanvas
import io.agora.rtc2.video.VideoEncoderConfiguration
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
    fun onError(errorCode: Int, message: String)
}

/**
 * Manages the Agora RTC Engine lifecycle for real-time voice and video calls.
 *
 * Reads credentials from [BuildConfig.AGORA_APP_ID] and optional [BuildConfig.AGORA_TEMP_TOKEN],
 * populated via the Secrets Gradle Plugin (.env / AI Studio Secrets panel).
 */
class AgoraCallController(
    context: Context,
    private val appId: String = BuildConfig.AGORA_APP_ID.trim(),
    private val tempToken: String = BuildConfig.AGORA_TEMP_TOKEN.trim()
) {
    private val appContext: Context = context.applicationContext
    private val lock = Any()

    @Volatile
    private var rtcEngine: RtcEngine? = null

    @Volatile
    private var activeListener: AgoraCallListener? = null

    @Volatile
    private var isInChannel: Boolean = false

    val isConfigured: Boolean
        get() = isValidAppId(appId)

    private val rtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            isInChannel = true
            activeListener?.onJoinedChannel(channel.orEmpty(), uid)
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            activeListener?.onRemoteUserJoined(uid)
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            activeListener?.onRemoteUserLeft(uid, reason)
        }

        override fun onRtcStats(stats: RtcStats?) {
            if (stats == null) return
            val totalBitrate = (stats.txKBitRate + stats.rxKBitRate).coerceAtLeast(0)
            val totalKb = ((stats.txBytes.toLong() + stats.rxBytes.toLong()) / 1024L).coerceAtLeast(0L)
            val packetLoss = maxOf(stats.txPacketLossRate, stats.rxPacketLossRate).toFloat()
            activeListener?.onStatsUpdated(
                AgoraCallStats(
                    latencyMs = stats.lastmileDelay.coerceAtLeast(0),
                    bitrateKbps = totalBitrate,
                    totalDataKb = totalKb,
                    packetLossPercent = packetLoss
                )
            )
        }

        override fun onError(err: Int) {
            Log.e(TAG, "Agora RTC error code: $err")
            activeListener?.onError(err, describeAgoraError(err))
        }
    }

    fun joinCall(
        channelName: String,
        isVideo: Boolean,
        isLowDataMode: Boolean,
        listener: AgoraCallListener
    ): Boolean {
        if (!isConfigured) {
            Log.w(TAG, "Agora App ID is missing or still set to a placeholder.")
            return false
        }

        synchronized(lock) {
            activeListener = listener
            val engine = getOrCreateEngine() ?: run {
                listener.onError(-1, "Unable to initialize Agora RTC engine on this device.")
                return false
            }

            return try {
                engine.enableAudio()
                engine.setEnableSpeakerphone(isVideo)
                engine.muteLocalAudioStream(false)

                if (isVideo) {
                    engine.enableVideo()
                    engine.setVideoEncoderConfiguration(buildVideoConfig(isLowDataMode))
                    engine.enableLocalVideo(true)
                    engine.muteLocalVideoStream(false)
                    engine.startPreview()
                } else {
                    engine.disableVideo()
                }

                val options = ChannelMediaOptions().apply {
                    channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
                    clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                    autoSubscribeAudio = true
                    autoSubscribeVideo = isVideo
                    publishMicrophoneTrack = true
                    publishCameraTrack = isVideo
                }

                val tokenOrNull = normalizeToken(tempToken)
                val result = engine.joinChannel(tokenOrNull, channelName, 0, options)
                if (result != 0) {
                    Log.e(TAG, "Agora joinChannel returned error code: $result")
                    listener.onError(result, describeAgoraError(result))
                    false
                } else {
                    true
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to join Agora channel", t)
                listener.onError(-2, t.localizedMessage ?: "Failed to join Agora channel.")
                false
            }
        }
    }

    fun attachLocalVideo(surfaceView: SurfaceView) {
        synchronized(lock) {
            val engine = rtcEngine ?: return
            try {
                engine.setupLocalVideo(
                    VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, 0)
                )
                engine.startPreview()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to attach local Agora video canvas", t)
            }
        }
    }

    fun detachLocalVideo() {
        synchronized(lock) {
            val engine = rtcEngine ?: return
            try {
                engine.setupLocalVideo(
                    VideoCanvas(null, VideoCanvas.RENDER_MODE_HIDDEN, 0)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to detach local Agora video canvas", t)
            }
        }
    }

    fun attachRemoteVideo(uid: Int, surfaceView: SurfaceView) {
        synchronized(lock) {
            val engine = rtcEngine ?: return
            try {
                engine.setupRemoteVideo(
                    VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, uid)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to attach remote Agora video canvas for uid=$uid", t)
            }
        }
    }

    fun detachRemoteVideo(uid: Int) {
        synchronized(lock) {
            val engine = rtcEngine ?: return
            try {
                engine.setupRemoteVideo(
                    VideoCanvas(null, VideoCanvas.RENDER_MODE_HIDDEN, uid)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to detach remote Agora video canvas for uid=$uid", t)
            }
        }
    }

    fun setMuted(muted: Boolean) {
        synchronized(lock) {
            try {
                rtcEngine?.muteLocalAudioStream(muted)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to toggle mute", t)
            }
        }
    }

    fun setCameraEnabled(enabled: Boolean, isLowDataMode: Boolean) {
        synchronized(lock) {
            val engine = rtcEngine ?: return
            try {
                if (enabled) {
                    engine.enableVideo()
                    engine.setVideoEncoderConfiguration(buildVideoConfig(isLowDataMode))
                    engine.enableLocalVideo(true)
                    engine.muteLocalVideoStream(false)
                    engine.startPreview()
                } else {
                    engine.muteLocalVideoStream(true)
                    engine.enableLocalVideo(false)
                    engine.stopPreview()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to toggle local camera", t)
            }
        }
    }

    fun switchCamera() {
        synchronized(lock) {
            try {
                rtcEngine?.switchCamera()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to switch camera", t)
            }
        }
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        synchronized(lock) {
            try {
                rtcEngine?.setEnableSpeakerphone(enabled)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to toggle speakerphone", t)
            }
        }
    }

    fun leaveCall() {
        synchronized(lock) {
            activeListener = null
            val engine = rtcEngine ?: return
            try {
                engine.stopPreview()
                if (isInChannel) {
                    engine.leaveChannel()
                    isInChannel = false
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error while leaving Agora channel", t)
            }
        }
    }

    fun destroy() {
        synchronized(lock) {
            activeListener = null
            val engine = rtcEngine ?: return
            try {
                engine.stopPreview()
                if (isInChannel) {
                    engine.leaveChannel()
                    isInChannel = false
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error stopping Agora engine prior to destroy", t)
            }
            rtcEngine = null
        }
        try {
            RtcEngine.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "Error destroying Agora RtcEngine", t)
        }
    }

    private fun getOrCreateEngine(): RtcEngine? {
        rtcEngine?.let { return it }
        return try {
            val config = RtcEngineConfig().apply {
                mContext = appContext
                mAppId = appId
                mEventHandler = rtcEventHandler
                mChannelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            }
            RtcEngine.create(config).also { rtcEngine = it }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create Agora RtcEngine", t)
            null
        }
    }

    private fun buildVideoConfig(isLowDataMode: Boolean): VideoEncoderConfiguration {
        return if (isLowDataMode) {
            VideoEncoderConfiguration(
                VideoEncoderConfiguration.VD_320x240,
                VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                220,
                VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE
            )
        } else {
            VideoEncoderConfiguration(
                VideoEncoderConfiguration.VD_640x360,
                VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_24,
                VideoEncoderConfiguration.STANDARD_BITRATE,
                VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE
            )
        }
    }

    companion object {
        private const val TAG = "AgoraCallController"

        private val PLACEHOLDER_VALUES = setOf(
            "",
            "YOUR_AGORA_APP_ID",
            "YAHAN_APNI_AGORA_APP_ID_PASTE_KAREIN",
            "AGORA_APP_ID"
        )

        fun isValidAppId(rawAppId: String): Boolean {
            val cleaned = rawAppId.trim()
            if (cleaned.length < 16) return false
            if (cleaned.uppercase() in PLACEHOLDER_VALUES) return false
            if (cleaned.contains("YOUR_", ignoreCase = true) ||
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
                cleaned.equals("NONE", ignoreCase = true) ||
                cleaned.startsWith("YOUR_", ignoreCase = true) ||
                cleaned.startsWith("OPTIONAL_", ignoreCase = true)
            ) {
                return null
            }
            return cleaned
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
                "Invalid Agora App ID. Check AGORA_APP_ID in AI Studio Secrets."
            Constants.ERR_INVALID_TOKEN,
            Constants.ERR_TOKEN_EXPIRED ->
                "Agora token is required or expired. Set AGORA_TEMP_TOKEN in AI Studio Secrets or enable Testing Mode in Agora Console."
            Constants.ERR_INVALID_CHANNEL_NAME ->
                "Invalid Agora channel name."
            Constants.ERR_NO_PERMISSION ->
                "Microphone or camera permission is required for Agora calling."
            else -> "Agora connection error ($code)."
        }
    }
}
