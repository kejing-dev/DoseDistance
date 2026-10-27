package io.embodit.dosedistance.ui.dose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.dosedistance.R
import io.embodit.dosedistance.domain.Dose
import io.embodit.dosedistance.domain.Language
import io.embodit.dosedistance.domain.Rings
import io.embodit.dosedistance.ui.dose.DoseEvent
import io.embodit.dosedistance.ui.dose.DoseUiState
import io.embodit.dosedistance.ui.dose.DrillKind
import io.embodit.dosedistance.ui.dose.DrillPhase
import io.embodit.dosedistance.ui.dose.FailWhy
import io.embodit.dosedistance.ui.dose.SnackKind
import io.embodit.dosedistance.ui.dose.SnackUi
import io.embodit.dosedistance.ui.dose.debug.probe
import io.embodit.dosedistance.ui.dose.scene.PanelIds
import io.embodit.dosedistance.ui.theme.DdColors
import io.embodit.dosedistance.ui.theme.DdType

private val MenuRowH = 44.dp

/** The More menu (design `panels.menu`, 300 x 296): Geiger and rings switches, language segment, About, Clear all, Exit. */
@Composable
internal fun MoreMenu(state: DoseUiState?, onEvent: (DoseEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.MENU_W.dp, PanelSizes.MENU_H.dp)
                .clip(RoundedCornerShape(24.dp))
                .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (state == null) return@Column
        val ink = PicoTheme.colorScheme.labelPrimary
        SwitchRow("menu_geiger", rs(R.string.menu_geiger), state.settings.geiger) { onEvent(DoseEvent.GeigerSet(it)) }
        SwitchRow("menu_rings", rs(R.string.menu_rings), state.settings.rings) { onEvent(DoseEvent.RingsSet(it)) }
        Row(modifier = Modifier.fillMaxWidth().height(MenuRowH), verticalAlignment = Alignment.CenterVertically) {
            Text(text = rs(R.string.menu_language), color = ink, style = DdType.title, maxLines = 1, modifier = Modifier.weight(1f))
            Segments(items = Language.entries, selected = state.settings.language, width = 136.dp, enabled = true, name = { "lang_${it.tag}" }, onSelect = { onEvent(DoseEvent.LanguageSelected(it)) }, height = 44.dp) { item ->
                Text(text = rs(if (item == Language.ZH) R.string.lang_zh else R.string.lang_en), style = DdType.label, maxLines = 1, softWrap = false)
            }
        }
        MenuButton("menu_about", R.drawable.ic_info, rs(R.string.menu_about), true) { onEvent(DoseEvent.AboutClicked) }
        MenuButton("menu_clear", R.drawable.ic_trash, rs(R.string.menu_clear), !state.drillRunning && (state.source != null || state.shields.isNotEmpty())) { onEvent(DoseEvent.ClearAllClicked) }
        MenuButton("menu_exit", R.drawable.ic_exit, rs(R.string.menu_exit), true) { onEvent(DoseEvent.ExitClicked) }
    }
}

@Composable
private fun SwitchRow(name: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(MenuRowH), verticalAlignment = Alignment.CenterVertically) {
        Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = DdType.title, maxLines = 1, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.probe(name))
    }
}

@Composable
private fun MenuButton(name: String, icon: Int, text: String, enabled: Boolean, onClick: () -> Unit) {
    val ink = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    WordButton(name = name, icon = icon, text = text, container = PicoTheme.colorScheme.fillTertiary, content = ink, enabled = enabled, height = 40.dp, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), onClick = onClick)
}

/**
 * The three dialogs (design `panels.dialog`, 400 x 300, glass): clear confirmation (S23), spatial features
 * unavailable (S20, wins) and About (S25).
 */
