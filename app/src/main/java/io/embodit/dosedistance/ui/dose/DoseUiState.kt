package io.embodit.dosedistance.ui.dose

import io.embodit.dosedistance.domain.AimSource
import io.embodit.dosedistance.domain.Clearance
import io.embodit.dosedistance.domain.Dose
import io.embodit.dosedistance.domain.Isotope
import io.embodit.dosedistance.domain.P2
import io.embodit.dosedistance.domain.PlaneSurface
import io.embodit.dosedistance.domain.Reading
import io.embodit.dosedistance.domain.Settings
import io.embodit.dosedistance.domain.Shield
import io.embodit.dosedistance.domain.Snap
import io.embodit.dosedistance.domain.SourceFrame
import io.embodit.dosedistance.domain.SurfaceHit
import io.embodit.dosedistance.domain.Vec3
import io.embodit.dosedistance.domain.Zone

enum class Phase { STARTING, SCANNING, READY }

enum class Aiming { NONE, SOURCE, SHIELD }

enum class Tab { SOURCE, SHIELD, DRILL }

enum class SnackKind { SCANNING, SHIELDS_FULL, SNAPPED, LOST, CLEARED, BACK_TO_SPOT, WALK_TO_SPOT }

data class SnackUi(val kind: SnackKind, val seq: Int)

enum class DrillKind(val tag: String, val no: Int, val budget: Float) {
    DISTANCE("distance", 1, io.embodit.dosedistance.domain.Drills.BUDGET_DISTANCE),
    TIME("time", 2, io.embodit.dosedistance.domain.Drills.BUDGET_TIME),
    SHIELD("shield", 3, io.embodit.dosedistance.domain.Drills.BUDGET_SHIELD);

    companion object {
        fun byTag(tag: String?): DrillKind? = entries.firstOrNull { it.tag == tag }
    }
}

enum class DrillPhase { RUNNING, PASSED, FAILED }

enum class FailWhy { OVER_BUDGET, TIMEOUT }

/** What is being dragged (design S09). */
sealed interface DragWhat {
    data object Source : DragWhat

    data class ShieldOf(val id: Int) : DragWhat
}

/** The placed source (design `entities3d.source`): [planeY] = the surface it stands on; the core is 0.11 above. */
data class SourceUi(val x: Float, val z: Float, val planeY: Float, val surface: String, val planeId: String?, val faceDeg: Float, val frameEx: P2) {
    val core: Vec3
        get() = Vec3(x, planeY + CORE_ABOVE, z)

    val foot: P2
        get() = P2(x, z)

    val frame: SourceFrame
        get() = SourceFrame(core, frameEx)

    /** The middle of the pot opening (drill 2 gaze target, design `entities3d.codeLabel`). */
    val mouth: Vec3
        get() = Vec3(x, planeY + POT_H, z)

    companion object {
        const val CORE_ABOVE = 0.11f
        const val POT_H = 0.12f
        const val POT_D = 0.12f
    }
}

/** A running or finished drill (design `drills[]`). Times are drill-clock milliseconds (paused while tracking is lost). */
data class DrillUi(
    val kind: DrillKind,
    val phase: DrillPhase = DrillPhase.RUNNING,
    val elapsedMs: Long = 0L,
    val cum: Float = 0f,
    val hStart: Float = 0f,
    val dStart: Float = 0f,
    val target: Float = 0f,
    val toGo: Float = 0f,
    val holdMs: Long = 0L,
    val code: String = "",
    val readAtMs: Long? = null,
    val spot: P2? = null,
    val inSpot: Boolean = true,
    val outMs: Long = 0L,
    val closestD: Float = Float.MAX_VALUE,
    val hNow: Float = 0f,
    val aNow: Float = 1f,
    val failWhy: FailWhy? = null,
    val failD: Float = 0f,
    val overStaySec: Int = 0,
    val resultH: Float = 0f,
    val resultA: Float = 1f,
) {
    val running: Boolean
        get() = phase == DrillPhase.RUNNING

    val read: Boolean
        get() = readAtMs != null
}

