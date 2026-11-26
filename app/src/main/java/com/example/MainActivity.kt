package com.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.AgoraCallController
import com.example.data.CallSignaling
import com.example.data.FirestoreChatRepository
import com.example.data.UserManager
import com.example.data.authStateFlow
import com.example.data.signOutWithGoogle
import com.example.model.CallStatus
import com.example.model.CallType
import com.example.model.User
import com.example.ui.components.MinimizedCallBar
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CallScreen
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.MainDashboardScreen
import com.example.ui.screens.SearchUserScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.utils.CallRingtonePlayer
import com.example.utils.CallWakeHelper
import com.example.utils.JustChatNotifications
import com.example.viewmodel.JustChatViewModel
import com.example.viewmodel.UiState
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.justchat.app.sync.JustChatManager

class MainActivity : ComponentActivity() {

    private var screenOffReceiverRegistered = false
    private val pendingNotificationChatEmail = mutableStateOf<String?>(null)

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                // Power button / screen off: silence incoming ringtone only; keep active call alive
                CallRingtonePlayer.onPowerButtonOrScreenOff()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        JustChatNotifications.createChannels(applicationContext)
        pendingNotificationChatEmail.value =
            intent?.getStringExtra(JustChatNotifications.EXTRA_CONTACT_EMAIL)

        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenOffReceiverRegistered = true

        // Bind JustChatManager to the named Firestore DB and start online/offline presence tracking
        JustChatManager.initialize(applicationContext)
        JustChatManager.startPresenceTracking()

