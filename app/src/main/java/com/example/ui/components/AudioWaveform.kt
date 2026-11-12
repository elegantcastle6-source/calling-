package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun AudioWaveform(
    modifier: Modifier = Modifier,
    barCount: Int = 16,
    maxHeight: Dp = 32.dp,
    barWidth: Dp = 3.dp,
    barSpacing: Dp = 2.dp,
    color: Color = Color(0xFF6366F1),
    isAnimating: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "waveform")
    
    // Multiple phase animations for realistic fluctuating audio waves
    val phase1 by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase1"
    )
    val phase2 by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase2"
    )
    val phase3 by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase3"
    )

    Row(
        modifier = modifier.height(maxHeight),
        horizontalArrangement = Arrangement.spacedBy(barSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val basePattern = listOf(0.3f, 0.6f, 0.9f, 0.5f, 0.8f, 0.4f, 0.7f, 1.0f, 0.6f, 0.4f, 0.8f, 0.5f, 0.9f, 0.4f, 0.6f, 0.3f)
        for (i in 0 until barCount) {
            val baseVal = basePattern[i % basePattern.size]
            val animatedFraction = if (isAnimating) {
                when (i % 3) {
                    0 -> ((baseVal + phase1) / 2f).coerceIn(0.15f, 1f)
                    1 -> ((baseVal + phase2) / 2f).coerceIn(0.15f, 1f)
                    else -> ((baseVal + phase3) / 2f).coerceIn(0.15f, 1f)
                }
            } else {
                baseVal
            }

            Box(
                modifier = Modifier
                    .width(barWidth)
                    .fillMaxHeight(animatedFraction)
                    .clip(RoundedCornerShape(barWidth))
                    .background(color)
            )
        }
    }
}
