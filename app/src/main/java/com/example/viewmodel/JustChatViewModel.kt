package com.example.viewmodel

import android.util.Log
import android.view.SurfaceView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AgoraCallController
import com.example.data.AgoraCallListener
import com.example.data.AgoraCallStats
import com.example.data.DirectoryUser
import com.example.data.FirestoreChatRepository
import com.example.data.UserManager
import com.example.data.UserProfileDoc
import com.example.data.unwrapFirestoreError
import com.example.model.ActiveCallState
import com.example.model.CallLogItem
import com.example.model.CallStatus
import com.example.model.CallType
import com.example.model.ChatMessage
import com.example.model.User
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

sealed interface UserSearchUiState {
    data object Idle : UserSearchUiState
    data object Searching : UserSearchUiState
    data class Found(val user: DirectoryUser) : UserSearchUiState
    data class NotFound(val email: String) : UserSearchUiState
    data class Error(val message: String) : UserSearchUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class JustChatViewModel(
    private val repository: FirestoreChatRepository,
    private val userManager: UserManager,
    private val agoraController: AgoraCallController,
    private val currentUserId: String,
    initialUser: FirebaseUser
) : ViewModel() {

    private val _selectedContact = MutableStateFlow<User?>(null)
    val selectedContact: StateFlow<User?> = _selectedContact.asStateFlow()

    private val _activeCall = MutableStateFlow<ActiveCallState?>(null)
    val activeCall: StateFlow<ActiveCallState?> = _activeCall.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val _userSearchState = MutableStateFlow<UserSearchUiState>(UserSearchUiState.Idle)
    val userSearchState: StateFlow<UserSearchUiState> = _userSearchState.asStateFlow()

    private val _isSignedOut = MutableStateFlow(false)

    private var callTickerJob: Job? = null
    private var searchJob: Job? = null

    val fallbackUser: User = User(
        id = currentUserId,
        name = initialUser.displayName?.takeIf { it.isNotBlank() }
            ?: initialUser.email?.substringBefore("@")
            ?: "Me",
        gmail = initialUser.email.orEmpty(),
        avatarColorHex = 0xFF4F46E5L,
        statusMessage = "Using Just Chat"
    )

    val profileState: StateFlow<UiState<UserProfileDoc?>> = _isSignedOut
        .flatMapLatest { signedOut ->
            if (signedOut) {
                emptyFlow()
            } else {
                repository.observeUserProfile(currentUserId)
                    .map<UserProfileDoc?, UiState<UserProfileDoc?>> { UiState.Success(it) }
                    .catch { error ->
                        val firestoreError = unwrapFirestoreError(error)
                        if (firestoreError == null && error is CancellationException) throw error
                        val rootError = firestoreError ?: error
                        Log.w(TAG, "Error observing profile", rootError)
                        emit(UiState.Error(rootError.message ?: "Failed to load profile"))
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    val contactsState: StateFlow<UiState<List<User>>> = _isSignedOut
        .flatMapLatest { signedOut ->
            if (signedOut) {
                emptyFlow()
            } else {
                repository.observeContacts(currentUserId)
                    .map<List<User>, UiState<List<User>>> { UiState.Success(it) }
                    .catch { error ->
                        val firestoreError = unwrapFirestoreError(error)
                        if (firestoreError == null && error is CancellationException) throw error
                        val rootError = firestoreError ?: error
                        Log.w(TAG, "Error observing contacts", rootError)
                        emit(UiState.Error(rootError.message ?: "Failed to load contacts"))
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    val callLogsState: StateFlow<UiState<List<CallLogItem>>> = _isSignedOut
        .flatMapLatest { signedOut ->
            if (signedOut) {
                emptyFlow()
            } else {
                repository.observeCallLogs(currentUserId)
                    .map<List<CallLogItem>, UiState<List<CallLogItem>>> { UiState.Success(it) }
                    .catch { error ->
                        val firestoreError = unwrapFirestoreError(error)
                        if (firestoreError == null && error is CancellationException) throw error
                        val rootError = firestoreError ?: error
                        Log.w(TAG, "Error observing call logs", rootError)
                        emit(UiState.Error(rootError.message ?: "Failed to load call logs"))
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    val activeMessagesState: StateFlow<UiState<List<ChatMessage>>> = combine(
        _isSignedOut,
        _selectedContact
    ) { signedOut, contact -> if (signedOut) null else contact }
        .flatMapLatest { contact ->
            if (contact == null) {
                flowOf(UiState.Success(emptyList()))
            } else {
                repository.observeMessages(currentUserId, contact.id)
                    .map<List<ChatMessage>, UiState<List<ChatMessage>>> { UiState.Success(it) }
                    .catch { error ->
                        val firestoreError = unwrapFirestoreError(error)
                        if (firestoreError == null && error is CancellationException) throw error
                        val rootError = firestoreError ?: error
                        Log.w(TAG, "Error observing messages", rootError)
                        emit(UiState.Error(rootError.message ?: "Failed to load messages"))
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    init {
        viewModelScope.launch {
            repository.ensureUserProfile(initialUser).onFailure { error ->
                _actionError.value = error.localizedMessage ?: "Failed to initialize profile."
            }
            // Register verified Google account in /userDirectory/{sha256(email)} so peers can find them
            userManager.saveUserToDatabase().onFailure { error ->
                Log.w(TAG, "Directory registration skipped or failed: ${error.message}")
            }
        }
    }

    fun selectContact(contact: User?) {
        _selectedContact.value = contact
    }

    fun clearActionError() {
        _actionError.value = null
    }

    fun searchUserByEmail(email: String) {
        searchJob?.cancel()
        val trimmed = email.trim()
        if (trimmed.isEmpty()) {
            _userSearchState.value = UserSearchUiState.Idle
            return
        }
        searchJob = viewModelScope.launch {
            _userSearchState.value = UserSearchUiState.Searching
            userManager.searchUserByEmail(trimmed)
                .onSuccess { match ->
                    _userSearchState.value = if (match != null) {
                        UserSearchUiState.Found(match)
                    } else {
                        UserSearchUiState.NotFound(trimmed.lowercase())
                    }
                }
                .onFailure { error ->
                    _userSearchState.value = UserSearchUiState.Error(
                        error.localizedMessage ?: "Unable to search directory right now."
                    )
                }
        }
    }

    fun clearUserSearch() {
        searchJob?.cancel()
        _userSearchState.value = UserSearchUiState.Idle
    }

    fun prepareForSignOut() {
        _isSignedOut.value = true
        searchJob?.cancel()
        _userSearchState.value = UserSearchUiState.Idle
        endCall(persistLog = false)
        _selectedContact.value = null
    }

    fun addContactByGmail(gmail: String, name: String?, onAdded: (User) -> Unit) {
        val currentContacts = (contactsState.value as? UiState.Success)?.data.orEmpty()
        viewModelScope.launch {
            repository.addContactByGmail(currentUserId, gmail, name, currentContacts)
                .onSuccess { newContact -> onAdded(newContact) }
                .onFailure { error ->
                    _actionError.value = error.localizedMessage ?: "Unable to add contact."
                }
        }
    }

    fun sendMessage(contactId: String, text: String) {
        viewModelScope.launch {
            repository.sendMessage(currentUserId, contactId, text)
                .onFailure { error ->
                    _actionError.value = error.localizedMessage ?: "Unable to send message."
                }
        }
    }

    fun toggleReaction(contactId: String, message: ChatMessage, emoji: String) {
        viewModelScope.launch {
            repository.toggleReaction(currentUserId, contactId, message, emoji)
                .onFailure { error ->
                    _actionError.value = error.localizedMessage ?: "Unable to update reaction."
                }
        }
    }

    fun setLowDataCallsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateLowDataCallsEnabled(currentUserId, enabled)
                .onFailure { error ->
                    _actionError.value = error.localizedMessage ?: "Unable to update setting."
                }
        }
    }

    fun setLowLatencyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateLowLatencyEnabled(currentUserId, enabled)
                .onFailure { error ->
                    _actionError.value = error.localizedMessage ?: "Unable to update setting."
                }
        }
    }

    // --- Active Call State Management (Agora RTC) ---

    private fun currentUserEmail(): String {
        return (profileState.value as? UiState.Success)?.data?.email
            ?.takeIf { it.isNotBlank() }
            ?: fallbackUser.gmail
    }

    fun startCall(contact: User, type: CallType) {
        callTickerJob?.cancel()
        agoraController.leaveCall()

        val lowDataMode = (profileState.value as? UiState.Success)?.data?.lowDataCallsEnabled ?: true
        val channelName = AgoraCallController.buildChannelName(currentUserEmail(), contact.gmail)
        val agoraConfigured = agoraController.isConfigured
        val callId = UUID.randomUUID().toString()

        val initialCall = ActiveCallState(
            callId = callId,
            contact = contact,
            callType = type,
            status = CallStatus.DIALING,
            isMuted = false,
            isCameraOn = type == CallType.VIDEO,
            isFrontCamera = true,
            isSpeakerOn = type == CallType.VIDEO,
            isLowDataMode = lowDataMode,
            durationSeconds = 0,
            latencyMs = 0,
            dataRateKbps = 0,
            totalDataKb = 0L,
            channelName = channelName,
            remoteUid = null,
            isAgoraConfigured = agoraConfigured,
            connectionNote = if (agoraConfigured) {
                "Connecting via Agora RTC…"
            } else {
                MISSING_AGORA_NOTE
            }
        )
        _activeCall.value = initialCall

        if (agoraConfigured) {
            agoraController.joinCall(
                channelName = channelName,
                isVideo = type == CallType.VIDEO,
                isLowDataMode = lowDataMode,
                listener = createAgoraListener(callId = callId, contactName = contact.name)
            )
        }
    }

    fun receiveIncomingCall(contact: User, type: CallType) {
        callTickerJob?.cancel()
        agoraController.leaveCall()

        val lowDataMode = (profileState.value as? UiState.Success)?.data?.lowDataCallsEnabled ?: true
        val channelName = AgoraCallController.buildChannelName(currentUserEmail(), contact.gmail)
        val agoraConfigured = agoraController.isConfigured

        _activeCall.value = ActiveCallState(
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
            latencyMs = 0,
            dataRateKbps = 0,
            totalDataKb = 0L,
            channelName = channelName,
            remoteUid = null,
            isAgoraConfigured = agoraConfigured,
            connectionNote = if (agoraConfigured) null else MISSING_AGORA_NOTE
        )
    }

    fun answerCall() {
        val current = _activeCall.value ?: return
        if (current.status != CallStatus.INCOMING_RINGING) return

        if (agoraController.isConfigured) {
            _activeCall.update {
                it?.copy(
                    status = CallStatus.DIALING,
                    isAgoraConfigured = true,
                    connectionNote = "Connecting via Agora RTC…"
                )
            }
            agoraController.joinCall(
                channelName = current.channelName.ifBlank {
                    AgoraCallController.buildChannelName(currentUserEmail(), current.contact.gmail)
                },
                isVideo = current.callType == CallType.VIDEO,
                isLowDataMode = current.isLowDataMode,
                listener = createAgoraListener(callId = current.callId, contactName = current.contact.name)
            )
        } else {
            _activeCall.update {
                it?.copy(
                    status = CallStatus.DIALING,
                    isAgoraConfigured = false,
                    connectionNote = MISSING_AGORA_NOTE
                )
            }
        }
    }

    fun toggleMute() {
        val nextMuted = !(_activeCall.value?.isMuted ?: false)
        _activeCall.update { it?.copy(isMuted = nextMuted) }
        if (_activeCall.value?.isAgoraConfigured == true) {
            agoraController.setMuted(nextMuted)
        }
    }

    fun toggleCamera() {
        val current = _activeCall.value ?: return
        val nextCameraOn = !current.isCameraOn
        _activeCall.update { it?.copy(isCameraOn = nextCameraOn) }
        if (current.isAgoraConfigured) {
            agoraController.setCameraEnabled(nextCameraOn, current.isLowDataMode)
        }
    }

    fun toggleCameraFacing() {
        val current = _activeCall.value ?: return
        _activeCall.update { it?.copy(isFrontCamera = !it.isFrontCamera) }
        if (current.isAgoraConfigured) {
            agoraController.switchCamera()
        }
    }

    fun toggleSpeaker() {
        val current = _activeCall.value ?: return
        val nextSpeaker = !current.isSpeakerOn
        _activeCall.update { it?.copy(isSpeakerOn = nextSpeaker) }
        if (current.isAgoraConfigured) {
            agoraController.setSpeakerEnabled(nextSpeaker)
        }
    }

    fun attachLocalVideo(surfaceView: SurfaceView) {
        agoraController.attachLocalVideo(surfaceView)
    }

    fun detachLocalVideo() {
        agoraController.detachLocalVideo()
    }

    fun attachRemoteVideo(uid: Int, surfaceView: SurfaceView) {
        agoraController.attachRemoteVideo(uid, surfaceView)
    }

    fun detachRemoteVideo(uid: Int) {
        agoraController.detachRemoteVideo(uid)
    }

    fun setMinimized(minimized: Boolean) {
        _activeCall.update { it?.copy(isMinimized = minimized) }
    }

    fun endCall(persistLog: Boolean = true) {
        callTickerJob?.cancel()
        agoraController.leaveCall()
        val finishedCall = _activeCall.value
        _activeCall.value = null
        if (persistLog && finishedCall != null) {
            val currentSaved = (profileState.value as? UiState.Success)?.data?.totalDataSavedMb ?: 0.0
            viewModelScope.launch {
                repository.recordCompletedCall(
                    userId = currentUserId,
                    contact = finishedCall.contact,
                    callType = finishedCall.callType,
                    durationSeconds = finishedCall.durationSeconds,
                    wasLowDataMode = finishedCall.isLowDataMode,
                    dataUsedKb = finishedCall.totalDataKb,
                    currentTotalDataSavedMb = currentSaved
                ).onFailure { error ->
                    _actionError.value = error.localizedMessage ?: "Failed to save call log."
                }
            }
        }
    }

    private fun createAgoraListener(callId: String, contactName: String): AgoraCallListener {
        return object : AgoraCallListener {
            override fun onJoinedChannel(channel: String, uid: Int) {
                if (_activeCall.value?.callId != callId) return
                _activeCall.update { call ->
                    if (call == null || call.callId != callId) return@update call
                    call.copy(
                        status = CallStatus.CONNECTED,
                        channelName = channel.ifBlank { call.channelName },
                        connectionNote = if (call.remoteUid == null) {
                            "Connected • Waiting for $contactName to join"
                        } else {
                            null
                        }
                    )
                }
                startCallDurationTicker(callId)
            }

            override fun onRemoteUserJoined(uid: Int) {
                _activeCall.update { call ->
                    if (call == null || call.callId != callId) return@update call
                    call.copy(
                        status = CallStatus.CONNECTED,
                        remoteUid = uid,
                        connectionNote = null
                    )
                }
            }

            override fun onRemoteUserLeft(uid: Int, reason: Int) {
                _activeCall.update { call ->
                    if (call == null || call.callId != callId) return@update call
                    if (call.remoteUid != uid) return@update call
                    call.copy(
                        remoteUid = null,
                        connectionNote = "$contactName left the call"
                    )
                }
            }

            override fun onStatsUpdated(stats: AgoraCallStats) {
                _activeCall.update { call ->
                    if (call == null || call.callId != callId) return@update call
                    call.copy(
                        latencyMs = stats.latencyMs,
                        dataRateKbps = stats.bitrateKbps,
                        totalDataKb = stats.totalDataKb,
                        packetLossPercent = stats.packetLossPercent
                    )
                }
            }

            override fun onError(errorCode: Int, message: String) {
                _activeCall.update { call ->
                    if (call == null || call.callId != callId) return@update call
                    call.copy(connectionNote = message)
                }
            }
        }
    }

    private fun startCallDurationTicker(callId: String) {
        callTickerJob?.cancel()
        callTickerJob = viewModelScope.launch(Dispatchers.Default) {
            while (viewModelScope.isActive) {
                delay(1000)
                val active = _activeCall.value
                if (active == null || active.callId != callId || active.status != CallStatus.CONNECTED) {
                    break
                }
                _activeCall.update { call ->
                    if (call == null || call.callId != callId || call.status != CallStatus.CONNECTED) {
                        return@update call
                    }
                    call.copy(durationSeconds = call.durationSeconds + 1)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        callTickerJob?.cancel()
        agoraController.destroy()
    }

    private companion object {
        const val TAG = "JustChatViewModel"
        const val STOP_TIMEOUT_MS = 5000L
        const val MISSING_AGORA_NOTE =
            "Set AGORA_APP_ID in AI Studio Secrets to connect live Agora voice & video calls."
    }
}
