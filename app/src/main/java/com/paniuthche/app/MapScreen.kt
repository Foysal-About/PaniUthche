package com.paniuthche.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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

@Composable
fun MapScreen(onToggleLang: () -> Unit, vm: ForecastViewModel = viewModel()) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                LangToggle(onToggleLang)
            }
            DistrictChips(vm)
            when (val ml = vm.ml) {
                MlState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                MlState.Error -> Text(stringResource(R.string.ai_error), color = MaterialTheme.colorScheme.error)
                is MlState.Ready -> {
                    MlStats(ml.forecast.summary)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("10–30%" to riskBands[2].second, "30–50%" to riskBands[1].second, "50%+" to riskBands[0].second)
                            .forEach { (label, color) -> LegendDot(color, label.loc()) }
                        Spacer(Modifier.weight(1f))
                        MlUpdatedLabel(ml.forecast)
                    }
                }
            }
        }
        val cells = (vm.ml as? MlState.Ready)?.forecast?.cells.orEmpty()
        RiskMap(vm.district, cells, vm.location, Modifier.weight(1f).fillMaxWidth())
        Text(stringResource(R.string.map_note), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
