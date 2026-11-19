package com.justchat.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.CallInvite
import com.example.ui.components.MessageTickIcon as SharedMessageTickIcon
import com.example.ui.theme.CallGreen
import com.example.ui.theme.CallRed
import com.example.ui.theme.DarkOnBackground
import com.example.ui.theme.DarkSurfaceCard
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.justchat.app.sync.CallSignal
import com.justchat.app.sync.ChatMessage
import com.justchat.app.sync.JustChatManager
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ActiveCallState(
    val channelName: String,
    val peerEmail: String,
    val isOutgoing: Boolean
)

/**
 * Self-contained real-time chat + Agora voice call container backed by [JustChatManager]
 * and securely injected [BuildConfig.AGORA_APP_ID] / [BuildConfig.AGORA_TEMP_TOKEN].
 */
@Composable
fun ChatAndCallContainer(
    friendName: String,
    friendEmail: String,
    friendUid: String = "",
    agoraToken: String? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentUserEmail = remember {
        Firebase.auth.currentUser?.email?.trim()?.lowercase().orEmpty()
    }

    LaunchedEffect(context) {
        JustChatManager.initialize(context.applicationContext)
    }

    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var messageInput by remember { mutableStateOf("") }
    var isOnline by remember { mutableStateOf(false) }
    var incomingSignal by remember { mutableStateOf<CallSignal?>(null) }
    var activeCall by remember { mutableStateOf<ActiveCallState?>(null) }
    var sawActiveSignalForCurrentCall by remember { mutableStateOf(false) }
    var pendingMicAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val acknowledgedDeliveredIds = remember(friendEmail) { mutableSetOf<String>() }
    val acknowledgedReadIds = remember(friendEmail) { mutableSetOf<String>() }

    // Real-time messages from shared room /chats/{roomId}/messages
    DisposableEffect(friendEmail) {
        val listener = JustChatManager.observeMessages(friendEmail) { updated ->
            messages = updated
        }
        onDispose { listener?.remove() }
    }

    // Mark incoming peer messages as delivered (when in call / not viewing chat)
    // or directly as read (blue double tick) when the receiver is viewing the chat screen.
    LaunchedEffect(messages, isOnline, friendEmail, currentUserEmail, activeCall) {
        if (messages.isEmpty() || friendEmail.isBlank() || currentUserEmail.isBlank()) {
            return@LaunchedEffect
        }
        if (activeCall != null) {
            val undelivered = messages.filter { msg ->
                !msg.sender.equals(currentUserEmail, ignoreCase = true) &&
                    msg.status.equals(ChatMessage.STATUS_SENT, ignoreCase = true) &&
                    msg.id.isNotBlank() &&
                    msg.id !in acknowledgedDeliveredIds &&
                    msg.id !in acknowledgedReadIds
            }
            if (undelivered.isNotEmpty()) {
                val ids = undelivered.map { it.id }
                acknowledgedDeliveredIds.addAll(ids)
                JustChatManager.markMessagesAsDelivered(friendEmail, undelivered) {
                    acknowledgedDeliveredIds.removeAll(ids.toSet())
                }
            }
        } else {
            val unread = messages.filter { msg ->
                !msg.sender.equals(currentUserEmail, ignoreCase = true) &&
                    !msg.status.equals(ChatMessage.STATUS_READ, ignoreCase = true) &&
                    msg.id.isNotBlank() &&
                    msg.id !in acknowledgedReadIds
            }
            if (unread.isNotEmpty()) {
                val ids = unread.map { it.id }
                acknowledgedReadIds.addAll(ids)
                JustChatManager.markMessagesAsRead(friendEmail, unread) {
                    acknowledgedReadIds.removeAll(ids.toSet())
                }
            }
        }
    }

    // Real-time peer online/offline status from RTDB presence/{friendUid}/status
    DisposableEffect(friendUid) {
        val presenceListener = if (friendUid.isNotBlank()) {
            JustChatManager.observeUserPresence(friendUid) { online ->
                isOnline = online
            }
        } else {
            null
        }
        onDispose {
            if (friendUid.isNotBlank() && presenceListener != null) {
                JustChatManager.removePresenceObserver(friendUid, presenceListener)
            }
        }
    }

    // Real-time call signaling from /calls/{roomId}
    DisposableEffect(friendEmail, currentUserEmail) {
        val callListener = JustChatManager.observeCall(friendEmail) { signal ->
            if (signal == null) {
                incomingSignal = null
                return@observeCall
            }
            val isFromPeer = !signal.caller.equals(currentUserEmail, ignoreCase = true)
            when (signal.status) {
                CallInvite.STATUS_CALLING -> {
                    if (activeCall != null) {
                        sawActiveSignalForCurrentCall = true
                    }
                    if (isFromPeer && activeCall == null) {
                        incomingSignal = signal
                    }
                }

                CallInvite.STATUS_ACCEPTED -> {
                    incomingSignal = null
                    if (activeCall != null) {
                        sawActiveSignalForCurrentCall = true
                    }
                }

                CallInvite.STATUS_ENDED -> {
                    incomingSignal = null
                    if (activeCall != null && (sawActiveSignalForCurrentCall || !activeCall!!.isOutgoing)) {
                        activeCall = null
                        sawActiveSignalForCurrentCall = false
                    }
                }
            }
        }
        onDispose { callListener?.remove() }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingMicAction?.invoke()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.call_mic_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
        pendingMicAction = null
    }

    val ensureMicPermissionAndRun: (() -> Unit) -> Unit = { action ->
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            action()
        } else {
            pendingMicAction = action
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val startOutgoingCall = {
        val channel = JustChatManager.initiateCall(friendEmail)
        if (channel != null) {
            sawActiveSignalForCurrentCall = false
            activeCall = ActiveCallState(
                channelName = channel,
                peerEmail = friendEmail,
                isOutgoing = true
            )
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.please_sign_in_first),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val currentActiveCall = activeCall
    if (currentActiveCall != null) {
        AgoraVoiceCallScreen(
            peerName = friendName,
            channelName = currentActiveCall.channelName,
            agoraToken = agoraToken,
            onEndCall = {
                JustChatManager.endCall(friendEmail)
                activeCall = null
                sawActiveSignalForCurrentCall = false
            },
            modifier = modifier
        )
    } else {
        CombinedChatScreen(
            friendName = friendName,
            isOnline = isOnline,
            messages = messages,
            currentUserEmail = currentUserEmail,
            messageInput = messageInput,
            onMessageInputChange = { messageInput = it },
            onSendClick = {
                val trimmed = messageInput.trim()
                if (trimmed.isNotEmpty()) {
                    JustChatManager.sendMessage(friendEmail, trimmed)
                    messageInput = ""
                }
            },
            onCallClick = { ensureMicPermissionAndRun(startOutgoingCall) },
            incomingSignal = incomingSignal,
            onAcceptIncoming = { signal ->
                ensureMicPermissionAndRun {
                    incomingSignal = null
                    sawActiveSignalForCurrentCall = true
                    JustChatManager.acceptCall(friendEmail)
                    activeCall = ActiveCallState(
                        channelName = signal.channelName,
                        peerEmail = signal.caller,
                        isOutgoing = false
                    )
                }
            },
            onDeclineIncoming = {
                incomingSignal = null
                JustChatManager.endCall(friendEmail)
            },
            onBack = onBack,
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CombinedChatScreen(
    friendName: String,
    isOnline: Boolean,
    messages: List<ChatMessage>,
    currentUserEmail: String,
    messageInput: String,
    onMessageInputChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onCallClick: () -> Unit,
    incomingSignal: CallSignal?,
    onAcceptIncoming: (CallSignal) -> Unit,
    onDeclineIncoming: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val isImeVisible = WindowInsets.isImeVisible

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(isImeVisible) {
        if (isImeVisible && messages.isNotEmpty()) {
            delay(150)
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("combined_chat_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(contentAlignment = Alignment.BottomEnd) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = friendName.trim().firstOrNull()?.uppercase() ?: "?",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isOnline) {
                                            CallGreen
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        }
                                    )
                            )
                        }
                        Column {
                            Text(
                                text = friendName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(
                                    if (isOnline) R.string.status_active_now else R.string.status_offline
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOnline) {
                                    CallGreen
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onCallClick,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("combined_start_call_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Call,
                            contentDescription = stringResource(R.string.action_voice_call),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = onMessageInputChange,
                        placeholder = { Text(stringResource(R.string.hint_type_message)) },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("combined_message_input")
                    )
                    FilledIconButton(
                        onClick = onSendClick,
                        enabled = messageInput.isNotBlank(),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("combined_send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.action_send_message)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            AnimatedVisibility(
                visible = incomingSignal != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                incomingSignal?.let { signal ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = DarkSurfaceCard
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("combined_incoming_call_banner")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Call,
                                    contentDescription = null,
                                    tint = CallGreen
                                )
                                Column {
                                    Text(
                                        text = stringResource(
                                            R.string.incoming_call_from,
                                            signal.caller.ifBlank { friendName }
                                        ),
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = DarkOnBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = stringResource(R.string.incoming_voice_call),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = DarkOnBackground.copy(alpha = 0.75f)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = onDeclineIncoming,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CallRed,
                                        contentColor = DarkOnBackground
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("combined_decline_call_button")
                                ) {
                                    Text(stringResource(R.string.action_decline))
                                }
                                Button(
                                    onClick = { onAcceptIncoming(signal) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CallGreen,
                                        contentColor = DarkOnBackground
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("combined_accept_call_button")
                                ) {
                                    Text(stringResource(R.string.action_answer))
                                }
                            }
                        }
                    }
                }
            }

            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ChatBubbleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = stringResource(R.string.empty_chat_prompt, friendName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id.ifBlank { "${it.timestamp}_${it.sender}" } }) { msg ->
                        val isMe = msg.sender.equals(currentUserEmail, ignoreCase = true)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                        ) {
                            Card(
                                shape = RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomStart = if (isMe) 16.dp else 4.dp,
                                    bottomEnd = if (isMe) 4.dp else 16.dp
                                ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isMe) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ),
                                modifier = Modifier.widthIn(max = 280.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = msg.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isMe) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    val metaColor = if (isMe) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.End)
                                            .padding(top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = formatMessageTime(msg.timestamp),
                                            fontSize = 10.sp,
                                            color = metaColor.copy(alpha = 0.7f)
                                        )
                                        if (isMe) {
                                            MessageTickIcon(status = msg.status)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageTickIcon(
    status: String,
    modifier: Modifier = Modifier
) {
    SharedMessageTickIcon(
        status = status,
        modifier = modifier
    )
}

@Composable
fun CombinedCallScreen(
    peerName: String,
    channelName: String,
    agoraToken: String?,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    AgoraVoiceCallScreen(
        peerName = peerName,
        channelName = channelName,
        agoraToken = agoraToken,
        onEndCall = onEndCall,
        modifier = modifier
    )
}

private fun formatMessageTime(timeMillis: Long): String {
    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    return formatter.format(Date(timeMillis))
}
