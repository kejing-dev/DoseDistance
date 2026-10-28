package io.embodit.dosedistance.ui.dose.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.dosedistance.R
import io.embodit.dosedistance.domain.AimSource
import io.embodit.dosedistance.domain.Dose
import io.embodit.dosedistance.domain.Heat
import io.embodit.dosedistance.domain.Isotope
import io.embodit.dosedistance.domain.Shield
import io.embodit.dosedistance.domain.Zone
import io.embodit.dosedistance.ui.dose.Aiming
import io.embodit.dosedistance.ui.dose.DoseEvent
import io.embodit.dosedistance.ui.dose.DoseUiState
import io.embodit.dosedistance.ui.dose.DrillKind
import io.embodit.dosedistance.ui.dose.DrillUi
import io.embodit.dosedistance.ui.dose.Phase
import io.embodit.dosedistance.ui.dose.Tab
import io.embodit.dosedistance.ui.dose.debug.probe
import io.embodit.dosedistance.ui.theme.DdColors
import io.embodit.dosedistance.ui.theme.DdType
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * The dosimeter (design `panels.dosimeter`, 400 x 444 dp at 0.75): nameplate, the reading capsule, the
 * zone bar, three chips, the Source · Shield · Drills segment, a 144 dp content page and the resident
 * footer 「教学近似，非剂量评估」. Glass root (Material.Regular).
 */
