package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.StopScreenShare
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.CallManager
import com.example.model.ActiveCallState
import com.example.model.CallStatus
import com.example.model.CallType
import com.example.model.MediaType
import com.example.ui.components.AudioWaveform
import com.example.ui.components.CameraPreview
import com.example.ui.components.DataSaverBadge
import com.example.ui.components.EncryptionDialog
import com.example.ui.components.UserAvatar
import com.example.ui.theme.CallGreen
import com.example.ui.theme.CallRed
import com.example.ui.theme.DataSaverCyan
import com.example.ui.theme.IndigoPrimary

@Composable
fun CallScreen(
    callState: ActiveCallState,
    callManager: CallManager,
    modifier: Modifier = Modifier
) {
    var showEncryptionDialog by remember { mutableStateOf(false) }

    // In-call media picker launcher for photos and videos
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val isVideo = uri.toString().contains("video", ignoreCase = true)
            callManager.shareMediaInCall(
                uri = uri.toString(),
                type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
                title = if (isVideo) "Live Video Share" else "Live Photo Share"
            )
        }
    }

    BackHandler {
        callManager.endCall()
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFF0A0F1D)
    ) {
        if (callState.callType == CallType.VIDEO) {
            VideoCallContent(
                callState = callState,
                callManager = callManager,
                onOpenEncryption = { showEncryptionDialog = true },
                onLaunchMediaShare = {
                    mediaPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                }
            )
        } else {
            AudioCallContent(
                callState = callState,
                callManager = callManager,
                onOpenEncryption = { showEncryptionDialog = true },
                onLaunchMediaShare = {
                    mediaPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                }
            )
        }

        if (showEncryptionDialog) {
            EncryptionDialog(
                contact = callState.contact,
                onDismiss = { showEncryptionDialog = false }
            )
        }
    }
}

@Composable
private fun VideoCallContent(
    callState: ActiveCallState,
    callManager: CallManager,
    onOpenEncryption: () -> Unit,
    onLaunchMediaShare: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Remote Video Feed (or Shared Media Feed)
        if (callState.sharedMediaUri != null) {
            // Live In-Call Shared Media Stream
            SharedMediaCallView(
                callState = callState,
                onStopSharing = { callManager.stopSharingMedia() }
            )
        } else {
            // Standard Remote Video Feed
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFF0A0F1D))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    UserAvatar(
                        name = callState.contact.name,
                        avatarColorHex = callState.contact.avatarColorHex,
                        size = 100.dp,
                        showOnlineIndicator = false
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = callState.contact.name,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (callState.status == CallStatus.CONNECTED) {
                            "Remote Video Active • 30 FPS"
                        } else "Connecting encrypted video stream...",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )

                    if (callState.status == CallStatus.CONNECTED) {
                        Spacer(modifier = Modifier.height(12.dp))
                        AudioWaveform(
                            barCount = 12,
                            maxHeight = 24.dp,
                            color = CallGreen,
                            isAnimating = true
                        )
                    }
                }
            }
        }

        // Top Overlay: Status, Time, E2EE Shield, and Data Saver HUD
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 44.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { callManager.setMinimized(true) },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x66000000))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Minimize Call",
                            tint = Color.White
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = callState.contact.name,
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            // E2EE Lock Badge
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0x66000000))
                                    .clickable { onOpenEncryption() }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(Icons.Rounded.Lock, contentDescription = "E2EE", tint = CallGreen, modifier = Modifier.size(11.dp))
                                Text("E2EE", color = CallGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Text(
                            text = when (callState.status) {
                                CallStatus.DIALING -> "Dialing (Zero-Lag P2P)..."
                                CallStatus.INCOMING_RINGING -> "Incoming Video Call"
                                CallStatus.CONNECTED -> formatTimer(callState.durationSeconds)
                                CallStatus.ENDED -> "Call ended"
                            },
                            color = if (callState.status == CallStatus.CONNECTED) CallGreen else Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Data Saver Toggle Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (callState.isLowDataMode) Color(0xFF0369A1) else Color(0xFF334155))
                        .clickable { callManager.toggleLowDataMode() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DataUsage,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (callState.isLowDataMode) "Data Saver ON" else "HD Mode",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Real-Time Telemetry & Smooth Optimization Bar
            if (callState.status == CallStatus.CONNECTED) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DataSaverBadge(
                        isLowDataMode = callState.isLowDataMode,
                        latencyMs = callState.latencyMs,
                        dataRateKbps = callState.dataRateKbps,
                        onToggle = { callManager.toggleLowDataMode() }
                    )

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xCC0F172A))
                            .clickable { callManager.optimizeNetworkRoute() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Rounded.Bolt, contentDescription = "Turbo", tint = Color(0xFFFBBF24), modifier = Modifier.size(14.dp))
                        Text("${callState.fps} FPS • 0% Loss", color = Color(0xFFFBBF24), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Picture-in-Picture Local Camera Feed
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 130.dp, end = 16.dp)
                .size(width = 110.dp, height = 150.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(2.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .background(Color.Black)
        ) {
            CameraPreview(
                isCameraOn = callState.isCameraOn,
                isFrontCamera = callState.isFrontCamera,
                modifier = Modifier.fillMaxSize()
            )

            // Switch camera button inside PiP
            IconButton(
                onClick = { callManager.toggleCameraFacing() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0x88000000))
            ) {
                Icon(
                    imageVector = Icons.Rounded.Cameraswitch,
                    contentDescription = "Switch Camera",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Bottom In-Call Controls
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 36.dp, start = 16.dp, end = 16.dp)
        ) {
            if (callState.status == CallStatus.INCOMING_RINGING) {
                IncomingCallControls(
                    onAnswer = { callManager.answerCall() },
                    onDecline = { callManager.endCall() }
                )
            } else {
                VideoControlsBar(
                    callState = callState,
                    callManager = callManager,
                    onLaunchMediaShare = onLaunchMediaShare
                )
            }
        }
    }
}