data class DoseUiState(
    val phase: Phase = Phase.STARTING,
    val blocked: Boolean = false,
    val trackingLost: Boolean = false,
    val floorY: Float? = null,
    val tables: List<PlaneSurface> = emptyList(),
    val source: SourceUi? = null,
    val shields: List<Shield> = emptyList(),
    val lastShieldId: Int? = null,
    val aiming: Aiming = Aiming.NONE,
    val selectedSource: Boolean = false,
    val selectedShield: Int? = null,
    val tab: Tab = Tab.SOURCE,
    val dragging: DragWhat? = null,
    val settings: Settings = Settings(),
    val reading: Reading? = null,
    val cumulative: Float = 0f,
    val clicks: Long = 0L,
    val drill: DrillUi? = null,
    val menuOpen: Boolean = false,
    val clearDialog: Boolean = false,
    val aboutDialog: Boolean = false,
    val snack: SnackUi? = null,
    val coach: Boolean = false,
    val head: Vec3? = null,
    /** The head's level heading (for "bring to front", taste profile D9). */
    val heading: P2? = null,
    /** What aims now (v0.1.2, taste profile D1): controller ray, eyes, or the head as the last fallback. */
    val aimSource: AimSource? = null,
) {
    val isotope: Isotope
        get() = settings.isotope

    val ready: Boolean
        get() = phase == Phase.READY && !blocked

    val drillRunning: Boolean
        get() = drill?.running == true

    /** Drill 1 hides every shield and keeps them out of the reading (design `rules.drills`). */
    val shieldsHidden: Boolean
        get() = drill?.kind == DrillKind.DISTANCE && drill.running

    val visibleShields: List<Shield>
        get() = if (shieldsHidden) emptyList() else shields.filter { !it.hidden }

    /** Drill 3 locks the source (design `rules.select`). */
    val sourceLocked: Boolean
        get() = drill?.kind == DrillKind.SHIELD && drill.running

    val selectedShieldUi: Shield?
        get() = selectedShield?.let { id -> shields.firstOrNull { it.id == id } }

    /** The shield the thickness keys act on (design `rules.thickness`). */
    val thicknessTarget: Shield?
        get() = selectedShieldUi ?: lastShieldId?.let { id -> shields.firstOrNull { it.id == id } } ?: shields.lastOrNull()

    val thicknessShown: Int
        get() = thicknessTarget?.tMm ?: settings.thickness

    val interactive: Boolean
        get() = ready && !trackingLost && !clearDialog && !aboutDialog

    val dialogOpen: Boolean
        get() = clearDialog || aboutDialog || blocked

    val resultShown: Boolean
        get() = drill != null && !drill.running

    /** The dosimeter is in its selected (full) layout once a source stands in the room. */
    val fullPanel: Boolean
        get() = source != null && phase == Phase.READY

    val zone: Zone?
        get() = reading?.zone

    /** The design state the screen is in (design `states[]`, S01 - S27). */
    val designState: String
        get() =
            when {
                blocked -> "S20"
                phase == Phase.STARTING -> "S01"
                phase == Phase.SCANNING -> "S02"
                trackingLost -> "S19"
                clearDialog -> "S23"
                aboutDialog -> "S25"
                menuOpen -> "S22"
                drill?.phase == DrillPhase.PASSED -> "S17"
                drill?.phase == DrillPhase.FAILED -> "S18"
                drill?.kind == DrillKind.DISTANCE -> "S14"
                drill?.kind == DrillKind.TIME -> "S15"
                drill?.kind == DrillKind.SHIELD -> "S16"
                source == null && aiming == Aiming.SOURCE -> "S03"
                source == null -> "S27"
                coach -> "S21"
                dragging != null -> "S09"
                aiming == Aiming.SHIELD -> "S08"
                snack?.kind == SnackKind.SHIELDS_FULL -> "S24"
                selectedShield != null -> "S12"
                selectedSource -> "S07"
                snack?.kind == SnackKind.SNAPPED -> "S11"
                reading?.shielded == true -> "S13"
                shields.isNotEmpty() -> "S10"
                isotope != Isotope.CS137 -> "S26"
                reading == null -> "S04"
                reading.zone == Zone.HIGH || reading.zone == Zone.EXTREME -> "S06"
                reading.zone == Zone.MID -> "S05"
                else -> "S04"
            }
}

sealed interface DoseEvent {
    data object SceneReady : DoseEvent

    data class PlanesChanged(val floorY: Float?, val tables: List<PlaneSurface>) : DoseEvent

    data class TrackingChanged(val lost: Boolean) : DoseEvent

    data class BlockChanged(val reason: String?) : DoseEvent

