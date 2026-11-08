package com.paniuthche.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL

// Published twice daily by .github/workflows/forecast.yml (ml/forecast.py).
private const val ML_BASE_URL = "https://foysal-about.github.io/PaniUthche/"

data class AreaSummary(
    val maxProb: Double,
    val cellsAtRisk: Int,
    val peopleExposed: Int,
    val croplandHaExposed: Int = 0,
    val buildingsExposed: Int = 0,
)

data class RiskCell(val ring: List<Pair<Double, Double>>, val prob: Double, val people: Int, val peakDate: String = "")

data class MlForecast(
    val generated: String,
    val summary: AreaSummary?,
    val cells: List<RiskCell>,
    /** True when the network failed and the last saved copy was used. */
    val offline: Boolean,
)

/** Downloads [name] and saves a copy; falls back to the saved copy when offline. */
private fun fetchCached(context: Context, name: String): Pair<String, Boolean> {
    val file = File(context.filesDir, "ml_" + name.replace('/', '_'))
    return try {
        URL(ML_BASE_URL + name).readText().also { file.writeText(it) } to false
    } catch (e: IOException) {
        if (file.exists()) file.readText() to true else throw e
    }
}

// Blocking network call; run off the main thread.
fun fetchMlForecast(context: Context, d: District): MlForecast {
    val (summaryText, summaryOffline) = fetchCached(context, "summary.json")
    val (cellsText, cellsOffline) = fetchCached(context, "risk_${d.key}.geojson")
    val summary = JSONObject(summaryText)
    val area = summary.getJSONObject("areas").optJSONObject(d.key)?.let(::parseSummary)
    return MlForecast(summary.getString("generated"), area, parseCells(cellsText), summaryOffline || cellsOffline)
}

private fun parseSummary(it: JSONObject) = AreaSummary(
    it.getDouble("max_prob"), it.getInt("cells_at_risk"), it.getInt("people_exposed"),
    // opt*: older cached summaries don't have the asset fields yet.
    it.optInt("cropland_ha_exposed"), it.optInt("buildings_exposed"),
)

private fun parseCells(geojson: String): List<RiskCell> {
    val features = JSONObject(geojson).getJSONArray("features")
    return List(features.length()) { i ->
        val f = features.getJSONObject(i)
        val coords = f.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
        val props = f.getJSONObject("properties")
        RiskCell(
            // GeoJSON is [lon, lat]; keep (lat, lon) for the map.
            ring = List(coords.length()) { j -> coords.getJSONArray(j).let { it.getDouble(1) to it.getDouble(0) } },
            prob = props.getDouble("prob"),
            people = props.getInt("people"),
            peakDate = props.optString("peak_date"),
        )
    }
}

// The Aug 2024 Feni flood run day by day through the same model on observed flow and rain (ml/replay.py).
private const val REPLAY_DIR = "replay/feni_2024/"

data class ReplayDay(val date: String, val summary: AreaSummary)

fun fetchReplayTimeline(context: Context): List<ReplayDay> {
    val days = JSONObject(fetchCached(context, REPLAY_DIR + "timeline.json").first).getJSONArray("days")
    return List(days.length()) { i -> days.getJSONObject(i).let { ReplayDay(it.getString("date"), parseSummary(it)) } }
}

fun fetchReplayCells(context: Context, date: String): List<RiskCell> =
    parseCells(fetchCached(context, "$REPLAY_DIR$date.geojson").first)

/** The model's flood-proneness per cell (terrain only, same every day); `prob` holds the susceptibility. */
fun fetchSusceptibility(context: Context, d: District): List<RiskCell> =
    parseCells(fetchCached(context, "susceptibility_${d.key}.geojson").first)

/** Held-out scores and permutation importance from ml/train.py. */
data class ModelInfo(val auc: Double, val precision: Double, val recall: Double, val importance: List<Pair<String, Double>>)

fun fetchModelInfo(context: Context): ModelInfo {
    val m = JSONObject(fetchCached(context, "metrics.json").first)
    val imp = m.getJSONObject("importance")
    return ModelInfo(
        m.getDouble("roc_auc"), m.getDouble("precision@0.5"), m.getDouble("recall@0.5"),
        imp.keys().asSequence().map { it to imp.getDouble(it) }.sortedByDescending { it.second }.toList(),
    )
}