@Composable
internal fun DosimeterPanel(state: DoseUiState?, onEvent: (DoseEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.DOSIMETER_W.dp, PanelSizes.DOSIMETER_H.dp)
                .clip(RoundedCornerShape(28.dp))
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .padding(16.dp),
    ) {
        if (state == null) return@Column
        val lost = state.trackingLost
        val ink = PicoTheme.colorScheme.labelPrimary
        val reading = state.reading
        val tooClose = reading?.tooClose == true && !lost
        // row 1: nameplate (or the "at the source" capsule) · more
        Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tooClose) {
                Row(
                    modifier = Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(18.dp)).background(PicoTheme.colorScheme.error).padding(horizontal = 12.dp).probe("too_close"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(painter = painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(20.dp), tint = DdColors.StripeInk)
                    Text(text = rs(R.string.too_close), color = DdColors.StripeInk, style = DdType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text(text = rs(R.string.nameplate, isoName(state.isotope)), color = ink, style = DdType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).probe("nameplate"))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Key(name = "more", icon = R.drawable.ic_more, description = rs(R.string.action_more), enabled = state.ready) { onEvent(DoseEvent.MoreClicked) }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // row 2: the reading capsule (zone colour as the fill, stripeInk content; design reviewNotes 2b-1)
        Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            val zone = reading?.zone
            val fill = if (lost || zone == null) DdColors.StaleGrey else DdColors.ofZone(zone)
            Row(
                modifier = Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(28.dp)).background(fill).padding(horizontal = 20.dp).probe("reading"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (lost || reading == null) {
                    Text(text = "—", color = DdColors.StripeInk, style = DdType.reading, maxLines = 1)
                    Text(text = "µSv/h", color = DdColors.StripeInk, style = DdType.label, maxLines = 1)
                } else {
                    val (num, unit) = Dose.formatRate(reading.h)
                    Text(text = num, color = DdColors.StripeInk, style = DdType.reading, maxLines = 1, softWrap = false)
                    Text(text = unit, color = DdColors.StripeInk, style = DdType.label, maxLines = 1, softWrap = false)
                    Text(text = "· ${zoneName(reading.zone)}", color = DdColors.StripeInk, style = DdType.label, maxLines = 1, softWrap = false)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            // the Geiger icon blinks with the clicks (grey when silent or lost)
            val audible = state.settings.geiger && !lost && reading != null
            val blink = if (audible && state.clicks % 2L == 1L) 1f else 0.45f
            Icon(painter = painterResource(R.drawable.ic_geiger), contentDescription = null, modifier = Modifier.size(28.dp).alpha(if (audible) blink else 0.3f).probe("geiger"), tint = if (audible) ink else DdColors.StaleGrey)
            Spacer(modifier = Modifier.width(8.dp))
            // v0.1.1 (taste profile D15): the mute key, always in sight; the state is remembered (Settings.geiger)
            val soundOn = state.settings.geiger
            Key(
                name = "mute",
                icon = if (soundOn) R.drawable.ic_sound else R.drawable.ic_mute,
                description = rs(if (soundOn) R.string.action_mute else R.string.action_unmute),
                enabled = true,
                container = if (soundOn) null else PicoTheme.colorScheme.interaction,
                content = if (soundOn) null else DdColors.StripeInk,
            ) { onEvent(DoseEvent.GeigerSet(!soundOn)) }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.Bottom) {
            // the bar doubles as the floor map's legend (v0.1.1): the same ramp is drawn on the floor
            if (state.settings.heat) Text(text = rs(R.string.heat_legend), color = PicoTheme.colorScheme.labelSecondary, style = DdType.small, maxLines = 1, softWrap = false, modifier = Modifier.width(44.dp).probe("heat_legend"))
            Box(modifier = Modifier.weight(1f)) { ZoneBar(h = if (lost) null else reading?.h, ramp = state.settings.heat) }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // row 4: distance · blocked · cumulative + reset
        Row(modifier = Modifier.fillMaxWidth().height(32.dp).alpha(if (lost) 0.5f else 1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("chip_distance", R.drawable.ic_distance, if (reading == null || lost) "—" else "${oneDecimal(reading.d)} m", lit = false)
            val blocked = state.reading?.takeIf { it.shielded && !lost }
            Chip("chip_blocked", R.drawable.ic_blocked, blocked?.let { Dose.blockedText(it.attenuation) } ?: "—", lit = blocked != null)
            Chip("chip_cumulative", R.drawable.ic_cumulative, "${Dose.sig2(state.cumulative)} µSv", lit = false)
            Key(name = "reset", icon = R.drawable.ic_reset, description = rs(R.string.action_reset), enabled = !state.drillRunning && state.cumulative > 0f, size = 32.dp, iconSize = 18.dp) { onEvent(DoseEvent.ResetCumulative) }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // row 5: Source · Shield · Drills (locked to Drills while a drill runs)
        val tabs = Tab.entries
        Segments(items = tabs, selected = state.tab, width = PanelSizes.INNER_W.dp, enabled = !state.drillRunning && state.interactive, name = { "tab_${it.name.lowercase()}" }, onSelect = { onEvent(DoseEvent.TabSelected(it)) }) { t ->
            Text(text = rs(when (t) { Tab.SOURCE -> R.string.tab_source; Tab.SHIELD -> R.string.tab_shield; Tab.DRILL -> R.string.tab_drill }), style = DdType.label, maxLines = 1, softWrap = false)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().height(PanelSizes.CONTENT_H.dp).alpha(if (lost) 0.5f else 1f)) {
            when (state.tab) {
                Tab.SOURCE -> SourcePage(state, onEvent)
                Tab.SHIELD -> ShieldPage(state, onEvent)
                Tab.DRILL -> {
                    val d = state.drill
                    if (d == null) DrillIdlePage(state, onEvent) else DrillRunningPage(state, d, onEvent)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = rs(R.string.footer), color = PicoTheme.colorScheme.labelSecondary, style = DdType.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(20.dp).probe("footer"))
    }
}

/** A stat chip: icon 20 + tabular value; [lit] = the passable fill with stripeInk (the shield chip while shielded). */
@Composable
private fun Chip(name: String, icon: Int, value: String, lit: Boolean) {
    val fg = if (lit) DdColors.StripeInk else PicoTheme.colorScheme.labelPrimary
    Row(
        modifier = Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (lit) PicoTheme.colorScheme.passable else PicoTheme.colorScheme.fillTertiary).padding(horizontal = 12.dp).probe(name),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = fg)
        Text(text = value, color = fg, style = DdType.title, maxLines = 1, softWrap = false)
    }
}

/**
 * The log-scale zone bar (0.1 - 10 000 µSv/h, five zone colours, tick numbers, a white cursor). With [ramp]
 * (floor map on) the colours are the floor map's continuous ramp, so the bar is its legend.
 */
@Composable
private fun ZoneBar(h: Float?, ramp: Boolean = false) {
    val ticks = listOf(0.5f to "0.5", 10f to "10", 100f to "100", 1000f to "1000")
    val bounds = listOf(0.1f, 0.5f, 10f, 100f, 1000f, 10000f)
    val colors = Zone.entries.map { DdColors.ofZone(it) }
    val muted = PicoTheme.colorScheme.labelSecondary
    fun pos(v: Float): Float = ((log10(max(v, 0.1f)) + 1f) / 5f).coerceIn(0f, 1f)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(24.dp).probe("zone_bar")) {
        val w = maxWidth
        for ((v, label) in ticks) {
            Text(text = label, color = muted, style = DdType.small, maxLines = 1, softWrap = false, modifier = Modifier.offset(x = w * pos(v) - 6.dp, y = 0.dp))
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barTop = size.height - 8.dp.toPx()
            if (ramp) {
                val n = 64
                val sw = size.width / n
                for (k in 0 until n) {
                    val v = 10.0.pow((k + 0.5) / n * 5.0 - 1.0).toFloat()
                    drawRect(color = DdColors.heat(Heat.level(v)), topLeft = Offset(k * sw, barTop), size = Size(sw + 0.5f, 8.dp.toPx()))
                }
            } else for (i in 0 until 5) {
                val x0 = size.width * pos(bounds[i])
                val x1 = size.width * pos(bounds[i + 1])
                drawRect(color = colors[i], topLeft = Offset(x0, barTop), size = Size(x1 - x0, 8.dp.toPx()))
            }
            if (h != null) {
                val x = size.width * pos(h)
                drawRoundRect(color = Color.White, topLeft = Offset(x - 2.dp.toPx(), barTop - 4.dp.toPx()), size = Size(4.dp.toPx(), 12.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
            }
        }
    }
}

/** Source page (design `panels.dosimeter.tabs.source`): isotope segment, 1 m rate and half-value layer, place / remove + hint. */
@Composable
private fun SourcePage(state: DoseUiState, onEvent: (DoseEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val iso = state.isotope
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Segments(items = Isotope.entries, selected = iso, width = PanelSizes.INNER_W.dp, enabled = !state.drillRunning, name = { "iso_${it.tag}" }, onSelect = { onEvent(DoseEvent.IsotopeSelected(it)) }) { item ->
            Text(text = isoSymbol(item), style = DdType.label, maxLines = 1, softWrap = false)
        }
        Row(modifier = Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text = rs(R.string.source_level, whole(iso.h1)), color = ink, style = DdType.label, maxLines = 1, softWrap = false, modifier = Modifier.probe("source_level"))
            Text(text = rs(R.string.source_hvl, mm(iso.hvlMm)), color = ink, style = DdType.label, maxLines = 1, softWrap = false, modifier = Modifier.probe("source_hvl"))
        }
        Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val has = state.source != null
            WordButton(
                name = if (has) "remove_source" else "place_source",
                icon = R.drawable.ic_source,
                text = rs(if (has) R.string.btn_remove_source else R.string.btn_place_source),
                container = PicoTheme.colorScheme.fillTertiary,
                content = ink,
                enabled = !state.drillRunning,
            ) { if (has) onEvent(DoseEvent.RemoveSourceClicked) else onEvent(DoseEvent.PlaceSourceClicked) }
            Spacer(modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.selectedSource) Icon(painter = painterResource(R.drawable.ic_rotate), contentDescription = null, modifier = Modifier.size(18.dp), tint = ink)
                Text(text = rs(if (state.selectedSource) R.string.hint_rotate else R.string.hint_select), color = ink, style = DdType.label, maxLines = 1, softWrap = false, modifier = Modifier.probe("source_hint"))
            }
        }
    }
}

/** Thickness keys − / 6 mm / + (shield page and drill 3's fourth row). */
@Composable
private fun ThicknessKeys(state: DoseUiState, onEvent: (DoseEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val t = state.thicknessShown
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Key(name = "thickness_minus", icon = R.drawable.ic_minus, description = rs(R.string.action_thinner), enabled = t > Dose.THICKNESSES.first() && state.interactive) { onEvent(DoseEvent.ThicknessStep(-1)) }
        Text(text = rs(R.string.thickness, t), color = ink, style = DdType.title, maxLines = 1, softWrap = false, modifier = Modifier.width(52.dp).probe("thickness"))
        Key(name = "thickness_plus", icon = R.drawable.ic_plus, description = rs(R.string.action_thicker), enabled = t < Dose.THICKNESSES.last() && state.interactive) { onEvent(DoseEvent.ThicknessStep(1)) }
    }
}

/**
 * Shield page (design `tabs.shield`, v0.1.1): place shield · thickness, "blocks 47% · ½ per 6.5 mm", one row per
 * placed shield (name = select; bring in front of me · hide / show · delete: reachable while standing in the
 * shield, taste profile D14) and the stick hint.
 */
@Composable
private fun ShieldPage(state: DoseUiState, onEvent: (DoseEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            val aiming = state.aiming == Aiming.SHIELD
            WordButton(
                name = "place_shield",
                icon = if (aiming) R.drawable.ic_close else R.drawable.ic_shield,
                text = rs(if (aiming) R.string.btn_cancel else R.string.btn_place_shield),
                container = if (aiming) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary,
                content = if (aiming) DdColors.StripeInk else ink,
                enabled = state.interactive && state.source != null,
            ) { onEvent(DoseEvent.PlaceShieldClicked) }
            Spacer(modifier = Modifier.weight(1f))
            ThicknessKeys(state, onEvent)
        }
        val a = Dose.factor(state.isotope, state.thicknessShown)
        Text(text = rs(R.string.shield_info, Dose.blockedText(a), mm(state.isotope.hvlMm)), color = ink, style = DdType.label, maxLines = 1, softWrap = false, modifier = Modifier.height(20.dp).probe("shield_info"))
        if (state.shields.isEmpty()) {
            Text(text = rs(R.string.shield_none), color = PicoTheme.colorScheme.labelSecondary, style = DdType.label, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(88.dp).probe("shield_none"))
        } else {
            Column(modifier = Modifier.height(88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sh in state.shields.sortedBy { it.id }) ShieldRow(state, sh, onEvent)
            }
        }
        Row(modifier = Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // while placing: how to aim with what is there (v0.1.2, taste profile D1); else the stick hint
            val aiming = state.aiming == Aiming.SHIELD
            Icon(painter = painterResource(if (aiming) R.drawable.ic_shield else R.drawable.ic_rotate), contentDescription = null, modifier = Modifier.size(18.dp), tint = ink)
            Text(text = if (aiming) aimHint(state) else rs(R.string.hint_rotate_drag), color = ink, style = DdType.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("shield_hint"))
        }
    }
}

@Composable
private fun ShieldRow(state: DoseUiState, sh: Shield, onEvent: (DoseEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val selected = state.selectedShield == sh.id
    val enabled = state.ready && !state.shieldsHidden
    Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WordButton(
            name = "shield_item_${sh.id}",
            icon = R.drawable.ic_shield,
            text = if (sh.hidden) rs(R.string.shield_item_hidden, sh.id) else rs(R.string.shield_item, sh.id, sh.tMm),
            container = if (selected) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary,
            content = if (selected) DdColors.StripeInk else ink,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) { onEvent(DoseEvent.ShieldListTapped(sh.id)) }
        Key(name = "shield_front_${sh.id}", icon = R.drawable.ic_front, description = rs(R.string.action_to_front), enabled = enabled && state.head != null) { onEvent(DoseEvent.ShieldToFront(sh.id)) }
        Key(name = "shield_hide_${sh.id}", icon = if (sh.hidden) R.drawable.ic_eye else R.drawable.ic_eye_off, description = rs(if (sh.hidden) R.string.action_show else R.string.action_hide), enabled = enabled) { onEvent(DoseEvent.ShieldHidden(sh.id, !sh.hidden)) }
        Key(name = "shield_delete_${sh.id}", icon = R.drawable.ic_trash, description = rs(R.string.action_delete), enabled = state.ready && !state.drillRunning) { onEvent(DoseEvent.DeleteShield(sh.id)) }
    }
}

/** How to aim and confirm with the input there is now (controller ray + trigger, eyes + pinch, or the head as a fallback). */
@Composable
private fun aimHint(state: DoseUiState): String =
    rs(
        when (state.aimSource) {
            AimSource.CONTROLLER -> R.string.aim_controller
            AimSource.EYES -> R.string.aim_eyes
            AimSource.HEAD -> R.string.aim_head
            null -> R.string.aiming
        }
    )

/** Drill list (design `tabs.drillIdle`): numbered dot · short name · [Start]. */
@Composable
private fun DrillIdlePage(state: DoseUiState, onEvent: (DoseEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (k in DrillKind.entries) {
            Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(PicoTheme.colorScheme.fillSecondary), contentAlignment = Alignment.Center) {
                    Text(text = "${k.no}", color = ink, style = DdType.label, maxLines = 1)
                }
                Text(text = drillName(k), color = ink, style = DdType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                WordButton(name = "drill_${k.tag}", icon = null, text = rs(R.string.btn_start), container = PicoTheme.colorScheme.fillSecondary, content = ink, enabled = state.interactive && state.source != null, height = 32.dp, minWidth = 72.dp) { onEvent(DoseEvent.DrillStart(k)) }
            }
        }
    }
}

