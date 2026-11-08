package com.paniuthche.app

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SosViewModel(app: Application) : AndroidViewModel(app) {
    val mesh = SosMesh(app)
    var running by mutableStateOf(false); private set
    var sending by mutableStateOf(false); private set
    /** This phone's latest fix, for distances to received SOS messages. */
    var here by mutableStateOf<Pair<Double, Double>?>(null); private set

    fun toggle() {
        if (running) mesh.stop() else {
            mesh.start()
            // Warm up GPS now so a later SOS gets a fix fast; GPS needs no internet.
            viewModelScope.launch { locate() }
        }
        running = !running
    }

    // currentLocation reuses a fix up to 30 min old, else waits for a new one (capped here).
    private suspend fun locate() = withTimeoutOrNull(10_000) { currentLocation(getApplication()) }
        ?.let { (it.latitude to it.longitude).also { p -> here = p } }

    fun send(msg: SosMessage) {
        sending = true
        viewModelScope.launch {
            val p = locate()
            mesh.send(msg.copy(lat = p?.first, lon = p?.second))
            sending = false
        }
    }

    override fun onCleared() = mesh.stop()
}

private val meshPermissions: Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    // Android 17 gates local-network access; Nearby aborts advertising without it.
    if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
}.toTypedArray()

@Composable
fun SosScreen(onToggleLang: () -> Unit, vm: SosViewModel = viewModel()) {
    var denied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        denied = result.values.any { !it }
        if (!denied) vm.toggle()
    }
    var people by remember { mutableIntStateOf(1) }
    var elderly by remember { mutableStateOf(false) }
    var children by remember { mutableStateOf(false) }
    var pregnant by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val red = MaterialTheme.colorScheme.error

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sos_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, color = red)
                LangToggle(onToggleLang)
            }
            Text(stringResource(R.string.sos_subtitle),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            MeshCard(
                running = vm.running, meshStatus = vm.mesh.status.value, peers = vm.mesh.peerCount.value,
                onToggle = { if (vm.running) vm.toggle() else permissionLauncher.launch(meshPermissions) },
            )
            if (denied) Text(stringResource(R.string.perm_denied),
                color = red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Person, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.people_count), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Stepper(people, onChange = { people = it.coerceIn(1, 99) })
                    }
                    Text(stringResource(R.string.special_needs), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeedChip(stringResource(R.string.elderly), elderly) { elderly = !elderly }
                        NeedChip(stringResource(R.string.children), children) { children = !children }
                        NeedChip(stringResource(R.string.pregnant), pregnant) { pregnant = !pregnant }
                    }
                    OutlinedTextField(
                        value = note, onValueChange = { note = it.take(200) }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.note_label)) },
                        placeholder = { Text(stringResource(R.string.note_placeholder)) },
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            }
        }

        item {
            Box(Modifier.fillMaxWidth(), Alignment.Center) {
                SosButton(enabled = vm.running && !vm.sending) {
                    vm.send(SosMessage(people = people, elderly = elderly, children = children,
                        pregnant = pregnant, note = note.trim()))
                    note = ""
                }
            }
            if (!vm.running) Text(stringResource(R.string.sos_enable_hint), Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Text(stringResource(R.string.received_messages, vm.mesh.messages.size.toString().loc()),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (vm.mesh.messages.isEmpty()) item {
            Text(stringResource(R.string.no_messages), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(vm.mesh.messages, key = { it.id }) { SosCard(it, vm.here) }
    }
}

@Composable
private fun MeshCard(running: Boolean, meshStatus: MeshStatus, peers: Int, onToggle: () -> Unit) {
    val status = when (meshStatus) {
        MeshStatus.Off -> stringResource(R.string.mesh_off)
        MeshStatus.Searching -> stringResource(R.string.mesh_searching)
        is MeshStatus.Error -> stringResource(R.string.mesh_error, meshStatus.message)
    }
    val dot = if (running) RiskColors.low else MaterialTheme.colorScheme.outline
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(status, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.connected_phones, peers.toString().loc()), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = running, onCheckedChange = { onToggle() },
                thumbContent = { Icon(Icons.Filled.Share, null, Modifier.size(16.dp)) })
        }
    }
}

@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    FilledTonalIconButton(onClick = { onChange(value - 1) }, enabled = value > 1) { Text("−", fontSize = 20.sp) }
    Text(value.toString().loc(), Modifier.widthIn(min = 40.dp), style = MaterialTheme.typography.titleLarge,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    FilledTonalIconButton(onClick = { onChange(value + 1) }) { Icon(Icons.Filled.Add, stringResource(R.string.increase)) }
}

@Composable
private fun NeedChip(label: String, selected: Boolean, onClick: () -> Unit) = FilterChip(
    selected, onClick, { Text(label) },
    leadingIcon = if (selected) { { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) } } else null,
)

@Composable
private fun SosButton(enabled: Boolean, onClick: () -> Unit) {
    val red = MaterialTheme.colorScheme.error
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        1f, 1.15f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale",
    )
    Box(Modifier.size(200.dp), Alignment.Center) {
        if (enabled) Box(Modifier.size(170.dp).scale(pulse).clip(CircleShape).background(red.copy(alpha = 0.2f)))
        Box(
            Modifier.size(150.dp).clip(CircleShape)
                .background(if (enabled) red else MaterialTheme.colorScheme.surfaceVariant)
                .border(6.dp, Color.White.copy(alpha = if (enabled) 0.35f else 0f), CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SOS", fontSize = 40.sp, fontWeight = FontWeight.Black,
                    color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.sos_press), style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SosCard(m: SosMessage, here: Pair<Double, Double>?) {
    val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(m.createdAt)).loc()
    val flags = listOfNotNull(
        stringResource(R.string.elderly).takeIf { m.elderly },
        stringResource(R.string.children).takeIf { m.children },
        stringResource(R.string.pregnant).takeIf { m.pregnant },
    )
    val accent = if (flags.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.people_stuck, m.people.toString().loc()), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (flags.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    flags.forEach {
                        Text(it, Modifier.clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium, color = accent)
                    }
                }
                if (m.note.isNotEmpty()) Text(m.note, style = MaterialTheme.typography.bodyMedium)
                SosLocation(m, here)
                Text(if (m.hops == 0) stringResource(R.string.sent_from_here)
                    else stringResource(R.string.hops_away, m.hops.toString().loc()),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Coordinates and distance from this phone; tap opens any installed map app (offline maps work). */
@Composable
private fun SosLocation(m: SosMessage, here: Pair<Double, Double>?) {
    val ctx = LocalContext.current
    if (m.lat == null || m.lon == null) {
        Text(stringResource(R.string.sos_no_location), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val coords = "%.5f, %.5f".format(Locale.US, m.lat, m.lon)
    val km = here?.takeIf { m.hops > 0 }?.let { distanceKm(it.first, it.second, m.lat, m.lon) }
    val text = coords.loc() + km?.let { " · " + stringResource(R.string.sos_distance, "%.1f".format(Locale.US, it).loc()) }.orEmpty()
    Text("📍 $text", Modifier.clickable {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${m.lat},${m.lon}?q=${m.lat},${m.lon}(SOS)"))) }
    }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}
