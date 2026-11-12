package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CallGreen
import com.example.ui.theme.DataSaverCyan

@Composable
fun DataSaverBadge(
    isLowDataMode: Boolean,
    latencyMs: Int,
    dataRateKbps: Int,
    modifier: Modifier = Modifier,
    onToggle: (() -> Unit)? = null
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alphaPulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alphaPulse"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xCC0F172A))
            .then(if (onToggle != null) Modifier.clickable { onToggle() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Pulsing green dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(alphaPulse)
                .clip(CircleShape)
                .background(if (latencyMs < 30) CallGreen else Color(0xFFFBBF24))
        )

        Icon(
            imageVector = Icons.Rounded.Speed,
            contentDescription = null,
            tint = if (isLowDataMode) DataSaverCyan else Color.White,
            modifier = Modifier.size(14.dp)
        )

        Text(
            text = "${latencyMs}ms No-Lag",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )

        Box(
            modifier = Modifier
                .size(3.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.4f))
        )

        Text(
            text = if (isLowDataMode) "$dataRateKbps KB/s (Data Saver)" else "$dataRateKbps KB/s (HD)",
            color = if (isLowDataMode) DataSaverCyan else Color(0xFFE2E8F0),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