    /** 10 Hz from the frame system: the reading at the (judged) head, the dose integrated since the last tick, the gaze time on the pot. */
    data class Tick(val reading: Reading?, val dDose: Float, val head: Vec3?, val gazeMs: Long, val clicks: Long, val nowMs: Long, val heading: P2? = null, val aimSource: AimSource? = null) : DoseEvent

    data class PlaneTapped(val hit: SurfaceHit) : DoseEvent

    data object SourceTapped : DoseEvent

    data class ShieldTapped(val id: Int) : DoseEvent

    data object EmptyTapped : DoseEvent

    data class DragStarted(val what: DragWhat) : DoseEvent

    data class SourceMoved(val source: SourceUi?) : DoseEvent

    data class ShieldMoved(val shield: Shield?, val snapped: Boolean) : DoseEvent

    data class TabSelected(val tab: Tab) : DoseEvent

    data class IsotopeSelected(val isotope: Isotope) : DoseEvent

    data object PlaceSourceClicked : DoseEvent

    data object RemoveSourceClicked : DoseEvent

    data object PlaceShieldClicked : DoseEvent

    /** Deletes shield [id] (the dosimeter's shield list); null = the selected one. */
    data class DeleteShield(val id: Int?) : DoseEvent

    /** Moves shield [id] 1.2 m in front of the head, facing the user (taste profile D9 / D14). */
    data class ShieldToFront(val id: Int) : DoseEvent

    data class ShieldHidden(val id: Int, val hidden: Boolean) : DoseEvent

    /** A shield's name in the list: selects it (thickness keys, thumbstick). */
    data class ShieldListTapped(val id: Int) : DoseEvent

    data class ThicknessStep(val step: Int) : DoseEvent

    data object ResetCumulative : DoseEvent

    data class DrillStart(val kind: DrillKind) : DoseEvent

    data object DrillQuit : DoseEvent

    data object ResultAgain : DoseEvent

    data object ResultNext : DoseEvent

    data object ResultLater : DoseEvent

    data object MoreClicked : DoseEvent

    data class GeigerSet(val on: Boolean) : DoseEvent

    data class RingsSet(val on: Boolean) : DoseEvent

    data class HeatSet(val on: Boolean) : DoseEvent

    data class SnapSet(val on: Boolean) : DoseEvent

    data class LanguageSelected(val language: io.embodit.dosedistance.domain.Language) : DoseEvent

    data object AboutClicked : DoseEvent

    data object ClearAllClicked : DoseEvent

    data object ClearConfirmed : DoseEvent

    data object DialogDismissed : DoseEvent

    data object ExitClicked : DoseEvent

    data object BlockedExit : DoseEvent

    data object BlockedRetry : DoseEvent

    data object CoachDismissed : DoseEvent
}

sealed interface DoseEffect {
    data object Exit : DoseEffect

    data class Feedback(val kind: String) : DoseEffect
}

/**
 * The snap point for a shield (design `rules.snap`, v0.1.1 [Snap.point]): up to 0.5 m from the source towards the
 * user, or drill 3's midpoint of source and spot, always 0.8 m clear of the user; null when the snap is off or
 * no point keeps the user clear.
 */
fun shieldSnapPoint(s: DoseUiState): P2? {
    if (!s.settings.snap) return null
    val src = s.source ?: return null
    val spot = s.drill?.takeIf { it.kind == DrillKind.SHIELD && it.running }?.spot
    return Snap.point(src.foot, s.head?.let { P2.of(it) }, spot, src.frameEx)
}

/** Where a shield may stand: pushed out of the user's clearance (taste profile D14). */
fun shieldLanding(s: DoseUiState, p: P2): P2 {
    val head = s.head?.let { P2.of(it) }
    val toSource = s.source?.let { src -> head?.let { src.foot - it } } ?: s.heading ?: P2(0f, -1f)
    return Clearance.pushOut(p, head, Clearance.SHIELD_M, toSource)
}
const val SNAP_SHIELD_M = 0.10f
const val UNSNAP_SHIELD_M = 0.15f
const val SNAP_TABLE_M = 0.15f

/** Shields up to [Shield.MAX]; ids 1 and 2. */
internal fun List<Shield>.freeId(): Int? = (1..Shield.MAX).firstOrNull { id -> none { it.id == id } }

internal fun DoseUiState.blockedText(): String? = reading?.takeIf { it.shielded }?.let { Dose.blockedText(it.attenuation) }
