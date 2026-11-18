package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlin.random.Random

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

    // --- Active Call State Management ---

    fun startCall(contact: User, type: CallType) {
        val lowDataMode = (profileState.value as? UiState.Success)?.data?.lowDataCallsEnabled ?: true
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
            dataRateKbps = if (lowDataMode) {
                if (type == CallType.VIDEO) 42 else 12
            } else {
                if (type == CallType.VIDEO) 280 else 36
            },
            totalDataKb = 0L
        )
        _activeCall.value = initialCall

        callTickerJob?.cancel()
        callTickerJob = viewModelScope.launch(Dispatchers.Default) {
            delay(2200)
            if (_activeCall.value?.callId == initialCall.callId &&
                _activeCall.value?.status == CallStatus.DIALING
            ) {
                _activeCall.update { it?.copy(status = CallStatus.CONNECTED) }
                runCallTicker()
            }
        }
    }

    fun receiveIncomingCall(contact: User, type: CallType) {
        val lowDataMode = (profileState.value as? UiState.Success)?.data?.lowDataCallsEnabled ?: true
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
            latencyMs = 14,
            dataRateKbps = if (lowDataMode) 40 else 260,
            totalDataKb = 0L
        )
    }

    fun answerCall() {
        val current = _activeCall.value ?: return
        if (current.status == CallStatus.INCOMING_RINGING) {
            _activeCall.update { it?.copy(status = CallStatus.CONNECTED) }
            callTickerJob?.cancel()
            callTickerJob = viewModelScope.launch(Dispatchers.Default) {
                runCallTicker()
            }
        }
    }

    fun toggleMute() {
        _activeCall.update { it?.copy(isMuted = !it.isMuted) }
    }

    fun toggleCamera() {
        _activeCall.update { it?.copy(isCameraOn = !it.isCameraOn) }
    }

    fun toggleCameraFacing() {
        _activeCall.update { it?.copy(isFrontCamera = !it.isFrontCamera) }
    }

    fun setMinimized(minimized: Boolean) {
        _activeCall.update { it?.copy(isMinimized = minimized) }
    }

    fun endCall(persistLog: Boolean = true) {
        callTickerJob?.cancel()
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

    private suspend fun runCallTicker() {
        while (viewModelScope.isActive && _activeCall.value?.status == CallStatus.CONNECTED) {
            delay(1000)
            _activeCall.update { call ->
                if (call == null || call.status != CallStatus.CONNECTED) return@update call
                val baseLatency = if (call.isLowDataMode) 14 else 18
                val rate = if (call.isLowDataMode) {
                    if (call.callType == CallType.VIDEO && call.isCameraOn) {
                        Random.nextInt(38, 48)
                    } else {
                        Random.nextInt(10, 15)
                    }
                } else {
                    if (call.callType == CallType.VIDEO && call.isCameraOn) {
                        Random.nextInt(260, 320)
                    } else {
                        Random.nextInt(32, 44)
                    }
                }
                call.copy(
                    durationSeconds = call.durationSeconds + 1,
                    latencyMs = (baseLatency + Random.nextInt(-3, 4)).coerceAtLeast(8),
                    dataRateKbps = rate,
                    totalDataKb = call.totalDataKb + rate
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        callTickerJob?.cancel()
    }

    private companion object {
        const val TAG = "JustChatViewModel"
        const val STOP_TIMEOUT_MS = 5000L
    }
}