/** A running drill (design `tabs.drillRunning`): goal, budget bar, status · quit, and drill 3's shield row. */
@Composable
private fun DrillRunningPage(state: DoseUiState, d: DrillUi, onEvent: (DoseEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    // 4 dp gaps and a 20 dp budget row: SpatialUI buttons draw taller than their rows, and with 8 dp gaps drill 3's
    // fourth row ran over the footer on the emulator (2026-10-26)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = drillGoal(d.kind), color = ink, style = DdType.body.copy(lineHeight = DdType.title.lineHeight), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(24.dp).probe("drill_goal"))
        Row(modifier = Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val share = (d.cum / d.kind.budget).coerceIn(0f, 1f)
            val track = PicoTheme.colorScheme.fillSecondary
            val fill = if (share > 0.8f) PicoTheme.colorScheme.alert else PicoTheme.colorScheme.passable
            Canvas(modifier = Modifier.width(240.dp).height(8.dp).probe("budget_bar")) {
                drawRoundRect(color = track, size = size, cornerRadius = CornerRadius(4.dp.toPx()))
                if (share > 0f) drawRoundRect(color = fill, size = Size(size.width * share, size.height), cornerRadius = CornerRadius(4.dp.toPx()))
            }
            Text(text = rs(R.string.budget, Dose.sig2(d.cum), mm(d.kind.budget)), color = ink, style = DdType.label, maxLines = 1, softWrap = false, modifier = Modifier.probe("budget"))
        }
        Row(modifier = Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            val status =
                when (d.kind) {
                    DrillKind.DISTANCE -> if (d.toGo <= 0f) rs(R.string.status_hold) else rs(R.string.status_to_go, oneDecimal(d.toGo))
                    DrillKind.TIME -> rs(if (d.read) R.string.status_got_it else R.string.status_go_read)
                    DrillKind.SHIELD -> if (!d.inSpot) rs(R.string.status_back_to_spot) else rs(R.string.status_shield, Dose.rateText(d.hStart), oneDecimal(d.target))
                }
            Text(text = status, color = ink, style = DdType.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).probe("drill_status"))
            WordButton(name = "drill_quit", icon = null, text = rs(R.string.btn_quit), container = PicoTheme.colorScheme.fillTertiary, content = ink, enabled = d.running, height = 32.dp, minWidth = 72.dp) { onEvent(DoseEvent.DrillQuit) }
        }
        if (d.kind == DrillKind.SHIELD) {
            Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WordButton(name = "place_shield", icon = R.drawable.ic_shield, text = rs(R.string.btn_place_shield), container = if (state.aiming == Aiming.SHIELD) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary, content = if (state.aiming == Aiming.SHIELD) DdColors.StripeInk else ink, enabled = d.running, minWidth = 72.dp) { onEvent(DoseEvent.PlaceShieldClicked) }
                ThicknessKeys(state, onEvent)
                Spacer(modifier = Modifier.weight(1f))
                Text(text = Dose.blockedText(Dose.factor(state.isotope, state.thicknessShown)), color = ink, style = DdType.label, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * The compact panel (design `layouts.compact`, 400 x 120): starting (spinner), scanning, aiming the source
 * ("tap a desk or the floor"), or idle with [Place source] (S27).
 */
@Composable
internal fun CompactPanel(state: DoseUiState?, onEvent: (DoseEvent) -> Unit) {
    Row(
        modifier =
            Modifier
                .size(PanelSizes.DOSIMETER_W.dp, PanelSizes.COMPACT_H.dp)
                .clip(RoundedCornerShape(28.dp))
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state == null) return@Row
        val ink = PicoTheme.colorScheme.labelPrimary
        when {
            state.phase == Phase.STARTING || state.blocked -> {
                Spinner(28.dp, ink)
                Spacer(modifier = Modifier.weight(1f))
            }
            state.phase == Phase.SCANNING -> {
                Spinner(28.dp, ink)
                Text(text = rs(R.string.scanning), color = ink, style = DdType.body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).probe("compact_text"))
            }
            state.aiming == Aiming.SOURCE -> {
                Icon(painter = painterResource(R.drawable.ic_source), contentDescription = null, modifier = Modifier.size(32.dp), tint = ink)
                Text(text = aimHint(state), color = ink, style = DdType.body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).probe("compact_text"))
            }
            else -> {
                Icon(painter = painterResource(R.drawable.ic_source), contentDescription = null, modifier = Modifier.size(32.dp), tint = ink)
                WordButton(name = "place_source", icon = null, text = rs(R.string.btn_place_source), container = PicoTheme.colorScheme.interaction, content = DdColors.StripeInk, enabled = state.interactive) { onEvent(DoseEvent.PlaceSourceClicked) }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        Key(name = "more", icon = R.drawable.ic_more, description = rs(R.string.action_more), enabled = state.ready) { onEvent(DoseEvent.MoreClicked) }
    }
}
