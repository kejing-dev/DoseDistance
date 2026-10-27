package io.embodit.dosedistance.ui.dose

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.dosedistance.data.PrefsSettingsStore
import io.embodit.dosedistance.data.SettingsStore
import io.embodit.dosedistance.domain.Clearance
import io.embodit.dosedistance.domain.Dose
import io.embodit.dosedistance.domain.Drills
import io.embodit.dosedistance.domain.Isotope
import io.embodit.dosedistance.domain.P2
import io.embodit.dosedistance.domain.PlaneSurface
import io.embodit.dosedistance.domain.Reading
import io.embodit.dosedistance.domain.Settings
import io.embodit.dosedistance.domain.Shield
import io.embodit.dosedistance.domain.SurfaceHit
import io.embodit.dosedistance.domain.Vec3
import io.embodit.dosedistance.platform.DdGraph
import io.embodit.dosedistance.platform.DdLog
import java.util.Locale
import kotlin.math.atan2
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The whole behaviour of the app (design-package `rules`, `drills`, `states`): events from the panels and
 * the scene come in through [onEvent], the state goes out through [state]. The per-frame dose arithmetic
 * lives in the scene's frame system; this class sees it 10 times a second ([DoseEvent.Tick]).
 */
class DoseViewModel(
    private val store: SettingsStore,
    private val blockReason: () -> String?,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(DoseUiState(settings = store.load()))
    val state: StateFlow<DoseUiState> = _state

    private val _effects = MutableSharedFlow<DoseEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<DoseEffect> = _effects

    /** Timed snackbars stay 60 s (debug `snack long`). */
    @Volatile var longSnacks = false

    /** Debug: stay in S02 whatever the planes say. */
    @Volatile var debugHoldScanning = false

    private var snackSeq = 0
    private var snackJob: Job? = null
    private var coachJob: Job? = null
    private var blockJob: Job? = null
    private var lastTickMs = -1L
    private var lastLogged: String? = null

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(e: DoseEvent) {
        val s = _state.value
        when (e) {
            DoseEvent.SceneReady -> onSceneReady()
            is DoseEvent.PlanesChanged -> onPlanes(e.floorY, e.tables)
            is DoseEvent.TrackingChanged -> onTracking(e.lost)
            is DoseEvent.BlockChanged -> if (e.reason != null) update { it.copy(blocked = true, menuOpen = false) }
            is DoseEvent.Tick -> onTick(e)
            is DoseEvent.PlaneTapped -> onPlaneTapped(e.hit)
            DoseEvent.SourceTapped -> onSourceTapped()
            is DoseEvent.ShieldTapped -> onShieldTapped(e.id)
            DoseEvent.EmptyTapped -> onEmptyTapped()
            is DoseEvent.DragStarted -> onDragStarted(e.what)
            is DoseEvent.SourceMoved -> onSourceMoved(e.source)
            is DoseEvent.ShieldMoved -> onShieldMoved(e.shield, e.snapped)
            is DoseEvent.TabSelected -> if (!s.drillRunning && s.interactive) update { it.copy(tab = e.tab, aiming = if (it.aiming == Aiming.SHIELD && e.tab != Tab.SHIELD) Aiming.NONE else it.aiming) }
            is DoseEvent.IsotopeSelected -> if (!s.drillRunning) saveSettings { it.copy(isotope = e.isotope) }
            DoseEvent.PlaceSourceClicked -> onPlaceSource()
            DoseEvent.RemoveSourceClicked -> if (!s.drillRunning && s.source != null) removeSource()
            DoseEvent.PlaceShieldClicked -> onPlaceShield()
            is DoseEvent.DeleteShield -> onDeleteShield(e.id ?: s.selectedShield)
            is DoseEvent.ShieldToFront -> shieldToFront(e.id)
            is DoseEvent.ShieldHidden -> onShieldHidden(e.id, e.hidden)
            is DoseEvent.ShieldListTapped -> onShieldListTapped(e.id)
            is DoseEvent.ThicknessStep -> onThickness(e.step)
            DoseEvent.ResetCumulative -> if (!s.drillRunning) update { it.copy(cumulative = 0f) }
            is DoseEvent.DrillStart -> startDrill(e.kind)
            DoseEvent.DrillQuit -> if (s.drill != null) endDrill("quit")
            DoseEvent.ResultAgain -> s.drill?.let { startDrill(it.kind) }
            DoseEvent.ResultNext -> s.drill?.let { d -> DrillKind.entries.getOrNull(d.kind.ordinal + 1)?.let { startDrill(it) } ?: endDrill("done") }
            DoseEvent.ResultLater -> endDrill("later")
            DoseEvent.MoreClicked -> if (s.ready) update { it.copy(menuOpen = !it.menuOpen) }
            is DoseEvent.GeigerSet -> saveSettings { it.copy(geiger = e.on) }
            is DoseEvent.RingsSet -> saveSettings { it.copy(rings = e.on) }
            is DoseEvent.HeatSet -> saveSettings { it.copy(heat = e.on) }
            is DoseEvent.SnapSet -> saveSettings { it.copy(snap = e.on) }
            is DoseEvent.LanguageSelected -> saveSettings { it.copy(language = e.language) }
            DoseEvent.AboutClicked -> update { it.copy(menuOpen = false, aboutDialog = true) }
            DoseEvent.ClearAllClicked -> if (!s.drillRunning) update { it.copy(menuOpen = false, clearDialog = true) }
            DoseEvent.ClearConfirmed -> clearAll()
            DoseEvent.DialogDismissed -> update { it.copy(clearDialog = false, aboutDialog = false) }
            DoseEvent.ExitClicked, DoseEvent.BlockedExit -> {
                DdLog.i("EXIT requested")
                _effects.tryEmit(DoseEffect.Exit)
            }
            DoseEvent.BlockedRetry -> {
                update { it.copy(blocked = false, phase = Phase.STARTING) }
                scope.launch {
                    delay(300)
                    onSceneReady()
                }
            }
            DoseEvent.CoachDismissed -> dismissCoach()
        }
    }

    private fun onSceneReady() {
        val s = _state.value
        val ready = s.floorY != null && !debugHoldScanning
        update { it.copy(phase = if (ready) Phase.READY else Phase.SCANNING, aiming = if (ready && it.source == null) Aiming.SOURCE else it.aiming) }
        if (!ready) showSnack(SnackKind.SCANNING)
        watchBlock()
    }

    /** The head provider reports NOT_IN_FULL_SPACE while the Stage opens: blocked only if it still does after a grace period. */
    private fun watchBlock() {
        blockJob?.cancel()
        blockJob =
            scope.launch {
                delay(BLOCK_GRACE_MS)
                val reason = blockReason()
                if (reason != null) {
                    DdLog.w("BLOCKED reason=$reason")
                    update { it.copy(blocked = true, menuOpen = false) }
                }
            }
    }

    private fun onPlanes(floorY: Float?, tables: List<PlaneSurface>) {
        update { it.copy(floorY = floorY, tables = tables) }
        val s = _state.value
        if (s.phase == Phase.SCANNING && floorY != null && !debugHoldScanning) {
            update { it.copy(phase = Phase.READY, aiming = if (it.source == null) Aiming.SOURCE else it.aiming, snack = if (it.snack?.kind == SnackKind.SCANNING) null else it.snack) }
        }
    }

    private fun onTracking(lost: Boolean) {
        if (lost) {
            update { it.copy(trackingLost = true, menuOpen = false) }
            showSnack(SnackKind.LOST)
        } else {
            update { it.copy(trackingLost = false, snack = if (it.snack?.kind == SnackKind.LOST) null else it.snack) }
            lastTickMs = -1L
        }
    }

    // =========================================================================================
    // the 10 Hz reading: cumulative dose and drills
    // =========================================================================================

    private fun onTick(e: DoseEvent.Tick) {
        val s = _state.value
        val dt = if (lastTickMs < 0) 0L else (e.nowMs - lastTickMs).coerceIn(0L, 500L)
        lastTickMs = e.nowMs
        if (s.trackingLost) {
            update { it.copy(head = e.head ?: it.head, heading = e.heading ?: it.heading) }
            return
        }
        val dose = if (e.reading != null) e.dDose else 0f
        var drill = s.drill
        if (drill != null && drill.running && e.reading != null && s.source != null) drill = stepDrill(drill, s, e, dt, dose)
        val passedNow = drill?.phase == DrillPhase.PASSED && s.drill?.phase == DrillPhase.RUNNING
        val failedNow = drill?.phase == DrillPhase.FAILED && s.drill?.phase == DrillPhase.RUNNING
        update { it.copy(reading = e.reading, cumulative = it.cumulative + dose, head = e.head ?: it.head, heading = e.heading ?: it.heading, clicks = e.clicks, drill = drill) }
        if (passedNow) {
            DdLog.i("DRILL pass kind=${drill!!.kind.tag} s=${drill.elapsedMs / 1000} cum=${fmt(drill.cum)} h=${fmt(drill.resultH)} a=${fmt(drill.resultA)}")
            _effects.tryEmit(DoseEffect.Feedback("snap"))
        }
        if (failedNow) DdLog.i("DRILL fail kind=${drill!!.kind.tag} why=${drill.failWhy} cum=${fmt(drill.cum)}")
    }

    private fun stepDrill(d0: DrillUi, s: DoseUiState, e: DoseEvent.Tick, dt: Long, dose: Float): DrillUi {
        val reading = e.reading ?: return d0
        val src = s.source ?: return d0
        val head = e.head ?: return d0
        val core = src.core
        val r = P2(head.x - core.x, head.z - core.z).length
        var d = d0.copy(cum = d0.cum + dose, hNow = reading.h, aNow = reading.attenuation, closestD = minOf(d0.closestD, reading.d))
        when (d.kind) {
            DrillKind.DISTANCE -> {
                d = d.copy(elapsedMs = d.elapsedMs + dt, toGo = Drills.toGo(d.dStart, head.y - core.y, r))
                d = if (reading.h <= d.target) d.copy(holdMs = d.holdMs + dt) else d.copy(holdMs = 0L)
                if (d.holdMs >= Drills.HOLD_MS) return d.copy(phase = DrillPhase.PASSED, resultH = reading.h, resultA = reading.attenuation)
                if (d.cum > d.kind.budget) return d.copy(phase = DrillPhase.FAILED, failWhy = FailWhy.OVER_BUDGET, failD = reading.d)
            }
            DrillKind.TIME -> {
                d = d.copy(elapsedMs = d.elapsedMs + dt)
                if (!d.read && e.gazeMs >= Drills.READ_MS && reading.d <= Drills.READ_MAX_D) {
                    d = d.copy(readAtMs = d.elapsedMs)
                    DdLog.i("DRILL read code=${d.code} d=${fmt(reading.d)}")
                    _effects.tryEmit(DoseEffect.Feedback("click"))
                }
                if (d.read && reading.h <= d.hStart) return d.copy(phase = DrillPhase.PASSED, resultH = reading.h, resultA = reading.attenuation)
                if (d.cum > d.kind.budget) {
                    val stay = d.readAtMs?.let { ((d.elapsedMs - it) / 1000L).toInt() } ?: 0
                    return d.copy(phase = DrillPhase.FAILED, failWhy = FailWhy.OVER_BUDGET, failD = reading.d, overStaySec = stay)
                }
                if (!d.read && d.elapsedMs > Drills.TIME_LIMIT_MS) return d.copy(phase = DrillPhase.FAILED, failWhy = FailWhy.TIMEOUT)
            }
            DrillKind.SHIELD -> {
                val spot = d.spot ?: P2(head.x, head.z)
                val inSpot = P2(head.x - spot.x, head.z - spot.z).length <= Drills.SPOT_R
                val outMs = if (inSpot) 0L else d.outMs + dt
                val paused = outMs >= Drills.OUT_MS
                if (paused && d.outMs < Drills.OUT_MS) showSnack(SnackKind.BACK_TO_SPOT)
                d = d.copy(inSpot = inSpot, outMs = outMs, elapsedMs = if (paused) d.elapsedMs else d.elapsedMs + dt)
                d = if (inSpot && Drills.shieldPasses(reading.attenuation)) d.copy(holdMs = d.holdMs + dt) else d.copy(holdMs = 0L)
                if (d.holdMs >= Drills.HOLD_MS) return d.copy(phase = DrillPhase.PASSED, resultH = reading.h, resultA = reading.attenuation)
                if (d.cum > d.kind.budget) return d.copy(phase = DrillPhase.FAILED, failWhy = FailWhy.OVER_BUDGET, failD = reading.d)
            }
        }
        return d
    }

    private fun startDrill(kind: DrillKind) {
        val s = _state.value
        val src = s.source ?: return
        val floorY = s.floorY ?: return
        val head = s.head ?: src.frame.world(1f, 0.85f, 0f)
        val core = src.core
        val drill =
            when (kind) {
                DrillKind.DISTANCE -> {
                    // shields are hidden first, then the start is read without them (design R2-3)
                    val (d0, h0, target) = Drills.distanceStart(s.isotope, core.distanceTo(head))
                    DrillUi(kind, dStart = d0, hStart = h0, target = target, toGo = Drills.toGo(d0, head.y - core.y, P2(head.x - core.x, head.z - core.z).length))
                }
                DrillKind.TIME -> {
                    val h = Reading.at(s.isotope, core, head, s.visibleShields, floorY).h
                    DrillUi(kind, hStart = h, code = String.format(Locale.US, "%03d", Random.nextInt(100, 1000)))
                }
                DrillKind.SHIELD -> {
                    val (spot, pulled) = Drills.spot(src.foot, P2(head.x, head.z), src.frameEx)
                    if (pulled) showSnack(SnackKind.WALK_TO_SPOT)
                    val h0 = Reading.at(s.isotope, core, Vec3(spot.x, head.y, spot.z), emptyList(), floorY).h
                    DrillUi(kind, spot = spot, hStart = h0, target = Drills.shieldTarget(h0))
                }
            }
        update {
            it.copy(
                drill = drill,
                tab = Tab.DRILL,
                menuOpen = false,
                aiming = Aiming.NONE,
                selectedSource = false,
                selectedShield = if (kind == DrillKind.DISTANCE) null else it.selectedShield,
                coach = false,
            )
        }
        DdLog.i("DRILL start kind=${kind.tag} hStart=${fmt(drill.hStart)} dStart=${fmt(drill.dStart)} target=${fmt(drill.target)} code=${drill.code} spot=${drill.spot}")
    }

    private fun endDrill(why: String) {
        DdLog.i("DRILL end why=$why")
        update { it.copy(drill = null, tab = Tab.DRILL, aiming = Aiming.NONE) }
    }

    // =========================================================================================
    // taps, drags, placement
    // =========================================================================================

    private fun onPlaneTapped(hit: SurfaceHit) {
        val s = _state.value
        if (!s.interactive) return
        if (s.menuOpen) {
            update { it.copy(menuOpen = false) }
            return
        }
        when (s.aiming) {
            Aiming.SOURCE -> {
                val sem = hit.plane.semantic
                if (sem == TABLE || sem == FLOOR) placeSourceAt(hit.plane, hit.point) else {
                    DdLog.i("AIM cancel source plane=${hit.plane.label}")
                    update { it.copy(aiming = Aiming.NONE) }
                }
            }
            Aiming.SHIELD -> {
                if (hit.plane.semantic == FLOOR) placeShieldAt(P2(hit.point.x, hit.point.z)) else {
                    DdLog.i("AIM cancel shield plane=${hit.plane.label}")
                    update { it.copy(aiming = Aiming.NONE) }
                }
            }
            Aiming.NONE -> deselect()
        }
    }

    private fun onSourceTapped() {
        val s = _state.value
        if (!s.interactive) return
        if (s.menuOpen) return update { it.copy(menuOpen = false) }
        if (s.aiming != Aiming.NONE) return update { it.copy(aiming = Aiming.NONE) }
        if (s.sourceLocked) {
            DdLog.i("SELECT source refused: locked by drill 3")
            return
        }
        update { it.copy(selectedSource = true, selectedShield = null, tab = if (it.drillRunning) it.tab else Tab.SOURCE, coach = false) }
        DdLog.i("SELECT source")
    }

    private fun onShieldTapped(id: Int) {
        val s = _state.value
        if (!s.interactive || s.shieldsHidden) return
        if (s.menuOpen) return update { it.copy(menuOpen = false) }
        if (s.aiming != Aiming.NONE) return update { it.copy(aiming = Aiming.NONE) }
        update { it.copy(selectedShield = id, selectedSource = false, lastShieldId = id, tab = if (it.drillRunning) it.tab else Tab.SHIELD) }
        DdLog.i("SELECT shield id=$id")
    }

    private fun onEmptyTapped() {
        val s = _state.value
        if (s.menuOpen) return update { it.copy(menuOpen = false) }
        if (s.aiming != Aiming.NONE) {
            DdLog.i("AIM cancel ${s.aiming}")
            return update { it.copy(aiming = Aiming.NONE) }
        }
        deselect()
    }

    private fun deselect() = update { it.copy(selectedSource = false, selectedShield = null) }

    private fun onDragStarted(what: DragWhat) {
        val s = _state.value
        if (!s.interactive || s.menuOpen || s.aiming != Aiming.NONE) return
        when (what) {
            DragWhat.Source -> if (!s.sourceLocked && s.source != null) update { it.copy(dragging = what, selectedSource = true, selectedShield = null, tab = if (it.drillRunning) it.tab else Tab.SOURCE, coach = false) }
            is DragWhat.ShieldOf -> if (!s.shieldsHidden) update { it.copy(dragging = what, selectedShield = what.id, selectedSource = false, lastShieldId = what.id, tab = if (it.drillRunning) it.tab else Tab.SHIELD) }
        }
    }

    private fun onSourceMoved(src: SourceUi?) {
        update { it.copy(dragging = null, source = src ?: it.source) }
        if (src != null) DdLog.i("MOVE source foot=${src.foot} plane=${src.surface} face=${fmt(src.faceDeg)}")
    }

    private fun onShieldMoved(moved: Shield?, snapped: Boolean) {
        // a drag never ends inside the user's clearance (taste profile D14); a snapped point is clear already
        val shield = moved?.let { m -> if (snapped) m else shieldLanding(_state.value, m.center).let { p -> m.copy(x = p.x, z = p.z) } }
        update { s -> s.copy(dragging = null, shields = if (shield == null) s.shields else s.shields.map { if (it.id == shield.id) shield else it }) }
        if (shield != null) {
            DdLog.i("MOVE shield id=${shield.id} at=(${fmt(shield.x)}, ${fmt(shield.z)}) face=${fmt(shield.faceDeg)} snapped=$snapped")
            if (snapped) {
                showSnack(SnackKind.SNAPPED)
                _effects.tryEmit(DoseEffect.Feedback("snap"))
            }
        }
    }

    private fun onPlaceSource() {
        val s = _state.value
        if (!s.ready || s.drillRunning) return
        if (s.source != null) removeSource() else update { it.copy(aiming = if (it.aiming == Aiming.SOURCE) Aiming.NONE else Aiming.SOURCE, menuOpen = false) }
    }

    private fun removeSource() {
        update { it.copy(source = null, cumulative = 0f, reading = null, selectedSource = false, selectedShield = null, aiming = Aiming.SOURCE, drill = null, coach = false, tab = Tab.SOURCE) }
        DdLog.i("SOURCE removed")
    }

    /** Places the source on [plane] at [point] (design `rules.place`): a table snaps to its centre within 15 cm. */
    fun placeSourceAt(plane: PlaneSurface, point: Vec3, faceDeg: Float? = null, frameEx: P2? = null): SourceUi {
        val s = _state.value
        var p = plane.project(point)
        var snapped = false
        if (plane.semantic == TABLE) {
            val c = plane.centroid
            if (P2(p.x - c.x, p.z - c.z).length <= SNAP_TABLE_M) {
                p = plane.project(c)
                snapped = true
            } else p = plane.clampInside(p, POT_INSET_M)
        }
        val planeY = if (plane.semantic == FLOOR) s.floorY ?: p.y else p.y
        val head = s.head
        if (plane.semantic == FLOOR && head != null) {
            // the pot on the floor stays 0.6 m clear of the user (taste profile D14); on a desk it stands where the desk is
            val q = Clearance.pushOut(P2(p.x, p.z), P2.of(head), Clearance.SMALL_M, s.heading ?: P2(0f, -1f))
            p = Vec3(q.x, p.y, q.z)
        }
        val ex = frameEx ?: head?.let { P2(it.x - p.x, it.z - p.z).takeIf { d -> d.length > 0.05f }?.normalized() } ?: P2(0f, 1f)
        val face = faceDeg ?: Math.toDegrees(atan2(ex.z.toDouble(), ex.x.toDouble())).toFloat()
        val src = SourceUi(p.x, p.z, planeY, plane.semantic, plane.id, face, ex)
        val firstTime = !s.settings.coachSeen
        update { it.copy(source = src, aiming = Aiming.NONE, selectedSource = false, selectedShield = null, tab = Tab.SOURCE, coach = firstTime, cumulative = 0f) }
        DdLog.i("PLACE source plane=${plane.label} foot=${src.foot} y=${fmt(planeY)} snapped=$snapped ex=${src.frameEx}")
        if (snapped) _effects.tryEmit(DoseEffect.Feedback("snap"))
        if (firstTime) {
            coachJob?.cancel()
            coachJob =
                scope.launch {
                    delay(if (longSnacks) 60_000L else COACH_MS)
                    dismissCoach()
                }
        }
        return src
    }

    fun shieldSnapPoint(s: DoseUiState = _state.value): P2? = io.embodit.dosedistance.ui.dose.shieldSnapPoint(s)

    /** Places a new shield at floor point [p] (design `rules.placeShield`). */
    fun placeShieldAt(p: P2, faceDeg: Float? = null, tMm: Int? = null): Shield? {
        val s = _state.value
        val id = s.shields.freeId() ?: return null
        val src = s.source ?: return null
        val snap = shieldSnapPoint(s)
        val snapped = snap != null && (p - snap).length <= SNAP_SHIELD_M
        val at = if (snapped) snap!! else shieldLanding(s, p)
        val face =
            when {
                snapped -> Shield.faceTowards(at, src.foot)
                faceDeg != null -> faceDeg
                else -> s.head?.let { Shield.faceTowards(at, P2(it.x, it.z)) } ?: Shield.faceTowards(at, src.foot)
            }
        val shield = Shield(id, at.x, at.z, face, tMm ?: s.settings.thickness)
        update { it.copy(shields = it.shields + shield, lastShieldId = id, aiming = Aiming.NONE, selectedShield = null, selectedSource = false) }
        DdLog.i("PLACE shield id=$id at=(${fmt(at.x)}, ${fmt(at.z)}) face=${fmt(face)} t=${shield.tMm} snapped=$snapped")
        if (snapped) {
            showSnack(SnackKind.SNAPPED)
            _effects.tryEmit(DoseEffect.Feedback("snap"))
        }
        return shield
    }

    /** A shield released at [p] after a drag (debug `shield`): snaps like a drag release. */
    fun releaseShield(id: Int, p: P2, faceDeg: Float, tMm: Int?) {
        val s = _state.value
        val existing = s.shields.firstOrNull { it.id == id }
        if (existing == null) {
            placeShieldAt(p, faceDeg, tMm)
            return
        }
        val snap = shieldSnapPoint(s)
        val snapped = snap != null && (p - snap).length <= SNAP_SHIELD_M
        val at = if (snapped) snap!! else p
        val face = if (snapped) Shield.faceTowards(at, s.source!!.foot) else faceDeg
        onShieldMoved(existing.copy(x = at.x, z = at.z, faceDeg = face, tMm = tMm ?: existing.tMm, hidden = false), snapped)
    }

    private fun onPlaceShield() {
        val s = _state.value
        if (!s.interactive || s.source == null || s.shieldsHidden) return
        if (s.shields.size >= Shield.MAX) {
            showSnack(SnackKind.SHIELDS_FULL)
            DdLog.i("SHIELD full")
            return
        }
        update { it.copy(aiming = if (it.aiming == Aiming.SHIELD) Aiming.NONE else Aiming.SHIELD, selectedShield = null, selectedSource = false, menuOpen = false) }
    }

    private fun onDeleteShield(id: Int?) {
        val s = _state.value
        if (id == null || s.shields.none { it.id == id } || !s.ready) return
        update { st -> st.copy(shields = st.shields.filter { it.id != id }, selectedShield = st.selectedShield.takeIf { it != id }, lastShieldId = st.shields.lastOrNull { it.id != id }?.id, dragging = null) }
        DdLog.i("SHIELD deleted id=$id")
    }

    /**
     * "Bring to front" from the shield list (taste profile D9 / D14): 1.2 m ahead of the head along its level
     * heading, facing the user, shown again and selected; reachable while standing inside the shield.
     */
    fun shieldToFront(id: Int): Shield? {
        val s = _state.value
        val old = s.shields.firstOrNull { it.id == id } ?: return null
        if (!s.ready || s.shieldsHidden) return null
        val head = s.head?.let { P2.of(it) } ?: return null
        val heading = s.heading ?: s.source?.let { (it.foot - head).normalized() } ?: P2(0f, -1f)
        val at = Clearance.inFront(head, heading)
        val moved = old.copy(x = at.x, z = at.z, faceDeg = Shield.faceTowards(at, head), hidden = false)
        update { st -> st.copy(shields = st.shields.map { if (it.id == id) moved else it }, selectedShield = id, selectedSource = false, lastShieldId = id, aiming = Aiming.NONE, dragging = null, tab = if (st.drillRunning) st.tab else Tab.SHIELD) }
        DdLog.i("SHIELD front id=$id at=(${fmt(at.x)}, ${fmt(at.z)}) head=(${fmt(head.x)}, ${fmt(head.z)})")
        return moved
    }

    private fun onShieldHidden(id: Int, hidden: Boolean) {
        val s = _state.value
        if (!s.ready || s.shields.none { it.id == id }) return
        update { st -> st.copy(shields = st.shields.map { if (it.id == id) it.copy(hidden = hidden) else it }, selectedShield = if (hidden && st.selectedShield == id) null else st.selectedShield, dragging = null) }
        DdLog.i("SHIELD id=$id hidden=$hidden")
    }

    private fun onShieldListTapped(id: Int) {
        val s = _state.value
        val sh = s.shields.firstOrNull { it.id == id } ?: return
        if (!s.interactive || s.shieldsHidden) return
        if (sh.hidden) return onShieldHidden(id, false).also { update { it.copy(selectedShield = id, selectedSource = false, lastShieldId = id) } }
        update { it.copy(selectedShield = if (it.selectedShield == id) null else id, selectedSource = false, lastShieldId = id, aiming = Aiming.NONE) }
        DdLog.i("SELECT shield id=$id (list)")
    }

    private fun onThickness(step: Int) {
        val s = _state.value
        val current = s.thicknessShown
        val i = (Dose.THICKNESSES.indexOf(current).takeIf { it >= 0 } ?: 2) + step
        val next = Dose.THICKNESSES[i.coerceIn(0, Dose.THICKNESSES.size - 1)]
        if (next == current) return
        val target = s.thicknessTarget
        if (target != null) update { st -> st.copy(shields = st.shields.map { if (it.id == target.id) it.copy(tMm = next) else it }) }
        saveSettings { it.copy(thickness = next) }
        DdLog.i("THICKNESS ${target?.id ?: "next"} -> $next")
    }

    private fun clearAll() {
        update { it.copy(clearDialog = false, source = null, shields = emptyList(), cumulative = 0f, reading = null, selectedSource = false, selectedShield = null, aiming = Aiming.SOURCE, drill = null, lastShieldId = null, coach = false, tab = Tab.SOURCE) }
        showSnack(SnackKind.CLEARED)
        DdLog.i("CLEAR all")
    }

    private fun dismissCoach() {
        coachJob?.cancel()
        if (_state.value.coach || !_state.value.settings.coachSeen) {
            update { it.copy(coach = false) }
            saveSettings { it.copy(coachSeen = true) }
        }
    }

    // =========================================================================================
    // helpers
    // =========================================================================================

    private fun saveSettings(change: (Settings) -> Settings) {
        update { it.copy(settings = change(it.settings)) }
        store.save(_state.value.settings)
    }

    fun showSnack(kind: SnackKind) {
        val seq = ++snackSeq
        update { it.copy(snack = SnackUi(kind, seq)) }
        DdLog.i("SNACK $kind")
        snackJob?.cancel()
        if (kind == SnackKind.SCANNING || kind == SnackKind.LOST) return
        snackJob =
            scope.launch {
                delay(if (longSnacks) 60_000L else SNACK_MS)
                if (_state.value.snack?.seq == seq) update { it.copy(snack = null) }
            }
    }

    private fun update(change: (DoseUiState) -> DoseUiState) {
        val next = change(_state.value)
        _state.value = next
        val ds = next.designState
        if (ds != lastLogged) {
            lastLogged = ds
            DdLog.i(stateLine(next))
        }
    }

    /** One `STATE` line (QA compares its numbers with design `model.unitTests`). */
    fun stateLine(s: DoseUiState = _state.value): String {
        val r = s.reading
        val src = s.source
        val local = if (src != null && s.head != null) src.frame.local(s.head) else null
        val shields = s.shields.joinToString(";") { sh ->
            val l = src?.frame?.local(Vec3(sh.x, src.core.y, sh.z))
            "#${sh.id} t=${sh.tMm} at=(${fmt(l?.x ?: sh.x)},${fmt(l?.z ?: sh.z)}) yaw=${fmt(src?.frame?.localFace(sh.faceDeg) ?: sh.faceDeg)}${if (sh.hidden) " hidden" else ""}"
        }
        val d = s.drill
        return "STATE ${s.designState} iso=${s.isotope.tag} H=${r?.let { fmt(it.h) } ?: "-"} show=${r?.let { Dose.rateText(it.h) } ?: "-"} zone=${r?.zone ?: "-"} d=${r?.let { fmt(it.d) } ?: "-"} A=${r?.let { fmt(it.attenuation) } ?: "-"} blocked=${r?.takeIf { it.shielded }?.let { Dose.blockedText(it.attenuation) } ?: "-"} cum=${fmt(s.cumulative)} " +
            "head=${local?.let { "(${fmt(it.x)},${fmt(it.y)},${fmt(it.z)})" } ?: "-"} source=${src?.let { "${it.surface}(${fmt(it.x)},${fmt(it.planeY)},${fmt(it.z)})" } ?: "-"} shields=[$shields] sel=${if (s.selectedSource) "source" else s.selectedShield?.let { "shield$it" } ?: "-"} " +
            "aim=${s.aiming} tab=${s.tab} drill=${d?.let { "${it.kind.tag}:${it.phase} cum=${fmt(it.cum)} hStart=${fmt(it.hStart)} target=${fmt(it.target)} code=${it.code} read=${it.read} inSpot=${it.inSpot}" } ?: "-"} lost=${s.trackingLost} menu=${s.menuOpen} snack=${s.snack?.kind ?: "-"} coach=${s.coach} lang=${s.settings.language.tag} sound=${s.settings.geiger} heat=${s.settings.heat} snap=${s.settings.snap}"
    }

    /** Debug: change the state directly. */
    fun debugSet(change: (DoseUiState) -> DoseUiState) = update(change)

    fun debugCoachReset() = saveSettings { it.copy(coachSeen = false) }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DoseViewModel(PrefsSettingsStore(context.applicationContext), { DdGraph.head.blockReason() }) as T
    }

    companion object {
        const val TABLE = "TABLE"
        const val FLOOR = "FLOOR"
        const val POT_INSET_M = 0.07f
        const val SNACK_MS = 3000L
        const val COACH_MS = 10_000L
        const val BLOCK_GRACE_MS = 8000L
    }
}

