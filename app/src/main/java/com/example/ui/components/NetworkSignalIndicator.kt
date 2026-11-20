package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.NetworkStability
import com.example.ui.theme.CallAmber
import com.example.ui.theme.CallGreen
import com.example.ui.theme.CallRed
import com.example.ui.theme.DarkOnBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.TickSentGrey

/**
 * Compact 4-bar connection stability indicator driven by Agora's `onNetworkQuality` callback.
 *
 * Uses [compact] = true for inline bars inside minimized call headers, or [compact] = false
 * for a translucent pill badge with a readable stability label on full call screens.
 */
@Composable
fun NetworkSignalIndicator(
    stability: NetworkStability,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    latencyMs: Int = 0
) {
    val label = when (stability) {
        NetworkStability.EXCELLENT -> stringResource(R.string.network_quality_excellent)
        NetworkStability.GOOD -> stringResource(R.string.network_quality_good)
        NetworkStability.FAIR -> stringResource(R.string.network_quality_fair)
        NetworkStability.POOR -> stringResource(R.string.network_quality_poor)
        NetworkStability.DISCONNECTED -> stringResource(R.string.network_quality_disconnected)
        NetworkStability.UNKNOWN -> stringResource(R.string.network_quality_checking)
    }

    val targetColor = when (stability) {
        NetworkStability.EXCELLENT,
        NetworkStability.GOOD -> CallGreen
        NetworkStability.FAIR -> CallAmber
        NetworkStability.POOR,
        NetworkStability.DISCONNECTED -> CallRed
        NetworkStability.UNKNOWN -> TickSentGrey
    }

    val activeColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 250),
        label = "signalActiveColor"
    )
    val inactiveColor = DarkOnBackground.copy(alpha = 0.22f)
    val descriptionText = stringResource(R.string.network_quality_cd, label)

    if (compact) {
        SignalStrengthBars(
            activeBars = stability.activeBars,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            barWidth = 2.5.dp,
            maxHeight = 12.dp,
            modifier = modifier
                .semantics { contentDescription = descriptionText }
                .testTag("network_signal_indicator_compact")
        )
    } else {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(DarkSurface.copy(alpha = 0.65f))
                .border(
                    width = 1.dp,
                    color = activeColor.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .semantics(mergeDescendants = true) { contentDescription = descriptionText }
                .testTag("network_signal_indicator"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            SignalStrengthBars(
                activeBars = stability.activeBars,
                activeColor = activeColor,
                inactiveColor = inactiveColor,
                barWidth = 3.dp,
                maxHeight = 14.dp
            )
            Text(
                text = if (latencyMs > 0 && stability != NetworkStability.UNKNOWN) {
                    "$label • ${latencyMs}ms"
                } else {
                    label
                },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = DarkOnBackground.copy(alpha = 0.92f)
            )
        }
    }
}

@Composable
private fun SignalStrengthBars(
    activeBars: Int,
    activeColor: Color,
    inactiveColor: Color,
    barWidth: Dp,
    maxHeight: Dp,
    modifier: Modifier = Modifier
) {
    val barRatios = listOf(0.35f, 0.56f, 0.78f, 1.0f)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        barRatios.forEachIndexed { index, ratio ->
            val isLit = index < activeBars
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(maxHeight * ratio)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(if (isLit) activeColor else inactiveColor)
            )
        }
    }
}
