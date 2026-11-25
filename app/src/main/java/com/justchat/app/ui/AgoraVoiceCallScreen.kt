package com.justchat.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.R
import com.example.data.AgoraCallController
import com.example.data.AgoraCallListener
import com.example.data.AgoraCallStats
import com.example.model.NetworkStability
import com.example.ui.components.NetworkSignalIndicator
import com.example.ui.theme.CallGreen
import com.example.ui.theme.CallRed
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkOnBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceCard
import com.example.ui.theme.DarkSurfaceVariant
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Self-contained Agora voice call screen with runtime `RECORD_AUDIO` permission handling,
 * `AudioManager.MODE_IN_COMMUNICATION` lifecycle management (via [AgoraCallController]),
 * speakerphone default routing, mute/speaker toggles, and secure [BuildConfig.AGORA_APP_ID]
 * credential injection.
 */
@Composable
fun AgoraVoiceCallScreen(
    peerName: String,
    channelName: String,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val agoraController = remember(context) {
        AgoraCallController(
            context = context.applicationContext,
            myAppId = BuildConfig.AGORA_APP_ID.trim(),
            configuredChannelName = BuildConfig.AGORA_CHANNEL_NAME.trim()
        )
    }

    var callStatus by remember(channelName) {
        mutableStateOf(
            if (agoraController.isConfigured) {
                context.getString(R.string.call_status_connecting_audio)
            } else {
                context.getString(R.string.agora_missing_app_id_note)
            }
        )
    }
    var isConnected by remember(channelName) { mutableStateOf(false) }
    var isMuted by remember(channelName) { mutableStateOf(false) }
    var isSpeakerOn by remember(channelName) { mutableStateOf(true) }
    var elapsedSeconds by remember(channelName) { mutableIntStateOf(0) }
    var networkStability by remember(channelName) { mutableStateOf(NetworkStability.UNKNOWN) }
    var latencyMs by remember(channelName) { mutableIntStateOf(0) }

    val startVoiceCall = remember(agoraController, channelName, peerName) {
        {
            if (agoraController.isConfigured) {
                callStatus = context.getString(R.string.call_status_connecting_audio)
                agoraController.joinCall(
                    channelName = channelName,
                    isSpeakerDefault = isSpeakerOn,
                    isLowDataMode = true,
                    listener = object : AgoraCallListener {
                        override fun onJoinedChannel(channel: String, uid: Int) {
                            if (!isConnected) {
                                callStatus = context.getString(
                                    R.string.call_status_waiting_for_peer,
                                    peerName
                                )
                            }
                        }

                        override fun onRemoteUserJoined(uid: Int) {
                            isConnected = true
                            callStatus = context.getString(R.string.call_status_connected)
                        }

                        override fun onRemoteUserLeft(uid: Int, reason: Int) {
                            isConnected = false
                            callStatus = context.getString(
                                R.string.call_status_peer_disconnected,
                                peerName
                            )
                        }

                        override fun onStatsUpdated(stats: AgoraCallStats) {
                            latencyMs = stats.latencyMs
                        }

                        override fun onNetworkQualityChanged(txQuality: Int, rxQuality: Int) {
                            networkStability = NetworkStability.fromAgoraQuality(txQuality, rxQuality)
                        }

                        override fun onError(errorCode: Int, message: String) {
                            isConnected = false
                            callStatus = message
                        }
                    }
                )
            } else {
                callStatus = context.getString(R.string.agora_missing_app_id_note)
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceCall()
        } else {
            callStatus = context.getString(R.string.call_status_mic_permission_required)
            Toast.makeText(
                context,
                context.getString(R.string.call_mic_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    BackHandler {
        agoraController.leaveCall()
        onEndCall()
    }

    DisposableEffect(agoraController, channelName) {
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasMic) {
            startVoiceCall()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        onDispose {
            agoraController.leaveCall()
            agoraController.destroy()
        }
    }

    LaunchedEffect(isConnected) {
        if (!isConnected) return@LaunchedEffect
        while (true) {
            delay(1000)
            elapsedSeconds += 1
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        DarkSurface,
                        DarkBackground
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 480.dp)
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Spacer(modifier = Modifier.height(36.dp))

                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(DarkSurfaceCard),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = peerName.trim().firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = DarkOnBackground
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = peerName,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = DarkOnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("agora_call_peer_name")
                )

                Text(
                    text = if (isConnected && elapsedSeconds > 0) {
                        "$callStatus • ${formatVoiceCallDuration(elapsedSeconds)}"
                    } else {
                        callStatus
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isConnected) {
                        CallGreen
                    } else {
                        DarkOnBackground.copy(alpha = 0.72f)
                    },
                    modifier = Modifier.testTag("agora_call_status_label")
                )

                NetworkSignalIndicator(
                    stability = networkStability,
                    latencyMs = latencyMs
                )

                Text(
                    text = stringResource(R.string.call_channel_label, channelName),
                    style = MaterialTheme.typography.bodySmall,
                    color = DarkOnBackground.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("combined_call_channel_label")
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    onClick = {
                        val nextMute = !isMuted
                        isMuted = nextMute
                        agoraController.setMuted(nextMute)
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isMuted) DarkSurfaceCard else DarkSurfaceVariant,
                        contentColor = DarkOnBackground
                    ),
                    modifier = Modifier
                        .size(58.dp)
                        .testTag("combined_mute_button")
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        contentDescription = stringResource(
                            if (isMuted) R.string.action_unmute else R.string.action_mute
                        )
                    )
                }

                FilledIconButton(
                    onClick = {
                        agoraController.leaveCall()
                        onEndCall()
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = CallRed,
                        contentColor = DarkOnBackground
                    ),
                    modifier = Modifier
                        .size(68.dp)
                        .testTag("combined_end_call_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CallEnd,
                        contentDescription = stringResource(R.string.action_end_call),
                        modifier = Modifier.size(28.dp)
                    )
                }

                FilledTonalIconButton(
                    onClick = {
                        val nextSpeaker = !isSpeakerOn
                        isSpeakerOn = nextSpeaker
                        agoraController.setSpeakerEnabled(nextSpeaker)
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isSpeakerOn) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            DarkSurfaceVariant
                        },
                        contentColor = DarkOnBackground
                    ),
                    modifier = Modifier
                        .size(58.dp)
                        .testTag("combined_speaker_button")
                ) {
                    Icon(
                        imageVector = if (isSpeakerOn) {
                            Icons.AutoMirrored.Rounded.VolumeUp
                        } else {
                            Icons.AutoMirrored.Rounded.VolumeDown
                        },
                        contentDescription = stringResource(
                            if (isSpeakerOn) R.string.call_speaker_on_desc else R.string.call_speaker_off_desc
                        )
                    )
                }
            }
        }
    }
}

private fun formatVoiceCallDuration(totalSeconds: Int): String {
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
