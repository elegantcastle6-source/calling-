package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.example.ui.components.EncryptionDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.CallType
import com.example.model.ChatMessage
import com.example.model.MediaType
import com.example.model.MessageStatus
import com.example.model.User
import com.example.ui.components.AudioWaveform
import com.example.ui.components.UserAvatar
import com.example.ui.theme.CallGreen
import com.example.ui.theme.CallRed
import com.example.ui.theme.ChatBubbleReceiverDark
import com.example.ui.theme.ChatBubbleReceiverLight
import com.example.ui.theme.ChatBubbleSender
import com.example.ui.theme.ChatBubbleSenderOn
import com.example.ui.theme.DataSaverCyan
import com.example.ui.theme.IndigoPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatDetailScreen(
    contact: User,
    messages: List<ChatMessage>,
    onBack: () -> Unit,
    onStartCall: (CallType) -> Unit,
    onSendMessage: (text: String, mediaType: MediaType, mediaUri: String?, duration: Int) -> Unit,
    onToggleReaction: (messageId: String, emoji: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var selectedMediaMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var isRecordingVoice by remember { mutableStateOf(false) }
    var voiceRecordDuration by remember { mutableIntStateOf(0) }
    var showEncryptionDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Android Zero-Permission Photo Picker for Pictures
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onSendMessage("Photo shared", MediaType.IMAGE, uri.toString(), 0)
            showAttachmentMenu = false
        }
    }

    // Android Zero-Permission Video Picker
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onSendMessage("Video clip shared", MediaType.VIDEO, uri.toString(), 15)
            showAttachmentMenu = false
        }
    }

    // Auto-scroll to latest message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Auto-scroll when keyboard opens
    val isImeVisible = WindowInsets.isImeVisible
    LaunchedEffect(isImeVisible) {
        if (isImeVisible && messages.isNotEmpty()) {
            delay(150)
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Voice recording timer
    LaunchedEffect(isRecordingVoice) {
        if (isRecordingVoice) {
            voiceRecordDuration = 0
            while (isRecordingVoice) {
                delay(1000)
                voiceRecordDuration += 1
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        UserAvatar(
                            name = contact.name,
                            avatarColorHex = contact.avatarColorHex,
                            size = 40.dp,
                            isOnline = contact.isOnline
                        )
                        Column {
                            Text(
                                text = contact.name,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = if (contact.isOnline) "Active now • Gmail verified" else contact.lastSeenText,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (contact.isOnline) CallGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // E2EE Safety Number button
                    IconButton(
                        onClick = { showEncryptionDialog = true },
                        modifier = Modifier.testTag("verify_e2ee_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "E2EE Verified",
                            tint = CallGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Audio Call button
                    IconButton(
                        onClick = { onStartCall(CallType.AUDIO) },
                        modifier = Modifier.testTag("start_audio_call_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Call,
                            contentDescription = "Audio Call",
                            tint = IndigoPrimary
                        )
                    }

                    // Video Call button
                    IconButton(
                        onClick = { onStartCall(CallType.VIDEO) },
                        modifier = Modifier.testTag("start_video_call_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Videocam,
                            contentDescription = "Video Call",
                            tint = IndigoPrimary
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
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
            ) {
                Column {
                    // Attachment drawer
                    AnimatedVisibility(visible = showAttachmentMenu) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            AttachmentOption(
                                icon = Icons.Rounded.Image,
                                label = "Share Photo",
                                color = IndigoPrimary,
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            )

                            AttachmentOption(
                                icon = Icons.Rounded.VideoLibrary,
                                label = "Share Video",
                                color = DataSaverCyan,
                                onClick = {
                                    videoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    )
                                }
                            )

                            AttachmentOption(
                                icon = Icons.Rounded.Mic,
                                label = "Voice Note",
                                color = CallGreen,
                                onClick = {
                                    isRecordingVoice = true
                                    showAttachmentMenu = false
                                }
                            )
                        }
                    }

                    // Bottom Input Row
                    if (isRecordingVoice) {
                        // Voice note recording bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(CallRed)
                                )
                                Text(
                                    text = "Recording Voice (${voiceRecordDuration}s)",
                                    fontWeight = FontWeight.SemiBold,
                                    color = CallRed
                                )
                                AudioWaveform(
                                    barCount = 10,
                                    maxHeight = 20.dp,
                                    color = CallRed,
                                    isAnimating = true
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(onClick = { isRecordingVoice = false }) {
                                    Icon(Icons.Rounded.Stop, contentDescription = "Cancel", tint = Color.Gray)
                                }
                                FloatingActionButton(
                                    onClick = {
                                        onSendMessage(
                                            "Voice note (${voiceRecordDuration}s)",
                                            MediaType.VOICE,
                                            null,
                                            voiceRecordDuration.coerceAtLeast(1)
                                        )
                                        isRecordingVoice = false
                                    },
                                    containerColor = IndigoPrimary,
                                    contentColor = Color.White,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send Voice")
                                }
                            }
                        }
                    } else {
                        // Standard text & attachment input
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { showAttachmentMenu = !showAttachmentMenu }) {
                                Icon(
                                    imageVector = Icons.Rounded.AttachFile,
                                    contentDescription = "Attach media",
                                    tint = IndigoPrimary
                                )
                            }

                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                placeholder = { Text("Message with zero lag...") },
                                shape = RoundedCornerShape(24.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp)
                                    .testTag("chat_input_field")
                            )

                            if (inputText.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        onSendMessage(inputText.trim(), MediaType.NONE, null, 0)
                                        inputText = ""
                                    },
                                    modifier = Modifier.testTag("send_message_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.Send,
                                        contentDescription = "Send",
                                        tint = IndigoPrimary
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = { isRecordingVoice = true },
                                    modifier = Modifier.testTag("record_voice_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Mic,
                                        contentDescription = "Voice note",
                                        tint = IndigoPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Gmail verification badge banner at top
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.clickable { showEncryptionDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = CallGreen,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Connected with ${contact.gmail} • End-to-end encrypted (Tap to verify)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(messages, key = { it.id }) { message ->
                val isMe = message.senderId == "current_user"
                MessageBubbleItem(
                    message = message,
                    isMe = isMe,
                    onMediaClick = { selectedMediaMessage = message },
                    onReactionClick = { emoji -> onToggleReaction(message.id, emoji) }
                )
            }
        }

        // Fullscreen media dialog
        selectedMediaMessage?.let { mediaMsg ->
            MediaViewerDialog(
                message = mediaMsg,
                onDismiss = { selectedMediaMessage = null }
            )
        }

        // E2EE Encryption Verification Dialog
        if (showEncryptionDialog) {
            EncryptionDialog(
                contact = contact,
                onDismiss = { showEncryptionDialog = false }
            )
        }
    }
}

@Composable
private fun MessageBubbleItem(
    message: ChatMessage,
    isMe: Boolean,
    onMediaClick: () -> Unit,
    onReactionClick: (String) -> Unit
) {
    val bubbleColor = if (isMe) ChatBubbleSender else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isMe) ChatBubbleSenderOn else MaterialTheme.colorScheme.onSurfaceVariant
    val alignment = if (isMe) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isMe) 16.dp else 4.dp,
                bottomEnd = if (isMe) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                // Media Content Rendering
                when (message.mediaType) {
                    MediaType.IMAGE -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.2f))
                                .clickable { onMediaClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (message.mediaUri?.startsWith("sample") == true || message.mediaUri == null) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Rounded.Image,
                                        contentDescription = null,
                                        tint = DataSaverCyan,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        text = "Tap to view image",
                                        color = Color.White,
                                        fontSize = 12.sp
                                    )
                                }
                            } else {
                                AsyncImage(
                                    model = message.mediaUri,
                                    contentDescription = "Photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    MediaType.VIDEO -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.3f))
                                .clickable { onMediaClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Play video",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x88000000))
                                    .padding(8.dp)
                            )
                            Text(
                                text = "Video • ${message.mediaDurationSeconds}s",
                                color = Color.White,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    MediaType.VOICE -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Play voice note",
                                tint = if (isMe) Color.White else IndigoPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                            AudioWaveform(
                                barCount = 14,
                                maxHeight = 22.dp,
                                color = if (isMe) Color.White else IndigoPrimary,
                                isAnimating = false
                            )
                            Text(
                                text = "0:${message.mediaDurationSeconds.toString().padStart(2, '0')}",
                                color = textColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    MediaType.NONE -> {}
                }

                if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        color = textColor,
                        fontSize = 15.sp,
                        lineHeight = 20.sp
                    )
                }

                // Timestamp & read status
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = formatTimestamp(message.timestamp),
                        fontSize = 10.sp,
                        color = textColor.copy(alpha = 0.7f)
                    )

                    if (isMe) {
                        when (message.status) {
                            MessageStatus.SENDING -> Icon(
                                Icons.Rounded.Check,
                                contentDescription = "Sending",
                                tint = textColor.copy(alpha = 0.5f),
                                modifier = Modifier.size(12.dp)
                            )
                            MessageStatus.SENT -> Icon(
                                Icons.Rounded.Check,
                                contentDescription = "Sent",
                                tint = textColor.copy(alpha = 0.8f),
                                modifier = Modifier.size(12.dp)
                            )
                            MessageStatus.DELIVERED, MessageStatus.READ -> Icon(
                                Icons.Rounded.DoneAll,
                                contentDescription = "Delivered/Read",
                                tint = if (message.status == MessageStatus.READ) DataSaverCyan else textColor.copy(alpha = 0.8f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        // Reaction chips
        if (message.reactions.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                message.reactions.forEach { (emoji, count) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onReactionClick(emoji) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$emoji $count",
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatTimestamp(timeMillis: Long): String {
    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    return formatter.format(Date(timeMillis))
}
