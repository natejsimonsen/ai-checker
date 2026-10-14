package dev.aichecker.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.aichecker.app.data.SegmentScore
import kotlin.math.roundToInt

/** 270° arc gauge showing the AI likelihood as a percentage. */
@Composable
fun LikelihoodGauge(probability: Float, color: Color, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(probability) { anim.animateTo(probability, tween(durationMillis = 900)) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val percent = (probability * 100).roundToInt()
    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "$percent percent likely AI" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * anim.value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(anim.value * 100).roundToInt()}%", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Text("AI likelihood", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Bar per sliding window: height and colour show that window's AI probability. */
@Composable
fun SegmentTimeline(segments: List<SegmentScore>, dark: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(72.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (s in segments) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(0.08f + 0.92f * s.aiProbability)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(probabilityColor(s.aiProbability, dark)),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val style = MaterialTheme.typography.labelSmall
            val c = MaterialTheme.colorScheme.onSurfaceVariant
            Text(formatSeconds(segments.first().startSeconds), style = style, color = c)
            Text(formatSeconds(segments.last().endSeconds), style = style, color = c)
        }
    }
}

fun formatSeconds(s: Double): String {
    val total = s.roundToInt()
    return "%d:%02d".format(total / 60, total % 60)
}
