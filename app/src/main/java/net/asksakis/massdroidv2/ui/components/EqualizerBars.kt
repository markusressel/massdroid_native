package net.asksakis.massdroidv2.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun EqualizerBars(
    modifier: Modifier = Modifier,
    barWidth: Dp = 3.dp,
    spacing: Dp = 2.dp,
    barCount: Int = 4,
    bpm: Int = 130,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "eq")
    val beatMs = 60_000 / bpm
    val cycleMs = beatMs * 2
    val cycleMults = listOf(1.0f, 1.3f, 0.9f, 1.15f, 1.05f)
    val offsets = listOf(0, beatMs / 3, beatMs * 2 / 3, beatMs / 6, beatMs / 2)
    val peaks = listOf(0.95f, 0.75f, 0.85f, 0.65f, 0.8f)
    val lows = listOf(0.2f, 0.25f, 0.18f, 0.3f, 0.22f)

    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.Bottom,
        modifier = modifier
    ) {
        repeat(barCount) { index ->
            val peak = peaks[index % peaks.size]
            val low = lows[index % lows.size]
            val dur = (cycleMs * cycleMults[index % cycleMults.size]).toInt()
            val height by infiniteTransition.animateFloat(
                initialValue = low,
                targetValue = peak,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = dur
                        low at 0 using EaseInOut
                        peak * 0.7f at (dur * 0.25f).toInt() using EaseInOut
                        peak at (dur * 0.45f).toInt() using EaseInOut
                        low + (peak - low) * 0.3f at (dur * 0.7f).toInt() using EaseInOut
                        low at dur using EaseInOut
                    },
                    initialStartOffset = StartOffset(offsets[index % offsets.size])
                ),
                label = "bar$index"
            )
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .fillMaxHeight(height)
                    .background(
                        color.copy(alpha = 0.4f + height * 0.6f),
                        RoundedCornerShape(barWidth / 2)
                    )
            )
        }
    }
}
