package com.paniuthche.app

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

fun nearestDistrict(lat: Double, lon: Double): District = districts.minBy { distanceKm(lat, lon, it.lat, it.lon) }

/** Ray casting; ring is (lat, lon) pairs. */
fun pointInRing(lat: Double, lon: Double, ring: List<Pair<Double, Double>>): Boolean {
    var inside = false
    var j = ring.lastIndex
    for (i in ring.indices) {
        val (yi, xi) = ring[i]
        val (yj, xj) = ring[j]
        if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

fun cellAt(lat: Double, lon: Double, cells: List<RiskCell>): RiskCell? = cells.firstOrNull { pointInRing(lat, lon, it.ring) }

/** Risk of the cell containing the point; cells under 10% are not published, so 0 means "below 10%". */
fun riskAt(lat: Double, lon: Double, cells: List<RiskCell>): Double = cellAt(lat, lon, cells)?.prob ?: 0.0

enum class AlertLevel { NONE, MEDIUM, HIGH }

fun alertLevel(prob: Double): AlertLevel = when {
    prob >= 0.5 -> AlertLevel.HIGH
    prob >= 0.3 -> AlertLevel.MEDIUM
    else -> AlertLevel.NONE
}

/** Notify only when risk rises above the last level alerted, so a steady risk doesn't repeat. */
fun shouldNotify(level: AlertLevel, lastAlerted: AlertLevel): Boolean = level > lastAlerted
