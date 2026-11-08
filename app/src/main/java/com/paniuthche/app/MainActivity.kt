package com.paniuthche.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale
import androidx.compose.ui.unit.dp
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState {
    data object Loading : UiState
    data class Ready(val forecast: Forecast) : UiState
    data class Error(val message: String?) : UiState
}

enum class MapMode { LIVE, PRONE, REPLAY }

sealed interface MlState {
    data object Loading : MlState
    data class Ready(val forecast: MlForecast) : MlState
    data object Error : MlState
}

// Shared by the Forecast and Map tabs (both scoped to the activity), so the district choice carries over.
class ForecastViewModel(app: Application) : AndroidViewModel(app) {
    var location by mutableStateOf(savedLocation(app)); private set
    var district by mutableStateOf(location?.let { nearestDistrict(it.first, it.second) } ?: districts[0]); private set
    var state by mutableStateOf<UiState>(UiState.Loading); private set
    var ml by mutableStateOf<MlState>(MlState.Loading); private set
    var alertsOn by mutableStateOf(settings(app).getBoolean("alerts_on", false)); private set
    var locating by mutableStateOf(false); private set
    /** Map tab layer; here so the Forecast tab can open the map on a given layer. */
    var mapMode by mutableStateOf(MapMode.LIVE)

    init { load(district) }

    /** Gets a GPS fix, saves it for the alert worker, and switches to the nearest district. */
    fun locate() {
        locating = true
        viewModelScope.launch {
            currentLocation(getApplication())?.let {
                saveLocation(getApplication(), it.latitude, it.longitude)
                location = it.latitude to it.longitude
                load(nearestDistrict(it.latitude, it.longitude))
            }
            locating = false
        }
    }

    fun setAlerts(on: Boolean) {
        if (on) scheduleAlerts(getApplication()) else cancelAlerts(getApplication())
        alertsOn = on
    }

    fun load(d: District) {
        district = d
        settings(getApplication()).edit().putString("district", d.key).apply()
        state = UiState.Loading
        ml = MlState.Loading
        viewModelScope.launch {
            state = try {
                UiState.Ready(withContext(Dispatchers.IO) { fetchForecast(d) })
            } catch (e: Exception) {
                UiState.Error(e.message)
            }
        }
        viewModelScope.launch {
            ml = try {
                MlState.Ready(withContext(Dispatchers.IO) { fetchMlForecast(getApplication(), d) })
            } catch (e: Exception) {
                MlState.Error
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            var lang by remember { mutableStateOf(Lang.valueOf(prefs.getString("lang", null) ?: Lang.BN.name)) }
            var onboarded by remember { mutableStateOf(prefs.getBoolean("onboarded", false)) }
            val toggleLang = {
                lang = if (lang == Lang.BN) Lang.EN else Lang.BN
                prefs.edit().putString("lang", lang.name).commit()
            }
            PaniTheme {
                LocalizedContent(lang) {
                    if (onboarded) App(onToggleLang = { toggleLang() })
                    else Onboarding(onToggleLang = { toggleLang() }, onDone = {
                        prefs.edit().putBoolean("onboarded", true).commit()
                        onboarded = true
                    })
                }
            }
        }
    }
}

@Composable
fun LangToggle(onToggle: () -> Unit) =
    OutlinedButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text(stringResource(R.string.lang_toggle))
    }

@Composable
fun App(onToggleLang: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                val primary = MaterialTheme.colorScheme.primary
                val red = MaterialTheme.colorScheme.error
                NavigationBarItem(
                    tab == 0, { tab = 0 },
                    icon = { Icon(Icons.Filled.Home, null) }, label = { Text(stringResource(R.string.nav_forecast)) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = primary, selectedTextColor = primary),
                )
                NavigationBarItem(
                    tab == 1, { tab = 1 },
                    icon = { Icon(Icons.Filled.Place, null) }, label = { Text(stringResource(R.string.nav_map)) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = primary, selectedTextColor = primary),
                )
                NavigationBarItem(
                    tab == 2, { tab = 2 },
                    icon = { Icon(Icons.Filled.Warning, null) }, label = { Text(stringResource(R.string.nav_sos)) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = red, selectedTextColor = red,
                        indicatorColor = red.copy(alpha = 0.15f)),
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> HomeScreen(onToggleLang, onOpenMap = { tab = 1 })
                1 -> MapScreen(onToggleLang)
                else -> SosScreen(onToggleLang)
            }
        }
    }
}