@Composable
private fun AudioCallContent(
    callState: ActiveCallState,
    callManager: CallManager,
    onOpenEncryption: () -> Unit,
    onLaunchMediaShare: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "pulseRing")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 50.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { callManager.setMinimized(true) },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Minimize Call",
                        tint = Color.White
                    )
                }

                // E2EE Lock Banner
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x33000000))
                        .clickable { onOpenEncryption() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = CallGreen, modifier = Modifier.size(13.dp))
                    Text(
                        text = "E2EE Encrypted (SRTP)",
                        color = CallGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Turbo Sync
                IconButton(
                    onClick = { callManager.optimizeNetworkRoute() },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = "Turbo No-Lag Sync",
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Text(
                text = callState.contact.name,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = callState.contact.gmail,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when (callState.status) {
                    CallStatus.DIALING -> "Dialing (Zero-Lag Opus)..."
                    CallStatus.INCOMING_RINGING -> "Incoming Audio Call"
                    CallStatus.CONNECTED -> formatTimer(callState.durationSeconds)
                    CallStatus.ENDED -> "Call ended"
                },
                color = if (callState.status == CallStatus.CONNECTED) CallGreen else Color.White.copy(alpha = 0.8f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )

            if (callState.status == CallStatus.CONNECTED) {
                DataSaverBadge(
                    isLowDataMode = callState.isLowDataMode,
                    latencyMs = callState.latencyMs,
                    dataRateKbps = callState.dataRateKbps,
                    onToggle = { callManager.toggleLowDataMode() }
                )
            }
        }

        // Center: Shared Media or Pulsing Avatar
        if (callState.sharedMediaUri != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                SharedMediaCallView(
                    callState = callState,
                    onStopSharing = { callManager.stopSharingMedia() }
                )
            }
        } else {
            Box(
                modifier = Modifier.size(240.dp),
                contentAlignment = Alignment.Center
            ) {
                if (callState.status == CallStatus.CONNECTED) {
                    Box(
                        modifier = Modifier
                            .size(160.dp * pulseScale)
                            .clip(CircleShape)
                            .background(Color(0xFF6366F1).copy(alpha = 0.15f))
                    )
                }

                UserAvatar(
                    name = callState.contact.name,
                    avatarColorHex = callState.contact.avatarColorHex,
                    size = 120.dp,
                    showOnlineIndicator = false
                )
            }
        }

        // Waveform Visualizer
        if (callState.status == CallStatus.CONNECTED && callState.sharedMediaUri == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AudioWaveform(
                    barCount = 20,
                    maxHeight = 36.dp,
                    color = DataSaverCyan,
                    isAnimating = !callState.isMuted
                )
                Text(
                    text = if (callState.isMuted) "Microphone Muted" else "Opus Low-Bandwidth Voice Codec",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }

        // Bottom Controls
        if (callState.status == CallStatus.INCOMING_RINGING) {
            IncomingCallControls(
                onAnswer = { callManager.answerCall() },
                onDecline = { callManager.endCall() }
            )
        } else {
            AudioControlsBar(
                callState = callState,
                callManager = callManager,
                onLaunchMediaShare = onLaunchMediaShare
            )
        }
    }
}

@Composable
private fun SharedMediaCallView(
    callState: ActiveCallState,
    onStopSharing: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (callState.sharedMediaType == MediaType.IMAGE) {
            AsyncImage(
                model = callState.sharedMediaUri,
                contentDescription = "Shared Live Image",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Playing live video",
                        tint = Color.White,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(0x88000000))
                            .padding(16.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Live Shared Video Stream",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Top Shared Media Label and Close Button
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 110.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC0F172A))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Rounded.ScreenShare, contentDescription = null, tint = DataSaverCyan, modifier = Modifier.size(16.dp))
                Text(
                    text = "${callState.sharedMediaTitle ?: "Media"} (Live E2EE)",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            IconButton(
                onClick = onStopSharing,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xAA000000))
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Stop Sharing", tint = Color.White)
            }
        }
    }
}

