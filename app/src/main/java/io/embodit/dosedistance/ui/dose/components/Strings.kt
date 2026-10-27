package io.embodit.dosedistance.ui.dose.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.dosedistance.R
import io.embodit.dosedistance.domain.Isotope
import io.embodit.dosedistance.domain.Language
import io.embodit.dosedistance.domain.Zone
import io.embodit.dosedistance.ui.dose.DrillKind
import java.util.Locale

/**
 * Copy for the language chosen in the app (中文 / EN), switched at run time without restarting the
 * Stage: the screen provides a Resources object for that locale and every panel reads its strings from it
 * (Spatial Ruler). `LocalContext` is not overridden.
 */
val LocalDdResources = staticCompositionLocalOf<Resources> { error("LocalDdResources not provided") }

fun localizedResources(context: Context, language: Language): Resources {
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(if (language == Language.ZH) "zh-CN" else "en"))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalDdResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalDdResources.current.getString(id, *args)

/** Isotope name with the Chinese element name in the Chinese UI (design `i18n`). */
@Composable
@ReadOnlyComposable
fun isoName(iso: Isotope): String = rs(when (iso) { Isotope.TC99M -> R.string.iso_tc99m; Isotope.CS137 -> R.string.iso_cs137; Isotope.CO60 -> R.string.iso_co60 })

/** The international symbol only (segments, plates). */
fun isoSymbol(iso: Isotope): String = when (iso) { Isotope.TC99M -> "Tc-99m"; Isotope.CS137 -> "Cs-137"; Isotope.CO60 -> "Co-60" }

@Composable
@ReadOnlyComposable
fun zoneName(z: Zone): String = rs(when (z) { Zone.BACKGROUND -> R.string.zone_background; Zone.LOW -> R.string.zone_low; Zone.MID -> R.string.zone_mid; Zone.HIGH -> R.string.zone_high; Zone.EXTREME -> R.string.zone_extreme })

@Composable
@ReadOnlyComposable
fun drillName(k: DrillKind): String = rs(when (k) { DrillKind.DISTANCE -> R.string.drill_distance; DrillKind.TIME -> R.string.drill_time; DrillKind.SHIELD -> R.string.drill_shield })

@Composable
@ReadOnlyComposable
fun drillGoal(k: DrillKind): String = rs(when (k) { DrillKind.DISTANCE -> R.string.goal_distance; DrillKind.TIME -> R.string.goal_time; DrillKind.SHIELD -> R.string.goal_shield })

/** "6.5" / "12" / "0.3" (half-value layers as written in the design). */
fun mm(v: Float): String = if (v == v.toInt().toFloat()) "${v.toInt()}" else String.format(Locale.US, "%.1f", v)

/** "93" / "350" / "20" (1 m rates). */
fun whole(v: Float): String = "${v.toInt()}"

fun oneDecimal(v: Float): String = String.format(Locale.US, "%.1f", v)

/** Panel sizes (design-package `panels[].sizeDp`), in dp. */
object PanelSizes {
    const val DOSIMETER_W = 400f
    // v0.1.1: 444 -> 500, the shield page lists the placed shields
    const val DOSIMETER_H = 500f
    const val COMPACT_H = 120f
    const val INNER_W = 368f
    const val MENU_W = 300f
    // v0.1.1: 296 -> 388, floor map and shield snap switches
    const val MENU_H = 388f
    const val DIALOG_W = 400f
    const val DIALOG_H = 300f
    const val SNACK_W = 360f
    const val SNACK_H = 56f
    const val COACH_W = 360f
    const val COACH_H = 160f
    const val RESULT_W = 360f
    const val RESULT_H = 220f
    const val PLATE_W = 240f
    const val PLATE_H = 40f
    const val CONTENT_H = 200f

    /** Disabled content (SpatialUI's own disabled alpha). */
    const val DISABLED_ALPHA = 0.38f
}
