package com.paniuthche.app

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

@StringRes
private fun featureName(key: String): Int? = when (key) {
    "landcover" -> R.string.feat_landcover
    "hnd" -> R.string.feat_hnd
    "slope" -> R.string.feat_slope
    "dist_water" -> R.string.feat_dist_water
    "upa_log" -> R.string.feat_upa_log
    else -> null
}

/** Shows what the model is, how it scored on a flood it never saw, and links to its map layers. */
@Composable
fun AiSection(vm: ForecastViewModel, onOpenMap: () -> Unit) {
    val ctx = LocalContext.current
    val info by produceState<ModelInfo?>(null) {
        value = runCatching { withContext(Dispatchers.IO) { fetchModelInfo(ctx) } }.getOrNull()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ai_card_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.ai_card_body), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        info?.let { m ->
            Text(stringResource(R.string.ai_card_test), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(stringResource(R.string.ai_auc), "%.2f".format(Locale.US, m.auc).loc(), "", Modifier.weight(1f).fillMaxHeight())
                Stat(stringResource(R.string.ai_precision), "${(m.precision * 100).roundToInt()}%".loc(), "",
                    Modifier.weight(1f).fillMaxHeight())
                Stat(stringResource(R.string.ai_recall), "${(m.recall * 100).roundToInt()}%".loc(), "",
                    Modifier.weight(1f).fillMaxHeight())
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ai_importance), style = MaterialTheme.typography.labelLarge)
                    val max = m.importance.maxOf { it.second }.coerceAtLeast(1e-9)
                    m.importance.forEach { (key, v) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(featureName(key)?.let { stringResource(it) } ?: key, Modifier.width(150.dp),
                                style = MaterialTheme.typography.bodySmall)
                            Box(Modifier.weight(1f).height(10.dp)) {
                                Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight()
                                    .clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                            }
                        }
                    }
                }
            }
        }
        FilledTonalButton(onClick = { vm.mapMode = MapMode.REPLAY; onOpenMap() }) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ai_open_replay))
        }
        OutlinedButton(onClick = { vm.mapMode = MapMode.PRONE; onOpenMap() }) {
            Icon(Icons.Filled.Place, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ai_open_prone))
        }
    }
}