@Composable
private fun VideoControlsBar(
    callState: ActiveCallState,
    callManager: CallManager,
    onLaunchMediaShare: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xCC0F172A))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mute Toggle
        CallControlButton(
            icon = if (callState.isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
            isActive = !callState.isMuted,
            contentDescription = "Mute",
            onClick = { callManager.toggleMute() }
        )

        // Camera Toggle
        CallControlButton(
            icon = if (callState.isCameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
            isActive = callState.isCameraOn,
            contentDescription = "Camera",
            onClick = { callManager.toggleCamera() }
        )

        // In-Call Media Share Toggle
        CallControlButton(
            icon = if (callState.sharedMediaUri != null) Icons.Rounded.StopScreenShare else Icons.Rounded.ScreenShare,
            isActive = callState.sharedMediaUri != null,
            contentDescription = "Share Media Live",
            onClick = {
                if (callState.sharedMediaUri != null) {
                    callManager.stopSharingMedia()
                } else {
                    onLaunchMediaShare()
                }
            }
        )

        // End Call Button
        FloatingActionButton(
            onClick = { callManager.endCall() },
            containerColor = CallRed,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier
                .size(54.dp)
                .testTag("end_call_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.CallEnd,
                contentDescription = "End Call",
                modifier = Modifier.size(26.dp)
            )
        }

        // Speaker Toggle
        CallControlButton(
            icon = Icons.Rounded.VolumeUp,
            isActive = callState.isSpeakerOn,
            contentDescription = "Speaker",
            onClick = { callManager.toggleSpeaker() }
        )

        // AI Noise Cancellation
        CallControlButton(
            icon = Icons.Rounded.GraphicEq,
            isActive = callState.isNoiseSuppressionEnabled,
            contentDescription = "AI Noise Cancellation",
            onClick = { callManager.toggleNoiseSuppression() }
        )

        // Switch Camera
        CallControlButton(
            icon = Icons.Rounded.Cameraswitch,
            isActive = false,
            contentDescription = "Flip Camera",
            onClick = { callManager.toggleCameraFacing() }
        )
    }
}

@Composable
private fun AudioControlsBar(
    callState: ActiveCallState,
    callManager: CallManager,
    onLaunchMediaShare: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xCC0F172A))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CallControlButton(
            icon = if (callState.isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
            isActive = !callState.isMuted,
            contentDescription = "Mute",
            onClick = { callManager.toggleMute() }
        )

        CallControlButton(
            icon = Icons.Rounded.VolumeUp,
            isActive = callState.isSpeakerOn,
            contentDescription = "Speaker",
            onClick = { callManager.toggleSpeaker() }
        )

        // AI Noise Cancellation
        CallControlButton(
            icon = Icons.Rounded.GraphicEq,
            isActive = callState.isNoiseSuppressionEnabled,
            contentDescription = "AI Noise Cancellation",
            onClick = { callManager.toggleNoiseSuppression() }
        )

        // Media Share during audio call
        CallControlButton(
            icon = if (callState.sharedMediaUri != null) Icons.Rounded.StopScreenShare else Icons.Rounded.ScreenShare,
            isActive = callState.sharedMediaUri != null,
            contentDescription = "Share Media",
            onClick = {
                if (callState.sharedMediaUri != null) {
                    callManager.stopSharingMedia()
                } else {
                    onLaunchMediaShare()
                }
            }
        )

        FloatingActionButton(
            onClick = { callManager.endCall() },
            containerColor = CallRed,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier
                .size(54.dp)
                .testTag("end_audio_call_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.CallEnd,
                contentDescription = "End Call",
                modifier = Modifier.size(26.dp)
            )
        }

        CallControlButton(
            icon = Icons.Rounded.DataUsage,
            isActive = callState.isLowDataMode,
            contentDescription = "Data Saver",
            onClick = { callManager.toggleLowDataMode() }
        )
    }
}

@Composable
private fun IncomingCallControls(
    onAnswer: () -> Unit,
    onDecline: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FloatingActionButton(
                onClick = onDecline,
                containerColor = CallRed,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(60.dp)
            ) {
                Icon(Icons.Rounded.CallEnd, contentDescription = "Decline", modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Decline", color = Color.White, fontSize = 13.sp)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FloatingActionButton(
                onClick = onAnswer,
                containerColor = CallGreen,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(60.dp)
            ) {
                Icon(Icons.Rounded.Phone, contentDescription = "Answer", modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Answer", color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (isActive) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f))
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) Color.White else Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(22.dp)
        )
    }
}

private fun formatTimer(totalSeconds: Int): String {
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "%02d:%02d".format(mins, secs)
}