@Composable
internal fun DialogPanel(state: DoseUiState?, onEvent: (DoseEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.DIALOG_W.dp, PanelSizes.DIALOG_H.dp)
                .clip(RoundedCornerShape(32.dp))
                .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state == null || !state.dialogOpen) return@Column
        val ink = PicoTheme.colorScheme.labelPrimary
        val kind = when { state.blocked -> 0; state.clearDialog -> 1; else -> 2 }
        val icon = when (kind) { 0 -> R.drawable.ic_unavailable; 1 -> R.drawable.ic_trash; else -> R.drawable.ic_info }
        val title = rs(when (kind) { 0 -> R.string.blocked_title; 1 -> R.string.clear_title; else -> R.string.about_title })
        val body = rs(when (kind) { 0 -> R.string.blocked_body; 1 -> R.string.clear_body; else -> R.string.about_body })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp), tint = ink)
            Text(text = title, color = ink, style = DdType.headline, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).probe("dialog_title"))
        }
        Text(text = body, color = PicoTheme.colorScheme.labelSecondary, style = DdType.body, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).probe("dialog_body"))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            when (kind) {
                0 -> {
                    WordButton("blocked_exit", R.drawable.ic_exit, rs(R.string.btn_exit), PicoTheme.colorScheme.fillTertiary, ink) { onEvent(DoseEvent.BlockedExit) }
                    WordButton("blocked_retry", R.drawable.ic_retry, rs(R.string.btn_retry), PicoTheme.colorScheme.interaction, DdColors.StripeInk) { onEvent(DoseEvent.BlockedRetry) }
                }
                1 -> {
                    WordButton("dialog_cancel", R.drawable.ic_close, rs(R.string.btn_cancel), PicoTheme.colorScheme.fillTertiary, ink) { onEvent(DoseEvent.DialogDismissed) }
                    WordButton("dialog_confirm", R.drawable.ic_trash, rs(R.string.btn_clear), PicoTheme.colorScheme.error, DdColors.StripeInk) { onEvent(DoseEvent.ClearConfirmed) }
                }
                else -> WordButton("dialog_ok", R.drawable.ic_check, rs(R.string.btn_got_it), PicoTheme.colorScheme.interaction, DdColors.StripeInk) { onEvent(DoseEvent.DialogDismissed) }
            }
        }
    }
}

/** The snackbar (design `panels.snackbar`, 360 x 56): opaque hintInk capsule, icon + one short sentence. */
@Composable
internal fun SnackPanel(snack: SnackUi?) {
    Box(modifier = Modifier.size(PanelSizes.SNACK_W.dp, PanelSizes.SNACK_H.dp), contentAlignment = Alignment.Center) {
        if (snack == null) return@Box
        val (icon, text) =
            when (snack.kind) {
                SnackKind.SCANNING -> 0 to rs(R.string.scanning)
                SnackKind.SHIELDS_FULL -> R.drawable.ic_warning to rs(R.string.snack_shields_full)
                SnackKind.SNAPPED -> R.drawable.ic_check to rs(R.string.snack_snapped)
                SnackKind.LOST -> R.drawable.ic_unavailable to rs(R.string.snack_lost)
                SnackKind.CLEARED -> R.drawable.ic_check to rs(R.string.snack_cleared)
                SnackKind.BACK_TO_SPOT -> R.drawable.ic_spot to rs(R.string.snack_back_to_spot)
                SnackKind.WALK_TO_SPOT -> R.drawable.ic_spot to rs(R.string.snack_walk_to_spot)
            }
        val tint = if (snack.kind == SnackKind.SHIELDS_FULL || snack.kind == SnackKind.BACK_TO_SPOT) PicoTheme.colorScheme.alert else DdColors.White
        Row(
            modifier = Modifier.widthIn(min = 200.dp, max = PanelSizes.SNACK_W.dp).height(52.dp).clip(RoundedCornerShape(26.dp)).background(DdColors.HintInk).probe("snackbar").padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon == 0) Spinner(20.dp, DdColors.White) else Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
            Text(text = text, color = DdColors.White, style = DdType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The one-time coachmark above the source (design `panels.coach`, 360 x 160): footsteps, two lines, tick. */
@Composable
internal fun CoachPanel(state: DoseUiState?, onEvent: (DoseEvent) -> Unit) {
    Row(
        modifier =
            Modifier
                .size(PanelSizes.COACH_W.dp, PanelSizes.COACH_H.dp)
                .clip(RoundedCornerShape(24.dp))
                .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state == null) return@Row
        val ink = PicoTheme.colorScheme.labelPrimary
        Icon(painter = painterResource(R.drawable.ic_footsteps), contentDescription = null, modifier = Modifier.size(40.dp), tint = ink)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = rs(R.string.coach_line1), color = ink, style = DdType.body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("coach_line1"))
            Text(text = rs(R.string.coach_line2), color = ink, style = DdType.body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("coach_line2"))
        }
        Key(name = "coach_ok", icon = R.drawable.ic_check, description = rs(R.string.btn_got_it), enabled = true, size = 44.dp, container = PicoTheme.colorScheme.interaction, content = DdColors.StripeInk) { onEvent(DoseEvent.CoachDismissed) }
    }
}

