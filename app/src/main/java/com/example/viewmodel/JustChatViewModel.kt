package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AgoraCallController
import com.example.data.AgoraCallListener
import com.example.data.AgoraCallStats
import com.example.data.CallInvite
import com.example.data.CallSignaling
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
import com.example.model.MessageStatus
import com.example.model.NetworkStability
import com.example.model.User
import com.example.utils.CallRingtonePlayer
import com.example.utils.UserPresenceManager
import com.example.utils.buildChatRoomId
import com.google.firebase.auth.FirebaseUser
import com.justchat.app.sync.JustChatManager
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
import kotlinx.coroutines.flow.onEach
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
    private val callSignaling: CallSignaling,
    private val agoraController: AgoraCallController,
    private val currentUserId: String,
    initialUser: FirebaseUser,
    private val onIncomingMessageAlert: (User, ChatMessage) -> Unit = { _, _ -> },
    private val onIncomingCallAlert: (ActiveCallState) -> Unit = {},
    private val onCancelCallAlert: () -> Unit = {}
) : ViewModel() {

    private val _selectedContact = MutableStateFlow<User?>(null)
    private val _livePresenceByEmail = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    val selectedContact: StateFlow<User?> = combine(
        _selectedContact,
        _livePresenceByEmail
    ) { contact, presenceMap ->
        contact?.withLivePresence(presenceMap)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = null
    )

    private val _activeCall = MutableStateFlow<ActiveCallState?>(null)
    val activeCall: StateFlow<ActiveCallState?> = _activeCall.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val _userSearchState = MutableStateFlow<UserSearchUiState>(UserSearchUiState.Idle)
    val userSearchState: StateFlow<UserSearchUiState> = _userSearchState.asStateFlow()

    private val _isSignedOut = MutableStateFlow(false)

    private var callTickerJob: Job? = null
    private var callSignalingJob: Job? = null
    private var incomingCallsJob: Job? = null
    private var searchJob: Job? = null
    private var activeCallRoomId: String? = null
    private var activeInviteUpdatedAtMs: Long = 0L
    private val dismissedInviteUpdatedAt = mutableMapOf<String, Long>()
    private val presenceJobsByEmail = mutableMapOf<String, Job>()
    private val deliveryJobsByEmail = mutableMapOf<String, Job>()
    private val acknowledgedDeliveredIds = mutableSetOf<String>()
    private val acknowledgedReadIds = mutableSetOf<String>()
    private val initializedDeliveryRooms = mutableSetOf<String>()
    private val notifiedMessageIds = mutableSetOf<String>()

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
                combine(
                    repository.observeContacts(currentUserId),
                    _livePresenceByEmail
                ) { contacts, presenceMap ->
                    ensurePresenceObservers(contacts)
                    ensureDeliveryReceiptObservers(contacts)
                    contacts.map { it.withLivePresence(presenceMap) }
                }
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
                repository.observeSharedMessages(
                    currentUserId = currentUserId,
                    currentUserEmail = currentUserEmail(),
                    contactEmail = contact.gmail
                )
                    .onEach { messages ->
                        markIncomingMessagesAsRead(contact, messages)
                    }
                    .map<List<ChatMessage>, UiState<List<ChatMessage>>> { UiState.Success(it) }
                    .catch { error ->
                        val firestoreError = unwrapFirestoreError(error)
                        if (firestoreError == null && error is CancellationException) throw error
                        val rootError = firestoreError ?: error
                        Log.w(TAG, "Error observing shared messages", rootError)
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
        JustChatManager.startPresenceTracking()
        viewModelScope.launch {
            repository.ensureUserProfile(initialUser).onFailure { error ->
                _actionError.value = error.localizedMessage ?: "Failed to initialize profile."
            }
            // Register verified Google account in /userDirectory/{sha256(email)} so peers can find them
            userManager.saveUserToDatabase().onFailure { error ->
                Log.w(TAG, "Directory registration skipped or failed: ${error.message}")
            }
        }
        startObservingIncomingCalls()
    }

    private fun startObservingIncomingCalls() {
        incomingCallsJob?.cancel()
        incomingCallsJob = viewModelScope.launch {
            _isSignedOut
                .flatMapLatest { signedOut ->
                    if (signedOut) {
                        emptyFlow()
                    } else {
                        callSignaling.observeIncomingCalls(currentUserId, currentUserEmail())
                    }
                }
                .catch { error ->
                    val firestoreError = unwrapFirestoreError(error)
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Error observing incoming calls", firestoreError ?: error)
                }
                .collect { invites ->
                    if (_activeCall.value != null) return@collect
                    val incoming = invites.firstOrNull { invite ->
                        dismissedInviteUpdatedAt[invite.chatRoomId] != invite.updatedAtMillis
                    } ?: return@collect
                    presentIncomingSignaledCall(incoming)
                }
        }
    }

    private fun presentIncomingSignaledCall(invite: CallInvite) {
        val contacts = (contactsState.value as? UiState.Success)?.data.orEmpty()
        val callerContact = contacts.firstOrNull {
            it.gmail.equals(invite.caller, ignoreCase = true)
        } ?: User(
            id = invite.callerUid.ifBlank { invite.caller },
            name = invite.callerName.ifBlank {
                invite.caller.substringBefore("@").ifBlank { "Caller" }
            },
            gmail = invite.caller,
            avatarColorHex = 0xFF0EA5E9L,
            statusMessage = "Calling via Just Chat",
            isOnline = true,
            lastSeenText = "Active now"
        )

        val lowDataMode = (profileState.value as? UiState.Success)?.data?.lowDataCallsEnabled ?: true
        val type = invite.parsedCallType()
        val callId = UUID.randomUUID().toString()
        val agoraConfigured = agoraController.isConfigured

        activeCallRoomId = invite.chatRoomId
        activeInviteUpdatedAtMs = invite.updatedAtMillis

        val incomingState = ActiveCallState(
            callId = callId,
            contact = callerContact,
            callType = type,
            status = CallStatus.INCOMING_RINGING,
            isMuted = false,
            isCameraOn = type == CallType.VIDEO,
            isFrontCamera = true,
            isSpeakerOn = true,
            isLowDataMode = lowDataMode,
            durationSeconds = 0,
            latencyMs = 0,
            dataRateKbps = 0,
            totalDataKb = 0L,
            channelName = invite.channelName,
            remoteUid = null,
            isAgoraConfigured = agoraConfigured,
            connectionNote = if (agoraConfigured) null else MISSING_AGORA_NOTE
        )
        _activeCall.value = incomingState
        onIncomingCallAlert(incomingState)

        callSignalingJob?.cancel()
        callSignalingJob = viewModelScope.launch {
            collectCallSignaling(
                callId = callId,
                chatRoomId = invite.chatRoomId,
                contactName = callerContact.name,
                isCaller = false
            )
        }
    }

    private suspend fun collectCallSignaling(
        callId: String,
        chatRoomId: String,
        contactName: String,
        isCaller: Boolean
    ) {
        callSignaling.observeCall(currentUserId, chatRoomId)
            .catch { error ->
                val firestoreError = unwrapFirestoreError(error)
                if (firestoreError == null && error is CancellationException) throw error
                Log.w(TAG, "Error observing call room $chatRoomId", firestoreError ?: error)
            }
            .collect { invite ->
                val current = _activeCall.value
                if (current == null || current.callId != callId) return@collect
                if (invite == null) return@collect

                activeInviteUpdatedAtMs = invite.updatedAtMillis
                when (invite.status) {
                    CallInvite.STATUS_ACCEPTED -> {
                        _activeCall.update { call ->
                            if (call == null || call.callId != callId) return@update call
                            val nextNote = when {
                                call.remoteUid != null -> null
                                !call.isAgoraConfigured -> MISSING_AGORA_NOTE
                                isCaller -> "$contactName accepted • Connecting audio…"
                                else -> "Connected • Waiting for $contactName audio…"
                            }
                            call.copy(
                                status = CallStatus.CONNECTED,
                                connectionNote = nextNote
                            )
                        }
                        if (callTickerJob == null || callTickerJob?.isActive != true) {
                            startCallDurationTicker(callId)
                        }
                    }

                    CallInvite.STATUS_ENDED -> {
                        dismissedInviteUpdatedAt[chatRoomId] = invite.updatedAtMillis
                        endCall(
                            persistLog = current.status == CallStatus.CONNECTED,
                            notifySignaling = false
                        )
                    }
                }
            }
    }

    fun selectContact(contact: User?) {
        _selectedContact.value = contact
    }

    fun clearActionError() {
        _actionError.value = null
    }

    fun reportActionError(message: String) {
        _actionError.value = message
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
        incomingCallsJob?.cancel()
        callSignalingJob?.cancel()
        cancelPresenceObservers()
        cancelDeliveryReceiptObservers()
        acknowledgedDeliveredIds.clear()
        acknowledgedReadIds.clear()
        initializedDeliveryRooms.clear()
        notifiedMessageIds.clear()
        JustChatManager.stopPresenceTracking()
        searchJob?.cancel()
        _userSearchState.value = UserSearchUiState.Idle
        CallRingtonePlayer.stopRingtone()
        onCancelCallAlert()
        endCall(persistLog = false, notifySignaling = false)
        _selectedContact.value = null
    }

    private fun ensurePresenceObservers(contacts: List<User>) {
        if (_isSignedOut.value) return
        val activeEmails = contacts
            .map { it.gmail.trim().lowercase() }
            .filter { it.contains("@") }
            .toSet()

        // Detach observers for removed contacts
        val staleEmails = presenceJobsByEmail.keys - activeEmails
        staleEmails.forEach { email ->
            presenceJobsByEmail.remove(email)?.cancel()
        }

        // Attach live RTDB presence observers for newly added contacts
        activeEmails.forEach { email ->
            if (presenceJobsByEmail.containsKey(email)) return@forEach
            presenceJobsByEmail[email] = viewModelScope.launch {
                val directoryUser = userManager.searchUserByEmail(email).getOrNull() ?: return@launch
                UserPresenceManager.observeUserOnlineStatus(directoryUser.uid)
                    .catch { error ->
                        if (error is CancellationException) throw error
                        Log.w(TAG, "Live presence stream failed for $email", error)
                    }
                    .collect { isOnline ->
                        _livePresenceByEmail.update { current ->
                            if (current[email] == isOnline) current else current + (email to isOnline)
                        }
                    }
            }
        }
    }

    private fun cancelPresenceObservers() {
        presenceJobsByEmail.values.forEach { it.cancel() }
        presenceJobsByEmail.clear()
        _livePresenceByEmail.value = emptyMap()
    }

    private fun ensureDeliveryReceiptObservers(contacts: List<User>) {
        if (_isSignedOut.value) return
        val contactsByEmail = contacts
            .associateBy { it.gmail.trim().lowercase() }
            .filterKeys { it.contains("@") }

        val staleEmails = deliveryJobsByEmail.keys - contactsByEmail.keys
        staleEmails.forEach { email ->
            deliveryJobsByEmail.remove(email)?.cancel()
        }

        contactsByEmail.forEach { (email, contact) ->
            if (deliveryJobsByEmail.containsKey(email)) return@forEach
            deliveryJobsByEmail[email] = viewModelScope.launch {
                repository.observeSharedMessages(
                    currentUserId = currentUserId,
                    currentUserEmail = currentUserEmail(),
                    contactEmail = email
                )
                    .catch { error ->
                        if (error is CancellationException) throw error
                        Log.w(TAG, "Delivery receipt stream failed for $email", error)
                    }
                    .collect { messages ->
                        val isChatOpen = _selectedContact.value?.gmail
                            ?.trim()
                            ?.equals(email, ignoreCase = true) == true
                        if (email !in initializedDeliveryRooms) {
                            initializedDeliveryRooms.add(email)
                            messages.forEach { msg ->
                                if (msg.id.isNotBlank()) notifiedMessageIds.add(msg.id)
                            }
                        } else if (!isChatOpen) {
                            val newIncoming = messages.filter { msg ->
                                msg.senderId != "current_user" &&
                                    msg.status != MessageStatus.READ &&
                                    msg.id.isNotBlank() &&
                                    msg.id !in notifiedMessageIds
                            }
                            if (newIncoming.isNotEmpty()) {
                                newIncoming.forEach { notifiedMessageIds.add(it.id) }
                                onIncomingMessageAlert(contact, newIncoming.last())
                            }
                        }
                        if (!isChatOpen) {
                            markIncomingMessagesAsDelivered(contact, messages)
                        }
                    }
            }
        }
    }

    private fun cancelDeliveryReceiptObservers() {
        deliveryJobsByEmail.values.forEach { it.cancel() }
        deliveryJobsByEmail.clear()
    }

    private fun User.withLivePresence(presenceMap: Map<String, Boolean>): User {
        val liveOnline = presenceMap[gmail.trim().lowercase()] ?: return this
        return copy(
            isOnline = liveOnline,
            lastSeenText = if (liveOnline) "Active now" else "Offline"
        )
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

    fun sendMessage(contact: User, text: String) {
        viewModelScope.launch {
            repository.sendSharedMessage(
                currentUserId = currentUserId,
                senderEmail = currentUserEmail(),
                receiverEmail = contact.gmail,
                text = text
            ).onFailure { error ->
                _actionError.value = error.localizedMessage ?: "Unable to send message."
            }
        }
    }

    fun toggleReaction(contact: User, message: ChatMessage, emoji: String) {
        viewModelScope.launch {
            repository.toggleSharedReaction(
                currentUserId = currentUserId,
                currentUserEmail = currentUserEmail(),
                contactEmail = contact.gmail,
                message = message,
                emoji = emoji
            ).onFailure { error ->
                _actionError.value = error.localizedMessage ?: "Unable to update reaction."
            }
        }
    }

    fun markIncomingMessagesAsDelivered(contact: User, messages: List<ChatMessage>) {
        if (_isSignedOut.value) return
        val undeliveredIds = messages
            .filter {
                it.senderId != "current_user" &&
                    it.status == MessageStatus.SENT &&
                    it.id.isNotBlank() &&
                    it.id !in acknowledgedDeliveredIds &&
                    it.id !in acknowledgedReadIds
            }
            .map { it.id }
        if (undeliveredIds.isEmpty()) return

        acknowledgedDeliveredIds.addAll(undeliveredIds)
        viewModelScope.launch {
            repository.markSharedMessagesStatus(
                currentUserId = currentUserId,
                currentUserEmail = currentUserEmail(),
                contactEmail = contact.gmail,
                messageIds = undeliveredIds,
                targetStatus = MessageStatus.DELIVERED
            ).onFailure { error ->
                acknowledgedDeliveredIds.removeAll(undeliveredIds.toSet())
                Log.w(TAG, "Unable to mark messages as delivered: ${error.message}")
            }
        }
    }

    private fun markIncomingMessagesAsRead(contact: User, messages: List<ChatMessage>) {
        if (_isSignedOut.value) return
        val unreadIds = messages
            .filter {
                it.senderId != "current_user" &&
                    it.status != MessageStatus.READ &&
                    it.id.isNotBlank() &&
                    it.id !in acknowledgedReadIds
            }
            .map { it.id }
        if (unreadIds.isEmpty()) return

        acknowledgedReadIds.addAll(unreadIds)
        viewModelScope.launch {
            repository.markSharedMessagesStatus(
                currentUserId = currentUserId,
                currentUserEmail = currentUserEmail(),
                contactEmail = contact.gmail,
                messageIds = unreadIds,
                targetStatus = MessageStatus.READ
            ).onFailure { error ->
                acknowledgedReadIds.removeAll(unreadIds.toSet())
                Log.w(TAG, "Unable to mark messages as read: ${error.message}")
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

    // --- Active Call State Management (Firestore CallSignaling + Agora RTC) ---

    private fun currentUserEmail(): String {
        return (profileState.value as? UiState.Success)?.data?.email
            ?.takeIf { it.isNotBlank() }
            ?: fallbackUser.gmail
    }

    private fun currentUserDisplayName(): String {
        return (profileState.value as? UiState.Success)?.data?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: fallbackUser.name
    }

    fun startCall(contact: User, type: CallType) {
        CallRingtonePlayer.stopRingtone()
        callTickerJob?.cancel()
        callSignalingJob?.cancel()
        agoraController.leaveCall()

        val lowDataMode = (profileState.value as? UiState.Success)?.data?.lowDataCallsEnabled ?: true
        val myEmail = currentUserEmail()
        val myName = currentUserDisplayName()
        val chatRoomId = buildChatRoomId(myEmail, contact.gmail)
        val channelName = CallSignaling.buildCallChannelName(chatRoomId, myEmail, contact.gmail)
        val agoraConfigured = agoraController.isConfigured
        val callId = UUID.randomUUID().toString()

        activeCallRoomId = chatRoomId
        activeInviteUpdatedAtMs = 0L

        val initialCall = ActiveCallState(
            callId = callId,
            contact = contact,
            callType = type,
            status = CallStatus.DIALING,
            isMuted = false,
            isCameraOn = type == CallType.VIDEO,
            isFrontCamera = true,
            isSpeakerOn = true,
            isLowDataMode = lowDataMode,
            durationSeconds = 0,
            latencyMs = 0,
            dataRateKbps = 0,
            totalDataKb = 0L,
            channelName = channelName,
            remoteUid = null,
            isAgoraConfigured = agoraConfigured,
            connectionNote = if (agoraConfigured) {
                "Ringing ${contact.name}…"
            } else {
                MISSING_AGORA_NOTE
            }
        )
        _activeCall.value = initialCall

        // Publish real-time Firestore call invite at /calls/{chatRoomId} and observe callee state
        callSignalingJob = viewModelScope.launch {
            val inviteResult = callSignaling.startCall(
                currentUserId = currentUserId,
                caller = myEmail,
                callerName = myName,
                callee = contact.gmail,
                callType = type
            )
            inviteResult.onSuccess { invite ->
                if (_activeCall.value?.callId == callId) {
                    activeInviteUpdatedAtMs = invite.updatedAtMillis
                    collectCallSignaling(
                        callId = callId,
                        chatRoomId = invite.chatRoomId,
                        contactName = contact.name,
                        isCaller = true
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "Call signaling failed: ${error.message}")
                _activeCall.update { call ->
                    if (call == null || call.callId != callId) return@update call
                    call.copy(connectionNote = error.localizedMessage ?: "Unable to signal callee.")
                }
            }
        }

        if (agoraConfigured) {
            agoraController.joinCall(
                channelName = channelName,
                isSpeakerDefault = true,
                isLowDataMode = lowDataMode,
                listener = createAgoraListener(callId = callId, contactName = contact.name),
                withVideo = type == CallType.VIDEO
            )
        }
    }

    fun receiveIncomingCall(contact: User, type: CallType) {
        callTickerJob?.cancel()
        callSignalingJob?.cancel()
        agoraController.leaveCall()
        activeCallRoomId = null
        activeInviteUpdatedAtMs = 0L

        val lowDataMode = (profileState.value as? UiState.Success)?.data?.lowDataCallsEnabled ?: true
        val myEmail = currentUserEmail()
        val chatRoomId = buildChatRoomId(myEmail, contact.gmail)
        val channelName = CallSignaling.buildCallChannelName(chatRoomId, myEmail, contact.gmail)
        val agoraConfigured = agoraController.isConfigured

        val incomingState = ActiveCallState(
            callId = UUID.randomUUID().toString(),
            contact = contact,
            callType = type,
            status = CallStatus.INCOMING_RINGING,
            isMuted = false,
            isCameraOn = type == CallType.VIDEO,
            isFrontCamera = true,
            isSpeakerOn = true,
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
        _activeCall.value = incomingState
        onIncomingCallAlert(incomingState)
    }

    fun answerCall() {
        val current = _activeCall.value ?: return
        if (current.status != CallStatus.INCOMING_RINGING) return
        CallRingtonePlayer.stopRingtone()
        onCancelCallAlert()

        val roomId = activeCallRoomId
        if (roomId != null) {
            viewModelScope.launch {
                callSignaling.acceptCall(currentUserId, roomId).onFailure { error ->
                    Log.w(TAG, "Failed to update call invite to accepted: ${error.message}")
                }
            }
        }

        val resolvedChannel = current.channelName.ifBlank {
            val myEmail = currentUserEmail()
            val fallbackRoomId = buildChatRoomId(myEmail, current.contact.gmail)
            CallSignaling.buildCallChannelName(fallbackRoomId, myEmail, current.contact.gmail)
        }

        if (agoraController.isConfigured) {
            _activeCall.update {
                it?.copy(
                    status = CallStatus.DIALING,
                    isAgoraConfigured = true,
                    connectionNote = "Connecting via Agora RTC…"
                )
            }
            agoraController.joinCall(
                channelName = resolvedChannel,
                isSpeakerDefault = current.isSpeakerOn,
                isLowDataMode = current.isLowDataMode,
                listener = createAgoraListener(callId = current.callId, contactName = current.contact.name),
                withVideo = current.callType == CallType.VIDEO
            )
        } else {
            _activeCall.update {
                it?.copy(
                    status = CallStatus.CONNECTED,
                    isAgoraConfigured = false,
                    connectionNote = MISSING_AGORA_NOTE
                )
            }
            startCallDurationTicker(current.callId)
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
            agoraController.setCameraEnabled(nextCameraOn)
        }
    }

    fun toggleCameraFacing() {
        _activeCall.update { it?.copy(isFrontCamera = !it.isFrontCamera) }
        if (_activeCall.value?.isAgoraConfigured == true) {
            agoraController.switchCamera()
        }
    }

    // Agora video surfaces (wired from CallScreen via MainActivity)
    fun attachLocalVideo(view: android.view.SurfaceView) = agoraController.attachLocalVideo(view)
    fun detachLocalVideo() = agoraController.detachLocalVideo()
    fun attachRemoteVideo(uid: Int, view: android.view.SurfaceView) =
        agoraController.attachRemoteVideo(uid, view)
    fun detachRemoteVideo(uid: Int) = agoraController.detachRemoteVideo(uid)

    fun toggleSpeaker() {
        val current = _activeCall.value ?: return
        val nextSpeaker = !current.isSpeakerOn
        _activeCall.update { it?.copy(isSpeakerOn = nextSpeaker) }
        if (current.isAgoraConfigured) {
            agoraController.setSpeakerEnabled(nextSpeaker)
        }
    }

    fun setMinimized(minimized: Boolean) {
        _activeCall.update { it?.copy(isMinimized = minimized) }
    }

    fun endCall(persistLog: Boolean = true, notifySignaling: Boolean = true) {
        CallRingtonePlayer.stopRingtone()
        onCancelCallAlert()
        callTickerJob?.cancel()
        callSignalingJob?.cancel()
        agoraController.leaveCall()

        val finishedCall = _activeCall.value
        val roomId = activeCallRoomId
        val inviteTimestamp = activeInviteUpdatedAtMs

        activeCallRoomId = null
        activeInviteUpdatedAtMs = 0L
        _activeCall.value = null

        if (roomId != null && inviteTimestamp > 0L) {
            dismissedInviteUpdatedAt[roomId] = inviteTimestamp
        }

        if (notifySignaling && roomId != null && !_isSignedOut.value) {
            viewModelScope.launch {
                callSignaling.endCall(currentUserId, roomId).onFailure { error ->
                    Log.w(TAG, "Failed to signal call end: ${error.message}")
                }
            }
        }

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

            override fun onNetworkQualityChanged(txQuality: Int, rxQuality: Int) {
                val stability = NetworkStability.fromAgoraQuality(txQuality, rxQuality)
                _activeCall.update { call ->
                    if (call == null || call.callId != callId) return@update call
                    call.copy(
                        networkStability = stability,
                        networkQualityRating = stability.ratingLabel
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
        CallRingtonePlayer.stopRingtone()
        onCancelCallAlert()
        callTickerJob?.cancel()
        callSignalingJob?.cancel()
        incomingCallsJob?.cancel()
        cancelPresenceObservers()
        JustChatManager.stopPresenceTracking()
        agoraController.destroy()
    }

    private companion object {
        const val TAG = "JustChatViewModel"
        const val STOP_TIMEOUT_MS = 5000L
        const val MISSING_AGORA_NOTE =
            "Set AGORA_APP_ID in AI Studio Secrets to connect live Agora voice & video calls."
    }
}
