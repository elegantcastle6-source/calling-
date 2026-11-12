package com.example.data

import com.example.model.ActiveCallState
import com.example.model.CallStatus
import com.example.model.CallType
import com.example.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

class CallManager(
    private val repository: ChatRepository,
    private val scope: CoroutineScope
) {
    private val _activeCall = MutableStateFlow<ActiveCallState?>(null)
    val activeCall: StateFlow<ActiveCallState?> = _activeCall.asStateFlow()

    private var callTickerJob: Job? = null

    fun startCall(contact: User, type: CallType, lowDataMode: Boolean = true) {
        val initialCall = ActiveCallState(
            callId = UUID.randomUUID().toString(),
            contact = contact,
            callType = type,
            status = CallStatus.DIALING,
            isMuted = false,
            isCameraOn = type == CallType.VIDEO,
            isFrontCamera = true,
            isSpeakerOn = type == CallType.VIDEO,
            isLowDataMode = lowDataMode,
            durationSeconds = 0,
            latencyMs = 15,
            dataRateKbps = if (lowDataMode) (if (type == CallType.VIDEO) 42 else 12) else (if (type == CallType.VIDEO) 280 else 36),
            totalDataKb = 0L
        )
        _activeCall.value = initialCall

        callTickerJob?.cancel()
        callTickerJob = scope.launch(Dispatchers.Default) {
            // Simulate ringing for 2 seconds then answer
            delay(2200)
            if (_activeCall.value?.callId == initialCall.callId && _activeCall.value?.status == CallStatus.DIALING) {
                _activeCall.update { it?.copy(status = CallStatus.CONNECTED) }
                runCallTicker()
            }
        }
    }

    fun receiveIncomingCall(contact: User, type: CallType, lowDataMode: Boolean = true) {
        val incoming = ActiveCallState(
            callId = UUID.randomUUID().toString(),
            contact = contact,
            callType = type,
            status = CallStatus.INCOMING_RINGING,
            isMuted = false,
            isCameraOn = type == CallType.VIDEO,
            isFrontCamera = true,
            isSpeakerOn = type == CallType.VIDEO,
            isLowDataMode = lowDataMode,
            durationSeconds = 0,
            latencyMs = 14,
            dataRateKbps = if (lowDataMode) 40 else 260,
            totalDataKb = 0L
        )
        _activeCall.value = incoming
    }

    fun answerCall() {
        val current = _activeCall.value ?: return
        if (current.status == CallStatus.INCOMING_RINGING) {
            _activeCall.update { it?.copy(status = CallStatus.CONNECTED) }
            callTickerJob?.cancel()
            callTickerJob = scope.launch(Dispatchers.Default) {
                runCallTicker()
            }
        }
    }

    private suspend fun runCallTicker() {
        while (scope.isActive && _activeCall.value?.status == CallStatus.CONNECTED) {
            delay(1000)
            _activeCall.update { call ->
                if (call == null || call.status != CallStatus.CONNECTED) return@update call
                val newDuration = call.durationSeconds + 1
                
                // Real-time jitter and latency simulation (No-Lag: 12ms - 22ms)
                val baseLatency = if (call.isLowDataMode) 14 else 18
                val currentLatency = baseLatency + Random.nextInt(-3, 4)

                // Low data calculation:
                // Video in Low Data: ~38-46 KB/s vs 250-320 KB/s
                // Audio in Low Data: ~10-14 KB/s vs 32-42 KB/s
                val rate = if (call.isLowDataMode) {
                    if (call.callType == CallType.VIDEO) {
                        if (call.isCameraOn) Random.nextInt(38, 48) else Random.nextInt(12, 16)
                    } else {
                        Random.nextInt(10, 15)
                    }
                } else {
                    if (call.callType == CallType.VIDEO) {
                        if (call.isCameraOn) Random.nextInt(260, 320) else Random.nextInt(34, 45)
                    } else {
                        Random.nextInt(32, 44)
                    }
                }

                call.copy(
                    durationSeconds = newDuration,
                    latencyMs = currentLatency.coerceAtLeast(8),
                    dataRateKbps = rate,
                    totalDataKb = call.totalDataKb + rate
                )
            }
        }
    }

    fun toggleMute() {
        _activeCall.update { it?.copy(isMuted = !(it.isMuted)) }
    }

    fun toggleCamera() {
        _activeCall.update { it?.copy(isCameraOn = !(it.isCameraOn)) }
    }

    fun toggleCameraFacing() {
        _activeCall.update { it?.copy(isFrontCamera = !(it.isFrontCamera)) }
    }

    fun toggleSpeaker() {
        _activeCall.update { it?.copy(isSpeakerOn = !(it.isSpeakerOn)) }
    }

    fun shareMediaInCall(uri: String, type: com.example.model.MediaType, title: String) {
        _activeCall.update {
            it?.copy(
                sharedMediaUri = uri,
                sharedMediaType = type,
                sharedMediaTitle = title
            )
        }
    }

    fun stopSharingMedia() {
        _activeCall.update {
            it?.copy(
                sharedMediaUri = null,
                sharedMediaType = com.example.model.MediaType.NONE,
                sharedMediaTitle = null
            )
        }
    }

    fun toggleLowDataMode() {
        _activeCall.update {
            if (it == null) null
            else {
                val newMode = !it.isLowDataMode
                val newRate = if (newMode) (if (it.callType == CallType.VIDEO) 42 else 12) else (if (it.callType == CallType.VIDEO) 280 else 38)
                it.copy(isLowDataMode = newMode, dataRateKbps = newRate)
            }
        }
    }

    fun toggleNoiseSuppression() {
        _activeCall.update { it?.copy(isNoiseSuppressionEnabled = !(it.isNoiseSuppressionEnabled)) }
    }

    fun toggleLowLightBoost() {
        _activeCall.update { it?.copy(isLowLightBoostEnabled = !(it.isLowLightBoostEnabled)) }
    }

    fun setMinimized(minimized: Boolean) {
        _activeCall.update { it?.copy(isMinimized = minimized) }
    }

    fun optimizeNetworkRoute() {
        _activeCall.update {
            it?.copy(
                latencyMs = 9,
                jitterMs = 1,
                packetLossPercent = 0.0f,
                networkQualityRating = "Ultra-Fast (60 FPS No-Lag)"
            )
        }
    }

    fun endCall() {
        callTickerJob?.cancel()
        val current = _activeCall.value
        if (current != null) {
            repository.recordCompletedCall(
                contact = current.contact,
                callType = current.callType,
                durationSeconds = current.durationSeconds,
                wasLowDataMode = current.isLowDataMode,
                dataUsedKb = current.totalDataKb
            )
        }
        _activeCall.value = null
    }
}