/** The drill result card (design `panels.drillResult`, 360 x 220, opaque hintInk). */
@Composable
internal fun ResultCard(state: DoseUiState?, onEvent: (DoseEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.RESULT_W.dp, PanelSizes.RESULT_H.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(DdColors.HintInk)
                .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val d = state?.drill ?: return@Column
        if (d.running) return@Column
        val passed = d.phase == DrillPhase.PASSED
        val white = DdColors.White
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(if (passed) PicoTheme.colorScheme.passable else PicoTheme.colorScheme.error), contentAlignment = Alignment.Center) {
                Icon(painter = painterResource(if (passed) R.drawable.ic_check else R.drawable.ic_close), contentDescription = null, modifier = Modifier.size(22.dp), tint = DdColors.StripeInk)
            }
            val title = rs(when { passed -> R.string.result_pass; d.failWhy == FailWhy.TIMEOUT -> R.string.result_timeout; else -> R.string.result_over_budget })
            Text(text = title, color = white, style = DdType.headline, maxLines = 1, modifier = Modifier.probe("result_title"))
        }
        val lines: List<String> =
            if (passed) {
                when (d.kind) {
                    DrillKind.DISTANCE -> listOf(rs(R.string.line_distance, (d.elapsedMs / 1000L).toInt(), Dose.rateText(d.hStart), Dose.rateText(d.resultH)))
                    DrillKind.TIME -> listOf(rs(R.string.line_time, (d.elapsedMs / 1000L).toInt(), Dose.sig2(d.cum), oneDecimal(d.closestD)))
                    DrillKind.SHIELD -> listOf(rs(R.string.line_shield_layers, oneDecimal(Dose.layers(d.resultA))), rs(R.string.line_shield_drop, Dose.rateText(d.hStart), Dose.rateText(d.resultH)))
                }
            } else {
                listOf(
                    when {
                        d.failWhy == FailWhy.TIMEOUT -> rs(R.string.line_not_read)
                        d.kind == DrillKind.TIME && d.read -> rs(R.string.line_over_stay, d.overStaySec)
                        d.kind == DrillKind.SHIELD -> rs(R.string.line_too_long)
                        else -> rs(R.string.line_over_budget_at, oneDecimal(d.failD))
                    }
                )
            }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for ((i, l) in lines.withIndex()) Text(text = l, color = white, style = DdType.body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("result_line$i"))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            WordButton("result_again", R.drawable.ic_retry, rs(R.string.btn_again), DdColors.OnInkFill, white) { onEvent(DoseEvent.ResultAgain) }
            if (passed) {
                val last = d.kind == DrillKind.SHIELD
                WordButton("result_next", null, rs(if (last) R.string.btn_done else R.string.btn_next), PicoTheme.colorScheme.interaction, DdColors.StripeInk) { onEvent(DoseEvent.ResultNext) }
            } else {
                WordButton("result_later", null, rs(R.string.btn_later), PicoTheme.colorScheme.interaction, DdColors.StripeInk) { onEvent(DoseEvent.ResultLater) }
            }
        }
    }
}

/**
 * A plate riding on a 3D thing (design `panels.plates`, pill 40 high, 120 - 240 wide): source plate
 * (plateInk), shield plates (leadGrey), ring plates (zone colour, stripeInk text), the drill 3 spot plate.
 */
@Composable
internal fun Plate(id: String, state: DoseUiState?) {
    Box(modifier = Modifier.size(PanelSizes.PLATE_W.dp, PanelSizes.PLATE_H.dp), contentAlignment = Alignment.Center) {
        val src = state?.source ?: return@Box
        val iso = state.isotope
        val floorY = state.floorY ?: return@Box
        val (text, bg, fg) =
            when {
                id == PanelIds.PLATE_SOURCE -> Triple(rs(R.string.source_plate, isoSymbol(iso), whole(iso.h1)), DdColors.PlateInk, DdColors.White)
                id.startsWith("plateShield") -> {
                    val sid = id.removePrefix("plateShield").toIntOrNull()
                    val s = state.visibleShields.firstOrNull { it.id == sid } ?: return@Box
                    Triple(rs(R.string.shield_plate, s.tMm, Dose.blockedText(Dose.factor(iso, s.tMm))), DdColors.LeadGrey, DdColors.White)
                }
                id.startsWith("plateRing") -> {
                    val i = id.removePrefix("plateRing").toIntOrNull() ?: return@Box
                    val r = Rings.RADII.getOrNull(i) ?: return@Box
                    val headY = state.head?.y ?: (src.core.y + 0.85f)
                    val reading = Rings.rateAt(iso, src.core, src.frame.floorPoint(r, 0f), headY, state.visibleShields, floorY)
                    Triple(rs(R.string.ring_plate, whole(r), Dose.rateText(reading.h)), DdColors.ofZone(reading.zone), DdColors.StripeInk)
                }
                id == PanelIds.PLATE_SPOT -> Triple(rs(R.string.spot_plate), PicoTheme.colorScheme.interaction, DdColors.StripeInk)
                else -> return@Box
            }
        val stale = state.trackingLost
        Row(
            modifier =
                Modifier
                    .height(34.dp)
                    .widthIn(min = 120.dp, max = PanelSizes.PLATE_W.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .border(3.dp, DdColors.White.copy(alpha = 0.3f), RoundedCornerShape(17.dp))
                    .background(if (stale) DdColors.StaleGrey else bg)
                    .padding(horizontal = 14.dp)
                    .probe("plate"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, color = if (stale) DdColors.StripeInk else fg, style = DdType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

