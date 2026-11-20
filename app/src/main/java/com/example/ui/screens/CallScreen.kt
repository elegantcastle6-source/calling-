package com.example.ui.screens

import android.view.SurfaceView
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.data.AgoraCallController
import com.example.data.CallManager
import com.example.model.ActiveCallState
import com.example.model.CallStatus
import com.example.model.CallType
import com.example.ui.components.CameraPreview
import com.example.ui.components.EncryptionDialog
import com.example.ui.components.NetworkSignalIndicator
import com.example.ui.components.UserAvatar
import com.example.ui.theme.CallAmber
import com.example.ui.theme.CallGreen
import com.example.ui.theme.CallRed
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.IndigoPrimary

@Composable
fun CallScreen(
    callState: ActiveCallState,
    callManager: CallManager,
    modifier: Modifier = Modifier
) {
    CallScreen(
        callState = callState,
        onToggleMute = { callManager.toggleMute() },
        onToggleCamera = { callManager.toggleCamera() },
        onToggleCameraFacing = { callManager.toggleCameraFacing() },
        onSetMinimized = { callManager.setMinimized(it) },
        onAnswerCall = { callManager.answerCall() },
        onEndCall = { callManager.endCall() },
        modifier = modifier
    )
}

@Composable
fun CallScreen(
    callState: ActiveCallState,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onToggleCameraFacing: () -> Unit,
    onSetMinimized: (Boolean) -> Unit,
    onAnswerCall: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleSpeaker: (() -> Unit)? = null,
    onAttachLocalVideo: ((SurfaceView) -> Unit)? = null,
    onDetachLocalVideo: (() -> Unit)? = null,
    onAttachRemoteVideo: ((Int, SurfaceView) -> Unit)? = null,
    onDetachRemoteVideo: ((Int) -> Unit)? = null
) {
    var showEncryptionDialog by remember { mutableStateOf(false) }
    val remoteUid = callState.remoteUid
    val isShowingRemoteVideo =
        AgoraCallController.SUPPORTS_VIDEO &&
            callState.isAgoraConfigured &&
            remoteUid != null &&
            callState.callType == CallType.VIDEO &&
            onAttachRemoteVideo != null

    BackHandler {
        onEndCall()
    }

    val statusText = when (callState.status) {
        CallStatus.DIALING -> "Calling..."
        CallStatus.INCOMING_RINGING -> if (callState.callType == CallType.VIDEO) {
            "Incoming video call..."
        } else {
            "Incoming audio call..."
        }
        CallStatus.CONNECTED -> buildString {
            append(formatCallDuration(callState.durationSeconds))
            if (callState.isMuted) {
                append(" • Muted")
            } else if (callState.isLowDataMode) {
                append(" • Low Data")
            }
        }
        CallStatus.ENDED -> "Call ended"
    }

    val pulseTransition = rememberInfiniteTransition(label = "callPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = DarkBackground
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(DarkSurface, DarkSurfaceVariant, DarkBackground)
                    )
                )
        ) {
            // Full-screen Remote Agora Video when peer joins a video call
            if (isShowingRemoteVideo) {
                val activeRemoteUid = remoteUid!!
                val attachRemote = onAttachRemoteVideo!!
                key(activeRemoteUid) {
                    DisposableEffect(activeRemoteUid) {
                        onDispose {
                            onDetachRemoteVideo?.invoke(activeRemoteUid)
                        }
                    }
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).also { surfaceView ->
                                attachRemote(activeRemoteUid, surfaceView)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("agora_remote_video_view")
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.Black.copy(alpha = 0.55f),
                                0.30f to Color.Transparent,
                                0.72f to Color.Transparent,
                                1.0f to Color.Black.copy(alpha = 0.65f)
                            )
                        )
                )
            }

            // Top bar: Minimize, E2EE badge & Speaker toggle
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onSetMinimized(true) },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .testTag("minimize_call_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Minimize Call",
                        tint = Color.White
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable { showEncryptionDialog = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("call_e2ee_badge"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = "End-to-End Encrypted",
                        tint = CallGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "End-to-End Encrypted",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }

                if (onToggleSpeaker != null) {
                    IconButton(
                        onClick = onToggleSpeaker,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (callState.isSpeakerOn) {
                                    IndigoPrimary.copy(alpha = 0.75f)
                                } else {
                                    Color.Black.copy(alpha = 0.35f)
                                }
                            )
                            .testTag("call_speaker_button")
                    ) {
                        Icon(
                            imageVector = if (callState.isSpeakerOn) {
                                Icons.AutoMirrored.Rounded.VolumeUp
                            } else {
                                Icons.AutoMirrored.Rounded.VolumeOff
                            },
                            contentDescription = stringResource(
                                if (callState.isSpeakerOn) {
                                    R.string.call_speaker_on_desc
                                } else {
                                    R.string.call_speaker_off_desc
                                }
                            ),
                            tint = Color.White
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(48.dp))
                }
            }

            // Caller Info at top
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 76.dp, start = 24.dp, end = 24.dp)
            ) {
                Text(
                    text = callState.contact.name,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (callState.status == CallStatus.CONNECTED) {
                        CallGreen
                    } else {
                        Color.LightGray
                    },
                    textAlign = TextAlign.Center
                )

                if (callState.status == CallStatus.CONNECTED || callState.status == CallStatus.DIALING) {
                    Spacer(modifier = Modifier.height(10.dp))
                    NetworkSignalIndicator(
                        stability = callState.networkStability,
                        latencyMs = callState.latencyMs
                    )
                }

                val note = callState.connectionNote
                if (!note.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("call_connection_note"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (callState.isAgoraConfigured) {
                                Icons.Rounded.GraphicEq
                            } else {
                                Icons.Rounded.Info
                            },
                            contentDescription = null,
                            tint = if (callState.isAgoraConfigured) CallGreen else CallAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.92f),
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }

            // Center Avatar with subtle pulse when remote video is not active
            if (!isShowingRemoteVideo) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (callState.status == CallStatus.CONNECTED || callState.status == CallStatus.DIALING) {
                        Box(
                            modifier = Modifier
                                .size(140.dp * pulseScale)
                                .clip(CircleShape)
                                .background(IndigoPrimary.copy(alpha = 0.18f))
                        )
                    }
                    UserAvatar(
                        name = callState.contact.name,
                        avatarColorHex = callState.contact.avatarColorHex,
                        size = 112.dp,
                        showOnlineIndicator = false
                    )
                }
            }

            // Self Video PiP when camera is enabled
            if (callState.isCameraOn) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(bottom = 148.dp, end = 20.dp)
                        .size(width = 110.dp, height = 152.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .background(Color.Black)
                ) {
                    if (AgoraCallController.SUPPORTS_VIDEO &&
                        callState.isAgoraConfigured &&
                        onAttachLocalVideo != null
                    ) {
                        DisposableEffect(callState.callId) {
                            onDispose {
                                onDetachLocalVideo?.invoke()
                            }
                        }
                        AndroidView(
                            factory = { ctx ->
                                SurfaceView(ctx).apply {
                                    setZOrderMediaOverlay(true)
                                }.also { surfaceView ->
                                    onAttachLocalVideo(surfaceView)
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("agora_local_video_view")
                        )
                    } else {
                        CameraPreview(
                            isCameraOn = true,
                            isFrontCamera = callState.isFrontCamera,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    IconButton(
                        onClick = onToggleCameraFacing,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .testTag("switch_camera_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Cameraswitch,
                            contentDescription = "Switch Camera",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Call Control Buttons at bottom
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 44.dp)
            ) {
                if (callState.status == CallStatus.INCOMING_RINGING) {
                    IncomingCallActionRow(
                        onDecline = onEndCall,
                        onAnswer = onAnswerCall
                    )
                } else {
                    CallControlRow(
                        isMuted = callState.isMuted,
                        isCameraOn = callState.isCameraOn,
                        onToggleMute = onToggleMute,
                        onEndCall = onEndCall,
                        onToggleCamera = onToggleCamera
                    )
                }
            }
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
fun CallScreen(
    contactName: String,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isMuted by remember { mutableStateOf(false) }
    var isVideoOn by remember { mutableStateOf(true) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(DarkSurface, DarkSurfaceVariant, DarkBackground)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp, start = 24.dp, end = 24.dp)
        ) {
            Text(
                text = contactName,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isMuted) "Calling... • Muted" else "Calling...",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray
            )
        }

        UserAvatar(
            name = contactName,
            avatarColorHex = 0xFF6366F1,
            size = 112.dp,
            showOnlineIndicator = false
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 48.dp)
        ) {
            CallControlRow(
                isMuted = isMuted,
                isCameraOn = isVideoOn,
                onToggleMute = { isMuted = !isMuted },
                onEndCall = onEndCall,
                onToggleCamera = { isVideoOn = !isVideoOn }
            )
        }
    }
}

@Composable
private fun CallControlRow(
    isMuted: Boolean,
    isCameraOn: Boolean,
    onToggleMute: () -> Unit,
    onEndCall: () -> Unit,
    onToggleCamera: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mute Button
        IconButton(
            onClick = onToggleMute,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (isMuted) Color.White else Color.Black.copy(alpha = 0.5f),
                    shape = CircleShape
                )
                .testTag("call_mute_button")
        ) {
            Icon(
                imageVector = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                tint = if (isMuted) DarkBackground else Color.White
            )
        }

        // End Call Button (Red)
        IconButton(
            onClick = onEndCall,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(CallRed, shape = CircleShape)
                .testTag("end_call_button")
        ) {
            Icon(
                imageVector = Icons.Filled.CallEnd,
                contentDescription = "End Call",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        // Video Toggle Button
        IconButton(
            onClick = onToggleCamera,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (isCameraOn) Color.Black.copy(alpha = 0.5f) else Color.White,
                    shape = CircleShape
                )
                .testTag("call_video_toggle_button")
        ) {
            Icon(
                imageVector = if (isCameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                contentDescription = if (isCameraOn) "Turn Off Video" else "Turn On Video",
                tint = if (isCameraOn) Color.White else DarkBackground
            )
        }
    }
}

@Composable
private fun IncomingCallActionRow(
    onDecline: () -> Unit,
    onAnswer: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = onDecline,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(CallRed, shape = CircleShape)
                    .testTag("decline_call_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.CallEnd,
                    contentDescription = "Decline Call",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Decline",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = onAnswer,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(CallGreen, shape = CircleShape)
                    .testTag("answer_call_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Call,
                    contentDescription = "Answer Call",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Answer",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }
    }
}

private fun formatCallDuration(totalSeconds: Int): String {
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "%02d:%02d".format(mins, secs)
}
