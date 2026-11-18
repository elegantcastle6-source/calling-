package com.example

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.data.FirestoreChatRepository
import com.example.data.UserManager
import com.example.data.authStateFlow
import com.example.data.signOutWithGoogle
import com.example.model.CallType
import com.example.model.User
import com.example.ui.components.MinimizedCallBar
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CallScreen
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.MainDashboardScreen
import com.example.ui.screens.SearchUserScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.JustChatViewModel
import com.example.viewmodel.UiState
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                JustChatApp()
            }
        }
    }
}

@Composable
fun JustChatApp() {
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
    onSessionEnded: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

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
                    agoraController = AgoraCallController(app.applicationContext),
                    currentUserId = firebaseUser.uid,
                    initialUser = firebaseUser
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

    val profileDoc = (profileState as? UiState.Success)?.data
    val currentUser = profileDoc?.toDomainUser() ?: viewModel.fallbackUser
    val lowDataCalls = profileDoc?.lowDataCallsEnabled ?: true
    val lowLatency = profileDoc?.lowLatencyEnabled ?: true
    val totalSavedMb = (profileDoc?.totalDataSavedMb ?: 0.0).toFloat()

    // Runtime Permission Request for Camera and Microphone
    var pendingCallAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        pendingCallAction?.invoke()
        pendingCallAction = null
    }

    val requestCallPermissionsAndStart: (User, CallType) -> Unit = { contact, type ->
        pendingCallAction = {
            viewModel.startCall(contact, type)
        }
        permissionsLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )
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
                            onAnswerCall = { viewModel.answerCall() },
                            onEndCall = { viewModel.endCall() },
                            onToggleSpeaker = { viewModel.toggleSpeaker() },
                            onAttachLocalVideo = { surfaceView -> viewModel.attachLocalVideo(surfaceView) },
                            onDetachLocalVideo = { viewModel.detachLocalVideo() },
                            onAttachRemoteVideo = { uid, surfaceView ->
                                viewModel.attachRemoteVideo(uid, surfaceView)
                            },
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
                                viewModel.sendMessage(contact.id, text)
                            },
                            onToggleReaction = { messageId, emoji ->
                                val targetMsg = chatMessages.firstOrNull { it.id == messageId }
                                if (targetMsg != null) {
                                    viewModel.toggleReaction(contact.id, targetMsg, emoji)
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
