package com.paniuthche.app

import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

data class District(@StringRes val name: Int, val lat: Double, val lon: Double)

// Pilot districts on major flood-prone rivers.
val districts = listOf(
    District(R.string.district_sylhet, 24.90, 91.87),
    District(R.string.district_sunamganj, 25.07, 91.40),
    District(R.string.district_kurigram, 25.81, 89.64),
    District(R.string.district_feni, 23.01, 91.40),
    District(R.string.district_sirajganj, 24.45, 89.70),
)

enum class Risk { LOW, MEDIUM, HIGH }

data class Forecast(
    val days: List<String>,
    val discharge: List<Double>,
    val todayIndex: Int,
    val risk: Risk,
    val peakDay: String,
    val peakDischarge: Double,
    /** Median flow over the past 90 days, the "normal" the risk is measured against. */
    val normal: Double,
)

fun median(v: List<Double>): Double = if (v.isEmpty()) 0.0 else v.sorted()[v.size / 2]

/**
 * Risk = forecast peak over the next 7 days compared with the median of the recent past.
 * shortcut: relative-to-recent baseline, not historical return periods; upgrade to GloFAS
 * return-period thresholds before anyone relies on these alerts.
 */
fun riskLevel(past: List<Double>, next7: List<Double>): Risk {
    if (past.isEmpty() || next7.isEmpty()) return Risk.LOW
    val normal = median(past)
    if (normal <= 0.0) return Risk.LOW
    val ratio = next7.max() / normal
    return when {
        ratio >= 2.0 -> Risk.HIGH
        ratio >= 1.5 -> Risk.MEDIUM
        else -> Risk.LOW
    }
}

private const val PAST_DAYS = 90

// GloFAS cells are ~5 km; a town's own cell is often a tiny stream, so search ±0.1° for the main river.
private val GRID_OFFSETS = listOf(-0.1, -0.05, 0.0, 0.05, 0.1)

// Blocking network call; run off the main thread.
fun fetchForecast(d: District): Forecast {
    val points = GRID_OFFSETS.flatMap { dLat -> GRID_OFFSETS.map { dLon -> d.lat + dLat to d.lon + dLon } }
    val url = "https://flood-api.open-meteo.com/v1/flood" +
        "?latitude=${points.joinToString(",") { "%.3f".format(java.util.Locale.US, it.first) }}" +
        "&longitude=${points.joinToString(",") { "%.3f".format(java.util.Locale.US, it.second) }}" +
        "&daily=river_discharge&past_days=$PAST_DAYS&forecast_days=16"
    val cells = JSONArray(URL(url).readText())
    val series = List(cells.length()) { cells.getJSONObject(it).getJSONObject("daily") }
    // Missing values come back as null; treat as 0 so the chart stays aligned.
    fun values(daily: JSONObject) = daily.getJSONArray("river_discharge").let { a ->
        List(a.length()) { a.optDouble(it, 0.0).takeUnless(Double::isNaN) ?: 0.0 }
    }
    val daily = series.maxBy { median(values(it)) }
    val times = daily.getJSONArray("time")
    val days = List(times.length()) { times.getString(it) }
    val discharge = values(daily)

    val today = PAST_DAYS.coerceAtMost(days.lastIndex)
    val next7 = discharge.subList(today, (today + 7).coerceAtMost(discharge.size))
    val peak = today + next7.indices.maxBy { next7[it] }
    return Forecast(
        days = days,
        discharge = discharge,
        todayIndex = today,
        risk = riskLevel(discharge.subList(0, today), next7),
        peakDay = days[peak],
        peakDischarge = discharge[peak],
        normal = median(discharge.subList(0, today)),
    )
}
