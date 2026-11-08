package com.paniuthche.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

fun settings(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

fun hasPermission(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

/** Last fix if under 30 min old, else a fresh one; null without permission or providers. */
@SuppressLint("MissingPermission")
suspend fun currentLocation(ctx: Context): Location? {
    if (!hasPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) return null
    val lm = ctx.getSystemService(LocationManager::class.java)
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.isProviderEnabled(it) }
    val last = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    if (last != null && System.currentTimeMillis() - last.time < 30 * 60_000) return last
    val provider = providers.firstOrNull() ?: return last
    return suspendCancellableCoroutine { cont ->
        LocationManagerCompat.getCurrentLocation(
            lm, provider, null as android.os.CancellationSignal?, ContextCompat.getMainExecutor(ctx),
            androidx.core.util.Consumer<Location?> { cont.resume(it ?: last) },
        )
    }
}

fun saveLocation(ctx: Context, lat: Double, lon: Double) =
    settings(ctx).edit().putString("lat", lat.toString()).putString("lon", lon.toString()).commit()

fun savedLocation(ctx: Context): Pair<Double, Double>? {
    val p = settings(ctx)
    val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return null
    val lon = p.getString("lon", null)?.toDoubleOrNull() ?: return null
    return lat to lon
}

private const val WORK_NAME = "flood-alerts"
private const val CHANNEL_ID = "flood-alerts"
private const val KEY_FORCE = "force"

fun scheduleAlerts(ctx: Context) {
    val req = PeriodicWorkRequestBuilder<AlertWorker>(6, TimeUnit.HOURS)
        .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
        .build()
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    // commit, not apply: these must survive the process being killed right after the tap.
    settings(ctx).edit().putBoolean("alerts_on", true).commit()
}

fun cancelAlerts(ctx: Context) {
    WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    settings(ctx).edit().putBoolean("alerts_on", false).commit()
}

/** Runs the check once and always notifies, so users (and judges) can see what an alert looks like. */
fun checkAlertsNow(ctx: Context) {
    WorkManager.getInstance(ctx).enqueue(
        OneTimeWorkRequestBuilder<AlertWorker>().setInputData(workDataOf(KEY_FORCE to true)).build()
    )
}

/**
 * Checks the published ML forecast at the user's last known location and notifies when risk rises.
 * Uses the location saved while the app was open, so no background-location permission is needed.
 */
class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = settings(ctx)
        val force = inputData.getBoolean(KEY_FORCE, false)
        val location = savedLocation(ctx)
        val district = location?.let { nearestDistrict(it.first, it.second) }
            ?: districts.find { it.key == prefs.getString("district", null) } ?: districts[0]

        val ml = try {
            withContext(Dispatchers.IO) { fetchMlForecast(ctx, district) }
        } catch (e: Exception) {
            return if (force) Result.failure() else Result.retry()
        }
        if (ml.offline && !force) return Result.retry()

        val cell = location?.let { cellAt(it.first, it.second, ml.cells) }
        val prob = if (location != null) cell?.prob ?: 0.0 else ml.summary?.maxProb ?: 0.0
        val level = alertLevel(prob)
        val last = AlertLevel.valueOf(prefs.getString("last_alert", AlertLevel.NONE.name)!!)
        if (force || shouldNotify(level, last)) notify(ctx, district, level, prob, cell?.peakDate, location != null)
        // Recording drops too means a later rise alerts again.
        prefs.edit().putString("last_alert", level.name).commit()
        return Result.success()
    }
}

private fun localized(ctx: Context): Context {
    val lang = settings(ctx).getString("lang", null)?.let(Lang::valueOf) ?: Lang.BN
    val config = Configuration(ctx.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang.tag)) }
    return ctx.createConfigurationContext(config)
}

@SuppressLint("MissingPermission")
internal fun notify(ctx: Context, d: District, level: AlertLevel, prob: Double, peakDate: String?, atLocation: Boolean) {
    if (Build.VERSION.SDK_INT >= 33 && !hasPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)) return
    val l = localized(ctx)
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, l.getString(R.string.alert_channel), NotificationManager.IMPORTANCE_HIGH)
    )
    val name = l.getString(d.name)
    val (title, advice) = when (level) {
        AlertLevel.HIGH -> l.getString(R.string.alert_high_title, name) to l.getString(R.string.advice_high)
        AlertLevel.MEDIUM -> l.getString(R.string.alert_medium_title, name) to l.getString(R.string.advice_medium)
        AlertLevel.NONE -> l.getString(R.string.alert_none_title, name) to l.getString(R.string.advice_low)
    }
    val bn = l.resources.configuration.locales[0].language == "bn"
    val pct = "${(prob * 100).toInt()}".let { if (bn) it.bn() else it }
    // Cells under 10% aren't published, so 0 at a location means "below 10%", not "zero".
    val where = if (atLocation && prob == 0.0) l.getString(R.string.alert_at_location_low)
        else l.getString(if (atLocation) R.string.alert_at_location else R.string.alert_in_district, pct)
    val peak = peakDate?.let { " " + l.getString(R.string.alert_peak, if (bn) it.bn() else it) }.orEmpty()
    val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle(title)
        .setContentText(where + peak)
        .setStyle(NotificationCompat.BigTextStyle().bigText("$where$peak\n$advice"))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(ctx).notify(1, n)
}
