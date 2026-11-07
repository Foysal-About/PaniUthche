package com.paniuthche.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL

// Published twice daily by .github/workflows/forecast.yml (ml/forecast.py).
private const val ML_BASE_URL = "https://foysal-about.github.io/PaniUthche/"

data class AreaSummary(val maxProb: Double, val cellsAtRisk: Int, val peopleExposed: Int)

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
    val file = File(context.filesDir, "ml_$name")
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
    val area = summary.getJSONObject("areas").optJSONObject(d.key)?.let {
        AreaSummary(it.getDouble("max_prob"), it.getInt("cells_at_risk"), it.getInt("people_exposed"))
    }
    val features = JSONObject(cellsText).getJSONArray("features")
    val cells = List(features.length()) { i ->
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
    return MlForecast(summary.getString("generated"), area, cells, summaryOffline || cellsOffline)
}
