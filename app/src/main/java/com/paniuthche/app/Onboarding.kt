package com.paniuthche.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private class Page(@StringRes val title: Int, @StringRes val body: Int, val art: @Composable () -> Unit)

private val pages = listOf(
    Page(R.string.onb_welcome_title, R.string.onb_welcome_body) { WaterLoader(null, size = 190.dp) },
    Page(R.string.onb_ai_title, R.string.onb_ai_body) { RiskGridArt() },
    Page(R.string.onb_sos_title, R.string.onb_sos_body) { MeshArt() },
    Page(R.string.onb_alerts_title, R.string.onb_alerts_body) { BellArt() },
)

/** First-run walkthrough; the last page can switch on alerts with location in one step. */
@Composable
fun Onboarding(onToggleLang: () -> Unit, onDone: () -> Unit, vm: ForecastViewModel = viewModel()) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    val alertLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (Build.VERSION.SDK_INT < 33 || r[Manifest.permission.POST_NOTIFICATIONS] == true) vm.setAlerts(true)
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true || r[Manifest.permission.ACCESS_COARSE_LOCATION] == true) vm.locate()
        onDone()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LangToggle(onToggleLang)
            Spacer(Modifier.weight(1f))
            if (!last) TextButton(onClick = onDone) { Text(stringResource(R.string.onb_skip)) }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            val p = pages[i]
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(240.dp), Alignment.Center) { p.art() }
                Spacer(Modifier.height(36.dp))
                Text(stringResource(p.title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(p.body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                val selected = i == pager.currentPage
                val color by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "dot")
                val width by animateDpAsState(if (selected) 24.dp else 8.dp, label = "dotWidth")
                Box(Modifier.padding(horizontal = 4.dp).height(8.dp).width(width).clip(CircleShape).background(color))
            }
        }
        if (last) {
            Button(onClick = {
                alertLauncher.launch(
                    (if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()) +
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.onb_enable_alerts)) }
            TextButton(onClick = onDone, Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_not_now)) }
        } else {
            Button(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.onb_next)) }
            Spacer(Modifier.height(48.dp))  // keeps the button in place across pages
        }
    }
}

/** 1 km risk cells along a river; a wave of colour runs down it like rising risk. */
@Composable
private fun RiskGridArt() {
    val phase by rememberInfiniteTransition(label = "grid")
        .animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "phase")
    val bands = listOf(RiskColors.high, RiskColors.medium, Color(0xFFFBC02D))
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.size(210.dp)) {
        val n = 7
        val gap = 6.dp.toPx()
        val cell = (size.width - gap * (n - 1)) / n
        for (row in 0 until n) for (col in 0 until n) {
            val d = abs(row - col)  // distance from the diagonal "river"
            val wave = (sin(phase - (row + col) * 0.45f) + 1) / 2
            val color = if (d >= bands.size) empty else bands[d].copy(alpha = 0.3f + 0.7f * wave)
            drawRoundRect(color, Offset(col * (cell + gap), row * (cell + gap)), Size(cell, cell), CornerRadius(6.dp.toPx()))
        }
    }
}

/** Phones in a chain; a signal hops from the sender to the furthest phone, then repeats. */
@Composable
private fun MeshArt() {
    val t by rememberInfiniteTransition(label = "mesh")
        .animateFloat(0f, 3f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "hop")
    val red = MaterialTheme.colorScheme.error
    val line = MaterialTheme.colorScheme.outlineVariant
    val phone = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(230.dp)) {
        val pts = listOf(Offset(0.14f, 0.72f), Offset(0.38f, 0.30f), Offset(0.64f, 0.66f), Offset(0.86f, 0.24f))
            .map { Offset(it.x * size.width, it.y * size.height) }
        pts.zipWithNext().forEach { (a, b) ->
            drawLine(line, a, b, strokeWidth = 3.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                floatArrayOf(10f, 10f)))
        }
        val w = 30.dp.toPx(); val h = 50.dp.toPx()
        pts.forEachIndexed { i, p ->
            val reached = i <= t.toInt() || i == 0
            drawRoundRect(if (reached) (if (i == 0) red else phone) else line, Offset(p.x - w / 2, p.y - h / 2), Size(w, h),
                CornerRadius(7.dp.toPx()))
            drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(p.x - w / 2 + 4.dp.toPx(), p.y - h / 2 + 6.dp.toPx()),
                Size(w - 8.dp.toPx(), h - 16.dp.toPx()), CornerRadius(3.dp.toPx()))
        }
        val seg = t.toInt().coerceAtMost(2)
        val f = t - seg
        val pos = pts[seg] + (pts[seg + 1] - pts[seg]) * f
        drawCircle(red.copy(alpha = 0.25f), 14.dp.toPx() * (1 + f * 0.4f), pos)
        drawCircle(red, 7.dp.toPx(), pos)
    }
}

/** Notification bell with rings spreading out. */
@Composable
private fun BellArt() {
    val t by rememberInfiniteTransition(label = "bell")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearOutSlowInEasing)), label = "ring")
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.size(220.dp), Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            for (k in 0..1) {
                val p = (t + k * 0.5f) % 1f
                drawCircle(primary.copy(alpha = (1 - p) * 0.5f), size.minDimension / 2 * (0.45f + 0.55f * p),
                    style = Stroke(3.dp.toPx()))
            }
        }
        Box(Modifier.size(104.dp).clip(CircleShape).background(primary), Alignment.Center) {
            Icon(Icons.Filled.Notifications, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}
