package com.paniuthche.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** Loading animation: water rising and falling in a circle, with two drifting waves. */
@Composable
fun WaterLoader(label: String?, modifier: Modifier = Modifier, size: Dp = 88.dp) {
    val t = rememberInfiniteTransition(label = "water")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "phase")
    val level by t.animateFloat(0.72f, 0.32f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "level")
    val water = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)

    // A null label means decoration only (onboarding art): nothing for screen readers.
    Column(if (label == null) modifier else modifier.semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2
            val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, r)) }
            fun wave(shift: Float, amp: Float) = Path().apply {
                val top = this@Canvas.size.height * level
                moveTo(0f, this@Canvas.size.height)
                var x = 0f
                while (x <= this@Canvas.size.width) {
                    lineTo(x, top + amp * sin(2 * PI.toFloat() * x / this@Canvas.size.width * 1.3f + phase + shift))
                    x += 3f
                }
                lineTo(this@Canvas.size.width, this@Canvas.size.height)
                close()
            }
            clipPath(circle) {
                drawPath(wave(PI.toFloat(), r * 0.09f), water.copy(alpha = 0.35f))
                drawPath(wave(0f, r * 0.11f), Brush.verticalGradient(listOf(water.copy(alpha = 0.75f), water),
                    startY = this.size.height * 0.3f, endY = this.size.height))
            }
            drawCircle(ring, r - 2.dp.toPx() / 2, style = Stroke(2.dp.toPx()))
            // A small highlight so the circle reads as glass, not a flat disc.
            drawCircle(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f), r * 0.1f, Offset(center.x - r * 0.45f, center.y - r * 0.45f))
        }
        if (label != null) Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
    }
}

/** WaterLoader on a floating card, for drawing over the map while its cells load. */
@Composable
fun WaterLoaderCard(label: String, modifier: Modifier = Modifier) =
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 6.dp) {
        WaterLoader(label, Modifier.padding(horizontal = 28.dp, vertical = 20.dp), size = 72.dp)
    }
