package com.paniuthche.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

@Composable
fun AlertsCard(vm: ForecastViewModel) {
    val ctx = LocalContext.current
    var denied by remember { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        denied = r.values.none { it }
        if (!denied) vm.locate()
    }
    val alertLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val notifOk = Build.VERSION.SDK_INT < 33 || r[Manifest.permission.POST_NOTIFICATIONS] == true
        denied = !notifOk
        if (notifOk) {
            vm.setAlerts(true)
            if (r.any { it.key in locationPermissions && it.value }) vm.locate()
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Notifications, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.alerts_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.alerts_subtitle), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = vm.alertsOn, onCheckedChange = { on ->
                    if (!on) vm.setAlerts(false)
                    else alertLauncher.launch(
                        (if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()) + locationPermissions
                    )
                })
            }

            val loc = vm.location
            val ml = vm.ml
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                val text = when {
                    loc == null -> stringResource(R.string.loc_none)
                    nearestDistrict(loc.first, loc.second) != vm.district -> stringResource(R.string.loc_other_district)
                    ml is MlState.Ready -> {
                        val pct = (riskAt(loc.first, loc.second, ml.forecast.cells) * 100).toInt()
                        if (pct == 0) stringResource(R.string.loc_risk_low) else stringResource(R.string.loc_risk, "$pct%".loc())
                    }
                    else -> stringResource(R.string.loc_set)
                }
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { locationLauncher.launch(locationPermissions) }, enabled = !vm.locating) {
                    Text(stringResource(if (vm.locating) R.string.loc_finding else R.string.loc_use_mine))
                }
                if (vm.alertsOn) TextButton(onClick = { checkAlertsNow(ctx) }) { Text(stringResource(R.string.alerts_check_now)) }
            }
            if (denied) Text(stringResource(R.string.alerts_denied), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