        setContent {
            MyApplicationTheme {
                JustChatApp(
                    pendingNotificationChatEmail = pendingNotificationChatEmail.value,
                    onConsumeNotificationChatEmail = { pendingNotificationChatEmail.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val contactEmail = intent.getStringExtra(JustChatNotifications.EXTRA_CONTACT_EMAIL)
        if (!contactEmail.isNullOrBlank()) {
            pendingNotificationChatEmail.value = contactEmail
        }
    }

    override fun onStart() {
        super.onStart()
        JustChatManager.startPresenceTracking()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (
            event.action == KeyEvent.ACTION_DOWN &&
            CallRingtonePlayer.isCurrentlyRinging() &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            CallRingtonePlayer.onPowerButtonOrScreenOff()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (screenOffReceiverRegistered) {
            try {
                unregisterReceiver(screenOffReceiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered
            }
            screenOffReceiverRegistered = false
        }
        if (!isChangingConfigurations) {
            CallRingtonePlayer.stopRingtone()
        }
        super.onDestroy()
    }
}

@Composable
fun JustChatApp(
    pendingNotificationChatEmail: String? = null,
    onConsumeNotificationChatEmail: () -> Unit = {}
) {
    val auth = remember { Firebase.auth }
    val authFlow = remember(auth) { auth.authStateFlow() }
    val currentUser by authFlow.collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val signedInUser = currentUser
    var sessionGeneration by remember { mutableIntStateOf(0) }

    Surface(modifier = Modifier.fillMaxSize()) {
        if (signedInUser == null) {
            // Unauthenticated state: render AuthScreen only; no Firestore ViewModel or listeners instantiated
            AuthScreen(onAuthSuccess = {})
        } else {
            val sessionKey = "${signedInUser.uid}_$sessionGeneration"
            // Key composition and ViewModel by UID + session generation so sign-out/re-login resets cleanly
            key(sessionKey) {
                AuthenticatedJustChatContent(
                    firebaseUser = signedInUser,
                    sessionKey = sessionKey,
                    pendingNotificationChatEmail = pendingNotificationChatEmail,
                    onConsumeNotificationChatEmail = onConsumeNotificationChatEmail,
                    onSessionEnded = { sessionGeneration += 1 }
                )
            }
        }
    }
}

@Composable
private fun AuthenticatedJustChatContent(
    firebaseUser: FirebaseUser,
    sessionKey: String,
    pendingNotificationChatEmail: String?,
    onConsumeNotificationChatEmail: () -> Unit,
    onSessionEnded: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled by system notification settings */ }

    LaunchedEffect(sessionKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val viewModel: JustChatViewModel = viewModel(
        key = sessionKey,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                JustChatViewModel(
                    repository = FirestoreChatRepository(db),
                    userManager = UserManager(db),
                    callSignaling = CallSignaling(db),
                    agoraController = AgoraCallController(app.applicationContext),
                    currentUserId = firebaseUser.uid,
                    initialUser = firebaseUser,
                    onIncomingMessageAlert = { contact, message ->
                        JustChatNotifications.showIncomingMessageNotification(
                            context = app.applicationContext,
                            senderName = contact.name,
                            senderEmail = contact.gmail,
                            messageText = message.text
                        )
                    },
                    onIncomingCallAlert = { call ->
                        CallWakeHelper.wakeForIncomingCall(app.applicationContext) {
                            JustChatNotifications.showIncomingCallNotification(
                                context = app.applicationContext,
                                callerName = call.contact.name,
                                callerEmail = call.contact.gmail,
                                isVideo = call.callType == CallType.VIDEO
                            )
                        }
                    },
                    onCancelCallAlert = {
                        JustChatNotifications.cancelIncomingCallNotification(app.applicationContext)
                    }
                )
            }
        }
    )

    val profileState by viewModel.profileState.collectAsStateWithLifecycle()
    val contactsState by viewModel.contactsState.collectAsStateWithLifecycle()
    val callLogsState by viewModel.callLogsState.collectAsStateWithLifecycle()
    val selectedContact by viewModel.selectedContact.collectAsStateWithLifecycle()
    val activeMessagesState by viewModel.activeMessagesState.collectAsStateWithLifecycle()
    val activeCall by viewModel.activeCall.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val userSearchState by viewModel.userSearchState.collectAsStateWithLifecycle()

    LaunchedEffect(pendingNotificationChatEmail, contactsState) {
        val targetEmail = pendingNotificationChatEmail?.trim().orEmpty()
        if (targetEmail.isEmpty()) return@LaunchedEffect
        val contacts = (contactsState as? UiState.Success)?.data ?: return@LaunchedEffect
        val matched = contacts.firstOrNull { it.gmail.equals(targetEmail, ignoreCase = true) }
        if (matched != null) {
            viewModel.selectContact(matched)
        }
        onConsumeNotificationChatEmail()
    }

    // Ring once per incoming call; silencing via power/volume buttons will not restart ringing on wake.
    val incomingRingingCallId = activeCall
        ?.takeIf { it.status == CallStatus.INCOMING_RINGING }
        ?.callId
    DisposableEffect(incomingRingingCallId) {
        if (incomingRingingCallId != null) {
            CallWakeHelper.wakeForIncomingCall(context) {
                CallRingtonePlayer.startDefaultRingtone(context)
            }
        } else {
            CallRingtonePlayer.stopRingtone()
        }
        onDispose {
            if (incomingRingingCallId != null) {
                CallRingtonePlayer.stopRingtone()
            }
        }
    }

    val profileDoc = (profileState as? UiState.Success)?.data
    val currentUser = profileDoc?.toDomainUser() ?: viewModel.fallbackUser
    val lowDataCalls = profileDoc?.lowDataCallsEnabled ?: true
    val lowLatency = profileDoc?.lowLatencyEnabled ?: true
    val totalSavedMb = (profileDoc?.totalDataSavedMb ?: 0.0).toFloat()

    // Runtime Permission Check & Request for Agora Voice / Video Calling
    var pendingCallAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        if (micGranted) {
            pendingCallAction?.invoke()
        } else {
            val message = context.getString(R.string.call_mic_permission_denied)
            viewModel.reportActionError(message)
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        pendingCallAction = null
    }

    val ensurePermissionsAndRunCall: (CallType, () -> Unit) -> Unit = { type, onGranted ->
        if (checkCallPermissions(context, type)) {
            onGranted()
        } else {
            pendingCallAction = onGranted
            permissionsLauncher.launch(requiredCallPermissions(type))
        }
    }

    val requestCallPermissionsAndStart: (User, CallType) -> Unit = { contact, type ->
        ensurePermissionsAndRunCall(type) {
            viewModel.startCall(contact, type)
        }
    }

    var showSearchUserScreen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = when {
                activeCall != null && !activeCall!!.isMinimized -> "call"
                selectedContact != null -> "chat"
                showSearchUserScreen -> "search"
                else -> "home"
            },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screenTransition"
        ) { targetScreen ->
            when (targetScreen) {
                "call" -> {
                    activeCall?.let { callState ->
                        CallScreen(
                            callState = callState,
                            onToggleMute = { viewModel.toggleMute() },
                            onToggleCamera = { viewModel.toggleCamera() },
                            onToggleCameraFacing = { viewModel.toggleCameraFacing() },
                            onSetMinimized = { viewModel.setMinimized(it) },
                            onAnswerCall = {
                                ensurePermissionsAndRunCall(callState.callType) {
                                    viewModel.answerCall()
                                }
                            },
                            onEndCall = { viewModel.endCall() },
                            onToggleSpeaker = { viewModel.toggleSpeaker() },
                            onAttachLocalVideo = { viewModel.attachLocalVideo(it) },
                            onDetachLocalVideo = { viewModel.detachLocalVideo() },
                            onAttachRemoteVideo = { uid, view -> viewModel.attachRemoteVideo(uid, view) },
                            onDetachRemoteVideo = { uid -> viewModel.detachRemoteVideo(uid) }
                        )
                    }
                }

                "chat" -> {
                    selectedContact?.let { contact ->
                        val chatMessages = (activeMessagesState as? UiState.Success)?.data.orEmpty()
                        val isMessagesLoading = activeMessagesState is UiState.Loading
                        val messagesError = (activeMessagesState as? UiState.Error)?.message
                        BackHandler {
                            viewModel.selectContact(null)
                        }
                        ChatDetailScreen(
                            contact = contact,
                            messages = chatMessages,
                            isLoading = isMessagesLoading,
                            errorMessage = messagesError,
                            actionError = actionError,
                            onDismissActionError = { viewModel.clearActionError() },
                            onBack = { viewModel.selectContact(null) },
                            onStartCall = { type ->
                                requestCallPermissionsAndStart(contact, type)
                            },
                            onSendMessage = { text, _, _, _ ->
                                viewModel.sendMessage(contact, text)
                            },
                            onToggleReaction = { messageId, emoji ->
                                val targetMsg = chatMessages.firstOrNull { it.id == messageId }
                                if (targetMsg != null) {
                                    viewModel.toggleReaction(contact, targetMsg, emoji)
                                }
                            }
                        )
                    }
                }

                "search" -> {
                    SearchUserScreen(
                        searchState = userSearchState,
                        onSearchByEmail = { email -> viewModel.searchUserByEmail(email) },
                        onClearSearch = { viewModel.clearUserSearch() },
                        onStartChat = { foundUser ->
                            viewModel.clearUserSearch()
                            showSearchUserScreen = false
                            viewModel.addContactByGmail(foundUser.email, foundUser.displayName) { contact ->
                                viewModel.selectContact(contact)
                            }
                        },
                        onStartCall = { foundUser, callType ->
                            viewModel.clearUserSearch()
                            showSearchUserScreen = false
                            viewModel.addContactByGmail(foundUser.email, foundUser.displayName) { contact ->
                                requestCallPermissionsAndStart(contact, callType)
                            }
                        },
                        onBack = {
                            viewModel.clearUserSearch()
                            showSearchUserScreen = false
                        }
                    )
                }

                else -> {
                    MainDashboardScreen(
                        currentUser = currentUser,
                        contactsState = contactsState,
                        callLogsState = callLogsState,
                        lowDataCalls = lowDataCalls,
                        lowLatency = lowLatency,
                        totalSavedMb = totalSavedMb,
                        actionError = actionError,
                        onDismissActionError = { viewModel.clearActionError() },
                        onOpenChat = { contact -> viewModel.selectContact(contact) },
                        onStartCall = { contact, type ->
                            requestCallPermissionsAndStart(contact, type)
                        },
                        onSimulateIncomingCall = { contact, type ->
                            viewModel.receiveIncomingCall(contact, type)
                        },
                        onAddContact = { gmail, name, onAdded ->
                            viewModel.addContactByGmail(gmail, name, onAdded)
                        },
                        onToggleLowDataCalls = { viewModel.setLowDataCallsEnabled(it) },
                        onToggleLowLatency = { viewModel.setLowLatencyEnabled(it) },
                        userSearchState = userSearchState,
                        onOpenSearchUser = {
                            viewModel.clearUserSearch()
                            showSearchUserScreen = true
                        },
                        onSearchUserByEmail = { email -> viewModel.searchUserByEmail(email) },
                        onClearUserSearch = { viewModel.clearUserSearch() },
                        onSignOut = {
                            signOutWithGoogle(
                                credentialManager = credentialManager,
                                scope = coroutineScope,
                                onBeforeSignOut = {
                                    showSearchUserScreen = false
                                    viewModel.prepareForSignOut()
                                },
                                onSignOutComplete = {
                                    onSessionEnded()
                                }
                            )
                        }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = activeCall != null && activeCall!!.isMinimized,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 84.dp)
        ) {
            activeCall?.let { callState ->
                MinimizedCallBar(
                    callState = callState,
                    onToggleMute = { viewModel.toggleMute() },
                    onEndCall = { viewModel.endCall() },
                    onExpand = { viewModel.setMinimized(false) }
                )
            }
        }
    }
}

private fun requiredCallPermissions(type: CallType): Array<String> {
    return when (type) {
        CallType.AUDIO -> arrayOf(Manifest.permission.RECORD_AUDIO)
        CallType.VIDEO -> arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
    }
}

private fun checkCallPermissions(context: Context, type: CallType): Boolean {
    return requiredCallPermissions(type).all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