private data class RiskStyle(@StringRes val label: Int, @StringRes val advice: Int, val color: Color, val icon: ImageVector)

private fun Risk.style() = when (this) {
    Risk.HIGH -> RiskStyle(R.string.risk_high, R.string.advice_high, RiskColors.high, Icons.Filled.Warning)
    Risk.MEDIUM -> RiskStyle(R.string.risk_medium, R.string.advice_medium, RiskColors.medium, Icons.Filled.Info)
    Risk.LOW -> RiskStyle(R.string.risk_low, R.string.advice_low, RiskColors.low, Icons.Filled.CheckCircle)
}

@Composable
fun HomeScreen(onToggleLang: () -> Unit, onOpenMap: () -> Unit, vm: ForecastViewModel = viewModel()) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LangToggle(onToggleLang)
            IconButton(onClick = { vm.load(vm.district) }) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
        }

        DistrictChips(vm)

        AlertsCard(vm)

        // One headline risk: the ML model (at the user's cell if located here, else the district's worst cell);
        // the river-only rule is the fallback when the published forecast can't be loaded.
        val ml = (vm.ml as? MlState.Ready)?.forecast
        val river = (vm.state as? UiState.Ready)?.forecast
        val here = vm.location?.takeIf { nearestDistrict(it.first, it.second) == vm.district }
        when {
            ml != null -> {
                val prob = here?.let { riskAt(it.first, it.second, ml.cells) } ?: ml.summary?.maxProb ?: 0.0
                val source = stringResource(if (here != null) R.string.src_ai_location else R.string.src_ai_district)
                RiskHero(alertLevel(prob).toRisk(), source) { MlUpdatedLabel(ml, onDark = true) }
                MlStats(ml.summary)
                FilledTonalButton(onClick = { vm.mapMode = MapMode.LIVE; onOpenMap() }) {
                    Icon(Icons.Filled.Place, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ai_view_map))
                }
            }
            river != null && vm.ml is MlState.Error -> RiskHero(river.risk, stringResource(R.string.src_river))
            vm.state is UiState.Error && vm.ml is MlState.Error ->
                ErrorCard((vm.state as UiState.Error).message) { vm.load(vm.district) }
            else -> Box(Modifier.fillMaxWidth().height(240.dp), Alignment.Center) {
                WaterLoader(stringResource(R.string.loading_forecast), size = 96.dp)
            }
        }

        AiSection(vm, onOpenMap)

        if (river != null) {
            Text(stringResource(R.string.river_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            StatsRow(river)
            ChartCard(river, MaterialTheme.colorScheme.primary)
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DistrictChips(vm: ForecastViewModel) =
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        districts.forEach { d ->
            FilterChip(
                selected = d == vm.district, onClick = { vm.load(d) }, label = { Text(stringResource(d.name)) },
                leadingIcon = if (d == vm.district) { { Icon(Icons.Filled.LocationOn, null, Modifier.size(18.dp)) } } else null,
            )
        }
    }

private fun AlertLevel.toRisk() = when (this) {
    AlertLevel.HIGH -> Risk.HIGH
    AlertLevel.MEDIUM -> Risk.MEDIUM
    AlertLevel.NONE -> Risk.LOW
}

@Composable
fun MlUpdatedLabel(f: MlForecast, onDark: Boolean = false) = Text(
    if (f.offline) stringResource(R.string.ai_offline) else stringResource(R.string.ai_updated, locDate(f.generated)),
    style = MaterialTheme.typography.labelSmall,
    color = when {
        onDark -> Color.White.copy(alpha = 0.85f)
        f.offline -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    },
)

@Composable
fun MlStats(s: AreaSummary?) {
    @Composable fun count(n: Int?) = "%,d".format(Locale.US, n ?: 0).loc()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(stringResource(R.string.ai_max_risk), "${((s?.maxProb ?: 0.0) * 100).toInt()}%".loc(), "",
                Modifier.weight(1f).fillMaxHeight())
            Stat(stringResource(R.string.ai_area_at_risk), count(s?.cellsAtRisk), "km²", Modifier.weight(1f).fillMaxHeight())
            Stat(stringResource(R.string.ai_people_exposed), count(s?.peopleExposed), "", Modifier.weight(1f).fillMaxHeight())
        }
        // Assets in cells at or above 50% risk.
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(stringResource(R.string.ai_cropland_exposed), count(s?.croplandHaExposed), stringResource(R.string.unit_hectares),
                Modifier.weight(1f).fillMaxHeight())
            Stat(stringResource(R.string.ai_buildings_exposed), count(s?.buildingsExposed), "", Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun RiskHero(risk: Risk, source: String, badge: @Composable () -> Unit = {}) {
    val st = risk.style()
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(st.color, st.color.copy(alpha = 0.75f))))
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), Alignment.Center) {
                    Icon(st.icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Column {
                    Text(stringResource(R.string.next_7_days), style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.85f))
                    Text(stringResource(st.label), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            Text(stringResource(st.advice), style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(source, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                badge()
            }
        }
    }
}

@Composable
private fun StatsRow(f: Forecast) {
    val pct = if (f.normal > 0) (f.peakDischarge / f.normal * 100).toInt() else 0
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat(stringResource(R.string.stat_peak_flow), "%.0f".format(Locale.US, f.peakDischarge).loc(), "m³/s",
            Modifier.weight(1f).fillMaxHeight())
        Stat(stringResource(R.string.stat_peak_day), locDate(f.peakDay), "", Modifier.weight(1f).fillMaxHeight())
        Stat(stringResource(R.string.stat_vs_normal), "$pct%".loc(), "", Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
fun Stat(label: String, value: String, unit: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
            if (unit.isNotEmpty()) Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChartCard(f: Forecast, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.chart_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            DischargeChart(f, color, MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend(color, stringResource(R.string.legend_past))
                Legend(color.copy(alpha = 0.5f), stringResource(R.string.legend_forecast))
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    Spacer(Modifier.width(6.dp))
    Text(text, style = MaterialTheme.typography.labelMedium)
}

@Composable
fun DischargeChart(f: Forecast, color: Color, axisColor: Color) {
    val max = (f.discharge.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val stepX = size.width / (f.discharge.size - 1).coerceAtLeast(1)
        fun pt(i: Int) = Offset(i * stepX, size.height * (1 - (f.discharge[i] / max).toFloat() * 0.9f))
        fun line(from: Int, to: Int) = Path().apply {
            moveTo(pt(from).x, pt(from).y)
            for (i in from + 1..to) lineTo(pt(i).x, pt(i).y)
        }
        val last = f.discharge.lastIndex
        val area = line(0, last).apply { lineTo(pt(last).x, size.height); lineTo(0f, size.height); close() }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.3f), Color.Transparent)))
        drawPath(line(0, f.todayIndex), color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        drawPath(line(f.todayIndex, last), color.copy(alpha = 0.5f),
            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
        val x = f.todayIndex * stepX
        drawLine(axisColor.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        drawCircle(color, 6.dp.toPx(), pt(f.todayIndex))
        drawCircle(Color.White, 3.dp.toPx(), pt(f.todayIndex))
    }
}

@Composable
private fun ErrorCard(message: String?, onRetry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.error_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message ?: stringResource(R.string.network_error), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}
