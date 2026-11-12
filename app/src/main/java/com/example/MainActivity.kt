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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.CallManager
import com.example.data.ChatRepository
import com.example.model.CallType
import com.example.model.User
import com.example.ui.components.MinimizedCallBar
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CallScreen
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                GlideChatApp()
            }
        }
    }
}

@Composable
fun GlideChatApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { ChatRepository(context) }
    val callManager = remember { CallManager(repository, coroutineScope) }

    val currentUser by repository.currentUser.collectAsState()
    val messagesMap by repository.messages.collectAsState()
    val activeCall by callManager.activeCall.collectAsState()
    val lowDataCalls by repository.lowDataCallsEnabled.collectAsState()

    var isSignedIn by remember { mutableStateOf(repository.isUserLoggedIn) }
    var activeChatContact by remember { mutableStateOf<User?>(null) }

    // Runtime Permission Request for Camera and Microphone
    var pendingCallAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Run pending call action regardless of permissions; camera preview handles fallback
        pendingCallAction?.invoke()
        pendingCallAction = null
    }

    val requestCallPermissionsAndStart: (User, CallType) -> Unit = { contact, type ->
        pendingCallAction = {
            callManager.startCall(contact, type, lowDataCalls)
        }
        permissionsLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = when {
                    activeCall != null && !activeCall!!.isMinimized -> "call"
                    !isSignedIn -> "auth"
                    activeChatContact != null -> "chat"
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
                                callManager = callManager
                            )
                        }
                    }

                    "auth" -> {
                        AuthScreen(
                            initialUser = currentUser,
                            onLoginSuccess = { name, email, status ->
                                repository.updateCurrentUser(name, email, status)
                                isSignedIn = true
                            }
                        )
                    }

                    "chat" -> {
                        activeChatContact?.let { contact ->
                            val chatMessages = messagesMap[contact.id] ?: emptyList()
                            BackHandler {
                                activeChatContact = null
                            }
                            ChatDetailScreen(
                                contact = contact,
                                messages = chatMessages,
                                onBack = { activeChatContact = null },
                                onStartCall = { type ->
                                    requestCallPermissionsAndStart(contact, type)
                                },
                                onSendMessage = { text, mediaType, mediaUri, duration ->
                                    repository.sendMessage(
                                        contactId = contact.id,
                                        text = text,
                                        mediaType = mediaType,
                                        mediaUri = mediaUri,
                                        mediaDurationSeconds = duration
                                    )
                                },
                                onToggleReaction = { messageId, emoji ->
                                    repository.toggleReaction(contact.id, messageId, emoji)
                                }
                            )
                        }
                    }

                    else -> {
                        HomeScreen(
                            repository = repository,
                            callManager = callManager,
                            onOpenChat = { contact -> activeChatContact = contact },
                            onStartCall = { contact, type ->
                                requestCallPermissionsAndStart(contact, type)
                            },
                            onSignOut = {
                                repository.signOut()
                                isSignedIn = false
                                activeChatContact = null
                            }
                        )
                    }
                }
            }

            // Minimized Floating Call Bar when user navigates chats or settings during a call
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
                        callManager = callManager,
                        onExpand = { callManager.setMinimized(false) }
                    )
                }
            }
        }
    }
}
