package com.paniuthche.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import java.util.Locale

private val Light = lightColorScheme(
    primary = Color(0xFF0B5FA5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF00796B),
    secondaryContainer = Color(0xFFD3E4FF),
    onSecondaryContainer = Color(0xFF001C38),
    background = Color(0xFFF6F9FC),
    surface = Color(0xFFF6F9FC),
    surfaceContainer = Color(0xFFEAF1F8),
    error = Color(0xFFC62828),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA2C9FF),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF004882),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFF80CBC4),
    secondaryContainer = Color(0xFF004882),
    onSecondaryContainer = Color(0xFFD3E4FF),
    background = Color(0xFF0F1418),
    surface = Color(0xFF0F1418),
    surfaceContainer = Color(0xFF1B2228),
    error = Color(0xFFFF8A80),
)

object RiskColors {
    val low = Color(0xFF2E7D32)
    val medium = Color(0xFFEF6C00)
    val high = Color(0xFFC62828)
}

@Composable
fun PaniTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)

enum class Lang(val tag: String) { BN("bn"), EN("en") }

val LocalLang = staticCompositionLocalOf { Lang.BN }

/**
 * Applies the in-app language to everything below: stringResource reads LocalContext's resources,
 * so swapping in a context with the chosen locale switches the whole UI without recreating the activity.
 */
@Composable
fun LocalizedContent(lang: Lang, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val ctx = remember(lang) {
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang.tag)) }
        base.createConfigurationContext(config)
    }
    CompositionLocalProvider(
        LocalContext provides ctx,
        LocalConfiguration provides ctx.resources.configuration,
        LocalLang provides lang,
        content = content,
    )
}

/** Latin digits to Bangla digits for display. */
fun String.bn(): String = map { if (it in '0'..'9') '০' + (it - '0') else it }.joinToString("")

/** Digits in the current language. */
@Composable
fun String.loc(): String = if (LocalLang.current == Lang.BN) bn() else this

/** "2026-11-07" to "৭ নভেম্বর" / "7 November". */
@Composable
fun locDate(iso: String): String {
    val months = stringArrayResource(R.array.months)
    val parts = iso.split("-")
    val month = parts.getOrNull(1)?.toIntOrNull() ?: return iso
    val day = parts.getOrNull(2)?.toIntOrNull() ?: return iso
    return "${day.toString().loc()} ${months[month - 1]}"
}
