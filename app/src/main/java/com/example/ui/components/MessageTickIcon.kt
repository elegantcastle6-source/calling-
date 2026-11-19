package com.example.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.MessageStatus
import com.example.ui.theme.TickReadBlue
import com.example.ui.theme.TickSentGrey

/**
 * Displays the single/double delivery and read receipt tick for an outgoing chat message.
 * Supports `"sent"`, `"delivered"`, `"read"`, and `"sending"` (case-insensitive).
 */
@Composable
fun MessageTickIcon(
    status: String,
    modifier: Modifier = Modifier,
    readTint: Color = TickReadBlue,
    sentTint: Color = TickSentGrey
) {
    when (status.trim().lowercase()) {
        "read" -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = stringResource(R.string.message_status_read),
                tint = readTint,
                modifier = modifier
                    .size(16.dp)
                    .testTag("message_tick_read")
            )
        }
        "delivered" -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = stringResource(R.string.message_status_delivered),
                tint = sentTint,
                modifier = modifier
                    .size(16.dp)
                    .testTag("message_tick_delivered")
            )
        }
        "sending" -> {
            Icon(
                imageVector = Icons.Default.Done,
                contentDescription = stringResource(R.string.message_status_sending),
                tint = sentTint.copy(alpha = 0.55f),
                modifier = modifier
                    .size(16.dp)
                    .testTag("message_tick_sending")
            )
        }
        else -> {
            Icon(
                imageVector = Icons.Default.Done,
                contentDescription = stringResource(R.string.message_status_sent),
                tint = sentTint,
                modifier = modifier
                    .size(16.dp)
                    .testTag("message_tick_sent")
            )
        }
    }
}

@Composable
fun MessageTickIcon(
    status: MessageStatus,
    modifier: Modifier = Modifier,
    readTint: Color = TickReadBlue,
    sentTint: Color = TickSentGrey
) {
    MessageTickIcon(
        status = status.name.lowercase(),
        modifier = modifier,
        readTint = readTint,
        sentTint = sentTint
    )
}
