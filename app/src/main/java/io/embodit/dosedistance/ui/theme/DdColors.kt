package io.embodit.dosedistance.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import com.pico.spatial.ui.design.PicoTheme
import io.embodit.dosedistance.domain.Heat
import io.embodit.dosedistance.domain.Zone

/**
 * The design's exact colours (design-package `palette`): radiation yellow, lead grey, safe green. This is
 * the only file with colour literals; the four semantic roles go into `PicoTheme`, the brand tokens are
 * used directly (3D materials, the solid faces of plates and the reading capsule).
 */
object DdColors {
    // ---- ColorScheme roles with a fixed design value (the other twelve inherit SpatialUI Vibrant)
    val Error = Color(0xFFFF5F52) // design-style: fixed-figma-color error: extreme, at the source
    val Alert = Color(0xFFFFA62B) // design-style: fixed-figma-color alert: high zone
    val Passable = Color(0xFF5FD38A) // design-style: fixed-figma-color passable: low zone, safe shadow
    val Interaction = Color(0xFFFFC72C) // design-style: fixed-figma-color interaction: medium zone, selected, spot

    // ---- brand tokens outside ColorScheme
    val RadYellow = Color(0xFFF2C230) // design-style: fixed-figma-color radYellow: pot band, app icon
    val SourceMagenta = Color(0xFFD63384) // design-style: fixed-figma-color sourceMagenta: source core
    val Trefoil = Color(0xFF1B1B1B) // design-style: fixed-figma-color trefoil black
    val LeadGrey = Color(0xFF6B7078) // design-style: fixed-figma-color leadGrey: pot, shield, shield plate
    val GlassBlue = Color(0xFF9FB8C8) // design-style: fixed-figma-color glassBlue: lead glass window (35 %)
    val BaseGrey = Color(0xFF3A3D42) // design-style: fixed-figma-color baseGrey: base, posts, pot opening
    val StripeInk = Color(0xFF16181C) // design-style: fixed-figma-color stripeInk: content on semantic fills
    val PlateInk = Color(0xF0121417) // design-style: fixed-figma-color plateInk 94 %: source plate
    val HintInk = Color(0xF5121417) // design-style: fixed-figma-color hintInk 96 %: snackbar, result card
    val StaleGrey = Color(0xFF9A9A96) // design-style: fixed-figma-color staleGrey: background zone, tracking lost
    val White = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val OnInkSecondary = Color(0xA8FFFFFF) // design-style: fixed-figma-color onInkSecondary white 66 %
    val OnInkFill = Color(0x24FFFFFF) // design-style: fixed-figma-color onInkFill white 14 %

    /** Zone colours (design `model.zones`): only for "how high is the dose rate". */
    fun ofZone(z: Zone): Color =
        when (z) {
            Zone.BACKGROUND -> StaleGrey
            Zone.LOW -> Passable
            Zone.MID -> Interaction
            Zone.HIGH -> Alert
            Zone.EXTREME -> Error
        }

    /**
     * The floor map's colour for a level 0..1 (domain `Heat.level`, log 0.5 - 1000 µSv/h): passable green at 0.5,
     * interaction yellow at 10, error red at 100, source magenta at 1000. The zone's alert orange is left out: next
     * to the yellow it was the same hue on the floor (emulator 2026-10-26), one colour per decade reads at a glance.
     */
    fun heat(level: Float): Color {
        val stops = listOf(0f to Passable, Heat.level(10f) to Interaction, Heat.level(100f) to Error, 1f to SourceMagenta)
        val t = level.coerceIn(0f, 1f)
        for (i in 0 until stops.size - 1) {
            val (a, ca) = stops[i]
            val (b, cb) = stops[i + 1]
            if (t <= b) {
                val k = if (b > a) (t - a) / (b - a) else 0f
                return Color(ca.red + (cb.red - ca.red) * k, ca.green + (cb.green - ca.green) * k, ca.blue + (cb.blue - ca.blue) * k, 1f)
            }
        }
        return Error
    }
}

/** Type roles of the design (`typography.roles`): tabular figures everywhere a number is shown. */
object DdType {
    val reading: TextStyle
        @Composable get() = PicoTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val headline: TextStyle
        @Composable get() = PicoTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)

    val title: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val label: TextStyle
        @Composable get() = PicoTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val body: TextStyle
        @Composable get() = PicoTheme.typography.bodyLargeMultiline.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)

    val small: TextStyle
        @Composable get() = PicoTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum, lnum")
}
