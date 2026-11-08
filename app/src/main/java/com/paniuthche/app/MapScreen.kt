package com.paniuthche.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

private val riskBands = listOf(
    0.5 to RiskColors.high,
    0.3 to RiskColors.medium,
    0.1 to Color(0xFFFBC02D),
)

private fun cellColor(prob: Double): Color = riskBands.first { prob >= it.first }.second

private val replayDistrict = districts.first { it.key == "feni" }

@Composable
fun MapScreen(onToggleLang: () -> Unit, vm: ForecastViewModel = viewModel()) {
    var replay by rememberSaveable { mutableStateOf(false) }
    var replayCells by remember { mutableStateOf(emptyList<RiskCell>()) }
    val ml = vm.ml
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                LangToggle(onToggleLang)
            }
            FilterChip(selected = replay, onClick = { replay = !replay }, label = { Text(stringResource(R.string.replay_toggle)) })
            if (replay) ReplayPanel(onCells = { replayCells = it })
            else {
                DistrictChips(vm)
                when (ml) {
                    MlState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    MlState.Error -> Text(stringResource(R.string.ai_error), color = MaterialTheme.colorScheme.error)
                    is MlState.Ready -> MlStats(ml.forecast.summary)
                }
            }
            if (replay || ml is MlState.Ready) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("10–30%" to riskBands[2].second, "30–50%" to riskBands[1].second, "50%+" to riskBands[0].second)
                    .forEach { (label, color) -> LegendDot(color, label.loc()) }
                Spacer(Modifier.weight(1f))
                if (!replay && ml is MlState.Ready) MlUpdatedLabel(ml.forecast)
            }
        }
        val cells = if (replay) replayCells else (ml as? MlState.Ready)?.forecast?.cells.orEmpty()
        RiskMap(if (replay) replayDistrict else vm.district, cells, vm.location, Modifier.weight(1f).fillMaxWidth())
        Text(stringResource(if (replay) R.string.replay_note else R.string.map_note), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Day slider over the Feni 2024 replay; reports the chosen day's cells for the map. */
@Composable
private fun ReplayPanel(onCells: (List<RiskCell>) -> Unit) {
    val ctx = LocalContext.current
    val timeline by produceState<List<ReplayDay>?>(null) {
        value = try { withContext(Dispatchers.IO) { fetchReplayTimeline(ctx) } } catch (e: Exception) { emptyList() }
    }
    val days = timeline
    if (days == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    if (days.isEmpty()) { Text(stringResource(R.string.ai_error), color = MaterialTheme.colorScheme.error); return }

    var index by rememberSaveable { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    val day = days[index.coerceIn(days.indices)]
    LaunchedEffect(day.date) {
        onCells(try { withContext(Dispatchers.IO) { fetchReplayCells(ctx, day.date) } } catch (e: Exception) { emptyList() })
    }
    LaunchedEffect(playing) {
        while (playing && index < days.lastIndex) { delay(1200); index++ }
        playing = false
    }

    val level = alertLevel(day.summary.maxProb)
    // Same notification the alert worker posts, using the district-wide risk (no location) for that day.
    fun showAlert() = notify(ctx, replayDistrict, level, day.summary.maxProb, null, false)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) showAlert() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${locDate(day.date)} ${day.date.take(4).loc()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(when (level) {
                AlertLevel.HIGH -> R.string.risk_high
                AlertLevel.MEDIUM -> R.string.risk_medium
                AlertLevel.NONE -> R.string.risk_low
            }), color = if (level == AlertLevel.NONE) MaterialTheme.colorScheme.onSurfaceVariant else cellColor(day.summary.maxProb))
        }
        TextButton(onClick = { if (!playing && index == days.lastIndex) index = 0; playing = !playing }) {
            Text(stringResource(if (playing) R.string.replay_pause else R.string.replay_play))
        }
    }
    Slider(value = index.toFloat(), onValueChange = { index = it.roundToInt(); playing = false },
        valueRange = 0f..days.lastIndex.toFloat(), steps = (days.size - 2).coerceAtLeast(0))
    MlStats(day.summary)
    OutlinedButton(onClick = {
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(ctx, Manifest.permission.POST_NOTIFICATIONS))
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else showAlert()
    }) { Text(stringResource(R.string.replay_alert)) }
}

@Composable
private fun LegendDot(color: Color, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(color.copy(alpha = 0.7f)))
    Spacer(Modifier.width(4.dp))
    Text(text, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun RiskMap(district: District, cells: List<RiskCell>, me: Pair<Double, Double>?, modifier: Modifier) {
    val youAreHere = stringResource(R.string.map_you)
    AndroidView(
        // MapView draws past its bounds unless clipped, covering the legend and note.
        modifier = modifier.clipToBounds(),
        factory = { ctx ->
            // OSM's tile policy requires an identifying user agent.
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(10.0)
            }
        },
        update = { map ->
            map.overlays.clear()
            cells.forEach { c ->
                map.overlays.add(Polygon(map).apply {
                    points = c.ring.map { (lat, lon) -> GeoPoint(lat, lon) }
                    fillPaint.color = cellColor(c.prob).copy(alpha = 0.55f).toArgb()
                    outlinePaint.color = android.graphics.Color.TRANSPARENT
                    title = "${(c.prob * 100).toInt()}% · ${c.people}"
                })
            }
            val inDistrict = me != null && nearestDistrict(me.first, me.second) == district
            if (inDistrict) map.overlays.add(Marker(map).apply {
                position = GeoPoint(me!!.first, me.second)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = youAreHere
            })
            // Re-frame only when the district changes, so a refresh doesn't undo the user's pinch-zoom.
            if (map.tag != district.key) {
                map.tag = district.key
                map.controller.setZoom(10.0)
                map.controller.setCenter(if (inDistrict) GeoPoint(me!!.first, me.second) else GeoPoint(district.lat, district.lon))
            }
            map.invalidate()
        },
        onRelease = { it.onDetach() },
    )
}
