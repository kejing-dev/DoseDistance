package io.embodit.dosedistance.ui.dose.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.dosedistance.domain.Aim
import io.embodit.dosedistance.domain.AimSource
import io.embodit.dosedistance.domain.Clearance
import io.embodit.dosedistance.domain.Dose
import io.embodit.dosedistance.domain.DragLine
import io.embodit.dosedistance.domain.Drills
import io.embodit.dosedistance.domain.FacePanel
import io.embodit.dosedistance.domain.HeadPose
import io.embodit.dosedistance.domain.Heat
import io.embodit.dosedistance.domain.P2
import io.embodit.dosedistance.domain.PlaneSurface
import io.embodit.dosedistance.domain.PointerRay
import io.embodit.dosedistance.domain.Quat4
import io.embodit.dosedistance.domain.Reading
import io.embodit.dosedistance.domain.Rings
import io.embodit.dosedistance.domain.Shadow
import io.embodit.dosedistance.domain.Shield
import io.embodit.dosedistance.domain.SurfaceHit
import io.embodit.dosedistance.domain.Vec3
import io.embodit.dosedistance.domain.planDistance
import io.embodit.dosedistance.platform.DdGraph
import io.embodit.dosedistance.platform.DdLog
import io.embodit.dosedistance.ui.dose.Aiming
import io.embodit.dosedistance.ui.dose.DoseEvent
import io.embodit.dosedistance.ui.dose.DoseUiState
import io.embodit.dosedistance.ui.dose.DragWhat
import io.embodit.dosedistance.ui.dose.DrillKind
import io.embodit.dosedistance.ui.dose.SNAP_SHIELD_M
import io.embodit.dosedistance.ui.dose.SNAP_TABLE_M
import io.embodit.dosedistance.ui.dose.SourceUi
import io.embodit.dosedistance.ui.dose.UNSNAP_SHIELD_M
import io.embodit.dosedistance.ui.dose.components.PanelSizes
import io.embodit.dosedistance.ui.dose.shieldLanding
import io.embodit.dosedistance.ui.dose.shieldSnapPoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: DoseScene? = null
}

/** The one per-frame ECS system of the app: the dose rate at the head is computed here every frame (design `container.notes`). */
class DoseFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/** Ids of the attachment panels; the Compose side declares exactly these, once. */
object PanelIds {
    const val DOSIMETER = "dosimeter"
    const val COMPACT = "compact"
    const val MENU = "menu"
    const val DIALOG = "dialog"
    const val SNACK = "snack"
    const val COACH = "coach"
    const val RESULT = "result"
    const val PLATE_SOURCE = "plateSource"
    const val PLATE_SPOT = "plateSpot"

    fun plateShield(id: Int) = "plateShield$id"

    fun plateRing(i: Int) = "plateRing$i"

    val plates: List<String> = listOf(PLATE_SOURCE, plateShield(1), plateShield(2), plateRing(0), plateRing(1), plateRing(2), PLATE_SPOT)

    val pool: List<String> = listOf(DOSIMETER, COMPACT, MENU, DIALOG, SNACK, COACH, RESULT) + plates
}

/**
 * The 3D side of the app: the source, the shields, the isodose rings, the safe shadows, the placement aids,
 * the tap and drag targets and the placement of the pre-built panels. The dose rate at the head is
 * computed here every frame (one distance, a few multiplications); rings and shadows are rebuilt at most
 * 10 times a second when their inputs change (design `container.notes`).
 *
 * Rules (Spatial Ruler / cam-coverage): one calibrated stage root; Compose state is rendered here through
 * [render] (idempotent); per-frame work happens in [onFrame] and reaches the ViewModel only as 10 Hz
 * [DoseEvent.Tick]s; everything started in [attach] is stopped in [detach]. Main thread only.
 */
class DoseScene(private val sink: (DoseEvent) -> Unit) {

    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val planes = DdGraph.planes
    private val head = DdGraph.head
    private val stick = DdGraph.stick
    private val eyes = DdGraph.eyes
    private val geiger = DdGraph.geiger

    private var content: SpatialViewContent? = null
    private var contentProbe: Entity? = null
    private var stageRoot: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var materials: DdMaterials? = null
    private var meshes: DdMeshes? = null
    private var colliders: RoomColliders? = null
    private var scope: CoroutineScope? = null
    private var buildJob: Job? = null
    private var attached = false
    private var built = false
    private var paused = false
    private var ui = DoseUiState()
    private var pose = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var frames = 0L
    private var lastFrameMs = -1L
    private var lastLogMs = 0L
    private var lastTrackingLost = false
    private var lastPanelPressMs = -1L
    private var lastDragEndMs = -1L
    private var lastFrameDtMs = 0L
    private var lastSlowMeshMs = 0L

    /** Mesh creations waiting for a frame, keyed so that a newer request for the same thing replaces the older. */
    private val meshWork = LinkedHashMap<String, () -> Unit>()

    // views
    private var sourceView: SourceView? = null
    private val shieldViews = HashMap<Int, ShieldView>()
    private val shadowViews = HashMap<Int, ShadowView>()
    private var ringsView: RingsView? = null
    private var heatView: HeatView? = null
    private val fadedShields = HashSet<Int>()
    private var aids: AidsView? = null
    private var trefoil: Material? = null
    private var pb: Material? = null
    private val codeMaterials = HashMap<String, Material?>()

    // the reading at the head, every frame
    private var judged: Vec3? = null
    private var judgedForward: Vec3 = Vec3.AHEAD

    // what aims this frame (v0.1.2, taste profile D1): controller ray, eyes, or the head as the last fallback
    private var aim: Pair<PointerRay, AimSource>? = null
    private var eyeRay: PointerRay? = null
    private var lastAimSource: AimSource? = null
    private var reading: Reading? = null
    private var doseAcc = 0f
    private var gazeMs = 0L
    private var lastTickMs = 0L
    private var lastGeigerMs = 0L
    private var geomKey: String? = null
    private var geomHeadY = Float.NaN
    private var lastGeomMs = 0L
    private var heatKey: String? = null
    private var lastHeatMs = 0L
    private var lastPlanesSent = -1

    // live placement (drag / stick), never through Compose state
    private class Drag(val what: DragWhat, val press: Vec3) {
        var dir: Vec3? = null
        var equivalent = 0f
        var hand = Vec3.ZERO
        var prevHand = Vec3.ZERO
        var totalPx = Vec3.ZERO
        var begun = false
        var moves = 0
        var lastLogMs = 0L
    }

    private class Turn(val what: DragWhat) {
        var neutralSinceMs = -1L
        var frames = 0
    }

    private var drag: Drag? = null
    private var turn: Turn? = null
    private var liveSource: SourceUi? = null
    private var liveShield: Shield? = null
    private var liveSnapped = false
    private var smoothRate = 0f
    private var awaitCommit: Long? = null

    // panel follow (design `panels[].placement`)
    private val dosimeterFollow = HorizontalFollow("dosimeter", { h -> restAt(h, DOSIMETER_LEFT_DEG, -DOSIMETER_DOWN_DEG, DOSIMETER_M) }, DOSIMETER_WINDOW_DEG)
    private val snackFollow = HorizontalFollow("snack", { h -> restAt(h, 0f, gazePitchDeg(h) + SNACK_UP_DEG, SNACK_M) }, 25f)

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity()
        runCatching { root.setName("stageRoot") }
        content.addEntity(root)
        val probe = Entity()
        runCatching { probe.setName("contentProbe") }
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin (0.256 m off in z, Spatial Ruler):
        // move the root once so that its local space equals Stage space.
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        DdLog.i("STAGE root offset=${DdLog.pos(offset.domain())}")

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.DOSIMETER, PanelSizes.DOSIMETER_W, PanelSizes.DOSIMETER_H, PANEL_SCALE)
        rig.world(PanelIds.COMPACT, PanelSizes.DOSIMETER_W, PanelSizes.COMPACT_H, PANEL_SCALE)
        rig.world(PanelIds.MENU, PanelSizes.MENU_W, PanelSizes.MENU_H, PANEL_SCALE)
        rig.world(PanelIds.DIALOG, PanelSizes.DIALOG_W, PanelSizes.DIALOG_H, DIALOG_SCALE)
        rig.world(PanelIds.SNACK, PanelSizes.SNACK_W, PanelSizes.SNACK_H, SNACK_SCALE)
        rig.world(PanelIds.COACH, PanelSizes.COACH_W, PanelSizes.COACH_H, COACH_SCALE)
        rig.world(PanelIds.RESULT, PanelSizes.RESULT_W, PanelSizes.RESULT_H, DIALOG_SCALE)
        for (id in PanelIds.plates) rig.world(id, PanelSizes.PLATE_W, PanelSizes.PLATE_H, PLATE_SCALE)
        head.start()
        stick.start()
        eyes.start()
        ActiveScene.scene = this
        attached = true
        bindPanels(attachments)
        startSensing()
        buildJob =
            scope?.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) DdLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
        DdLog.i("SCENE attached")
    }

    /** Materials and meshes, one per yielded frame (a mesh costs about a frame at idle fps), then the fixed views. */
    private suspend fun build(root: Entity, t0: Long) {
        delay(300)
        val shared = SharedResources()
        this.shared = shared
        val m = DdMaterials(shared)
        materials = m
        pace(ENTITY_GAP_SLOW_MS)
        val meshes = DdMeshes()
        meshes.build(shared) { pace(MESH_GAP_SLOW_MS) }
        this.meshes = meshes
        pace(ENTITY_GAP_SLOW_MS)
        trefoil = m.textured(Decals.trefoil(), "trefoil")
        pace(ENTITY_GAP_SLOW_MS)
        pb = m.textured(Decals.pb(), "pb")
        pace(ENTITY_GAP_SLOW_MS)
        val colliders = RoomColliders(root)
        colliders.buildBackstop()
        this.colliders = colliders
        pace(ENTITY_GAP_SLOW_MS)
        colliders.sync(planes.all)
        pace(ENTITY_GAP_SLOW_MS)
        heatView = HeatView(root, m)
        ringsView = RingsView(root, m)
        aids = AidsView(root, meshes, m)
        built = true
        DdLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - t0} trefoil=${trefoil != null} pb=${pb != null}")
        render(ui)
        sendPlanes()
        sink(DoseEvent.SceneReady)
    }

    /**
     * Gives the main thread back between expensive steps: one frame when frames are fast (headset), [slowMs] while
     * the engine runs at the idle emulator's 1 - 2 fps or has not drawn yet (figure-model / cam-coverage finding).
     */
    private suspend fun pace(slowMs: Long) {
        delay(if (lastFrameDtMs in 1..FAST_FRAME_MS) 16L else slowMs)
    }

    private fun startSensing() {
        planes.start {
            colliders?.sync(planes.all)
            sendPlanes()
        }
    }

    /** The floor height (lowest FLOOR plane) and the tables, for the ViewModel. */
    private fun sendPlanes() {
        val all = planes.all
        val floors = all.filter { it.semantic == FLOOR && it.isHorizontal }
        val floorY = floors.minOfOrNull { it.centroid.y }
        val tables = all.filter { it.semantic == TABLE && it.isHorizontal }
        val n = all.size
        if (n == lastPlanesSent && built) return
        lastPlanesSent = n
        DdLog.i("PLANES n=$n floorY=${floorY?.let { String.format(Locale.US, "%.3f", it) }} tables=${tables.size}")
        sink(DoseEvent.PlanesChanged(floorY, tables))
    }

    /** The Stage went to the background: stop every tracker (design `container.lifecycle`). */
    fun pause() {
        if (paused || !attached) return
        paused = true
        planes.stop()
        head.stop()
        stick.stop()
        eyes.stop()
        geiger.stop()
        cancelLive()
        DdLog.i("SCENE paused")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        head.start()
        stick.start()
        eyes.start()
        lastPlanesSent = -1
        startSensing()
        DdLog.i("SCENE resumed")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        meshWork.clear()
        buildJob?.cancel()
        scope?.cancel()
        scope = null
        planes.stop()
        head.stop()
        stick.stop()
        eyes.stop()
        geiger.stop()
        panelShown.clear()
        runCatching { sourceView?.destroy() }
        shieldViews.values.forEach { runCatching { it.destroy() } }
        shieldViews.clear()
        shadowViews.values.forEach { runCatching { it.destroy() } }
        shadowViews.clear()
        runCatching { ringsView?.destroy() }
        runCatching { heatView?.destroy() }
        heatView = null
        fadedShields.clear()
        runCatching { colliders?.clear() }
        colliders = null
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        materials = null
        meshes = null
        sourceView = null
        ringsView = null
        aids = null
        trefoil = null
        pb = null
        codeMaterials.clear()
        stageRoot = null
        contentProbe = null
        rig = null
        content = null
        built = false
        hasHead = false
        drag = null
        turn = null
        liveSource = null
        liveShield = null
        geomKey = null
        heatKey = null
        dosimeterFollow.reset()
        snackFollow.reset()
        DdLog.i("SCENE detached")
    }

    // =========================================================================================
    // state -> entities
    // =========================================================================================

    fun render(state: DoseUiState) {
        val before = ui
        ui = state
        val root = stageRoot ?: return
        val rig = rig ?: return
        if (!built) {
            rig[PanelIds.COMPACT]?.show()
            return
        }
        val m = materials ?: return
        val meshes = meshes ?: return
        // source
        if (state.source != null && sourceView == null) sourceView = SourceView(root, meshes, m, trefoil)
        if (state.source == null && sourceView != null) {
            sourceView?.destroy()
            sourceView = null
        }
        // shields
        val ids = state.shields.map { it.id }.toSet()
        for (id in shieldViews.keys.filter { it !in ids }) shieldViews.remove(id)?.destroy()
        for (id in shadowViews.keys.filter { it !in ids }) {
            shadowViews.remove(id)?.destroy()
            meshWork.keys.removeAll { it.startsWith("shadow$id:") }
        }
        for (s in state.shields) {
            if (s.id !in shieldViews) shieldViews[s.id] = ShieldView(root, s.id, meshes, m, pb)
            if (s.id !in shadowViews) shadowViews[s.id] = ShadowView(root, m, s.id)
        }
        // a committed drag / turn ends the live override once the state carries it
        awaitCommit?.let {
            if (state.source != before.source || state.shields != before.shields) {
                awaitCommit = null
                if (turn == null && drag == null) clearLive()
            }
        }
        if (before.dragging != null && state.dragging == null && drag == null && turn == null && awaitCommit == null) clearLive()
        // the pot's code (drill 2)
        val code = state.drill?.takeIf { it.kind == DrillKind.TIME && it.running }?.code ?: ""
        sourceView?.code(codeMaterial(code))
        // the state changes 10 times a second (Tick): rings and shadows rebuild only when their key changes (geometry)
        placeViews()
        panels(state)
    }

    private fun codeMaterial(code: String): Material? {
        if (code.isEmpty()) return codeMaterials.getOrPut("") { materials?.textured(Decals.code(""), "code-blank") }
        return codeMaterials.getOrPut(code) { materials?.textured(Decals.code(code), "code-$code") }
    }

    private fun effSource(): SourceUi? = liveSource ?: ui.source

    private fun effShields(): List<Shield> {
        val l = liveShield
        return ui.visibleShields.map { if (l != null && it.id == l.id) l else it }
    }

    /** Poses and looks of the source and the shields (live overrides first). */
    private fun placeViews() {
        val state = ui
        val floorY = state.floorY ?: return
        val src = effSource()
        val dragWhat = drag?.takeIf { it.begun }?.what ?: state.dragging
        sourceView?.let { v ->
            if (src != null) {
                v.place(src, lifted = dragWhat == DragWhat.Source)
                v.look(selected = state.selectedSource || dragWhat == DragWhat.Source, locked = state.sourceLocked)
            }
        }
        val crossed = reading?.crossed ?: emptySet()
        val hidden = state.shieldsHidden
        val live = liveShield
        for (s0 in state.shields) {
            val s = if (live != null && live.id == s0.id) live else s0
            val v = shieldViews[s.id] ?: continue
            v.show(!hidden && !s.hidden)
            if (hidden || s.hidden) continue
            val lifted = (dragWhat as? DragWhat.ShieldOf)?.id == s.id
            v.place(s, floorY, lifted)
            v.look(selected = state.selectedShield == s.id || lifted, shielding = s.id in crossed)
        }
        if (hidden) shieldViews.values.forEach { it.show(false) }
    }

    private fun panels(state: DoseUiState) {
        val rig = rig ?: return
        val full = state.fullPanel
        if (full) {
            rig[PanelIds.COMPACT]?.hide()
            rig[PanelIds.DOSIMETER]?.show()
        } else {
            rig[PanelIds.DOSIMETER]?.hide()
            rig[PanelIds.COMPACT]?.show()
        }
        if (state.menuOpen) rig[PanelIds.MENU]?.show() else rig[PanelIds.MENU]?.hide()
        val dialog = rig[PanelIds.DIALOG]
        if (state.dialogOpen && dialog?.shown != true && hasHead) dialog?.show(restAt(pose, 0f, gazePitchDeg(pose), DIALOG_M) + Vec3(0f, -0.04f, 0f))
        else if (!state.dialogOpen) dialog?.hide()
        if (state.snack != null) rig[PanelIds.SNACK]?.show() else {
            rig[PanelIds.SNACK]?.hide()
            snackFollow.reset()
        }
        val result = rig[PanelIds.RESULT]
        if (state.resultShown && !state.dialogOpen && result?.shown != true && hasHead) result?.show(restAt(pose, 0f, gazePitchDeg(pose) - 12f, RESULT_M))
        else if (!state.resultShown || state.dialogOpen) result?.hide()
        if (!coachOn(state)) rig[PanelIds.COACH]?.hide()
        if (state.source == null) for (id in PanelIds.plates) rig[id]?.hide()
    }

    private fun coachOn(s: DoseUiState) = s.coach && s.source != null && !s.menuOpen && !s.dialogOpen && !s.trackingLost

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        frames++
        head.pose()?.let {
            pose = it
            hasHead = it.position.length > 1e-3f
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(pose)
        if (!attached || paused) return
        val dt = if (lastFrameMs < 0) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.25f)
        lastFrameDtMs = if (lastFrameMs < 0) 0L else now - lastFrameMs
        pollTracking(now)
        if (built) {
            runMeshWork(now)
            updateAim(now)
            stepStick(now, dt)
            if (drag != null || turn != null || liveSource != null || liveShield != null) placeViews()
            judge(now, dt)
            geometry(now)
            drawAids(now)
        }
        if (hasHead) placePanels(now)
        rig.faceViewer(pose.position)
        lastFrameMs = now
        if (now - lastLogMs > LOG_MS) {
            lastLogMs = now
            DdLog.i("FRAME n=$frames head=${DdLog.pos(pose.position)} fwd=${DdLog.pos(pose.forward)} judged=${DdLog.pos(judged)} H=${reading?.h?.let { String.format(Locale.US, "%.2f", it) } ?: "-"} planes=${planes.all.size} stick=${stick.status()} geiger=${geiger.clicks.get()}")
        }
    }

    /** At most [MESH_PER_FRAME] mesh creations per frame (a mesh waits for the render thread). */
    private fun runMeshWork(now: Long) {
        // slow frames (idle emulator, 1 - 4 fps): one mesh every SLOW gap; middling frames (a loaded emulator, 7 - 25 fps):
        // one per frame (the floor map's 8 meshes took 20 s through the slow gap at 12 fps); fast frames: two per frame
        if (lastFrameDtMs in (FAST_FRAME_MS + 1)..MID_FRAME_MS) {
            val key = meshWork.keys.firstOrNull() ?: return
            meshWork.remove(key)?.let { w -> runCatching { w() }.onFailure { DdLog.w("MESH $key failed: $it") } }
            return
        }
        if (lastFrameDtMs !in 1..FAST_FRAME_MS) {
            if (meshWork.isEmpty() || now - lastSlowMeshMs < MESH_GAP_SLOW_MS) return
            lastSlowMeshMs = now
            val key = meshWork.keys.first()
            meshWork.remove(key)?.let { w -> runCatching { w() }.onFailure { DdLog.w("MESH $key failed: $it") } }
            return
        }
        var n = 0
        while (n < MESH_PER_FRAME && meshWork.isNotEmpty()) {
            val key = meshWork.keys.first()
            val work = meshWork.remove(key) ?: continue
            runCatching { work() }.onFailure { DdLog.w("MESH $key failed: $it") }
            n++
        }
    }

    private fun pollTracking(now: Long) {
        val lost = head.lost(now)
        if (lost != lastTrackingLost) {
            lastTrackingLost = lost
            sink(DoseEvent.TrackingChanged(lost))
        }
    }

    /**
     * The head used for every judgement: the debug injection (relative to the source frame) wins
     * (design `qaHooks.head`); its forward looks at the source with the injected pitch.
     */
    private fun judgeHead(src: SourceUi?): Pair<Vec3, Vec3> {
        val rel = DdGraph.debugHead
        if (rel != null && src != null) {
            val h = src.frame.world(rel.x, rel.y, rel.z)
            val to = P2(src.x - h.x, src.z - h.z).let { if (it.length < 1e-3f) -src.frameEx else it.normalized() }
            val pitch = DdGraph.debugPitchDeg
            val f =
                if (pitch != null) {
                    val r = Math.toRadians(pitch.toDouble())
                    Vec3((to.x * cos(r)).toFloat(), sin(r).toFloat(), (to.z * cos(r)).toFloat())
                } else (src.core - h).normalized()
            return h to f
        }
        return pose.position to pose.forward
    }

    /** The reading at the head, the dose integrated over the frame, the gaze on the pot; a 10 Hz tick to the ViewModel. */
    private fun judge(now: Long, dt: Float) {
        val state = ui
        val src = effSource()
        val floorY = state.floorY
        val (h, f) = judgeHead(src)
        judged = if (hasHead || DdGraph.debugHead != null) h else null
        judgedForward = f
        val lost = state.trackingLost
        val prevCrossed = reading?.crossed
        if (src != null && floorY != null && judged != null && !lost) {
            val r = Reading.at(state.isotope, src.core, h, effShields(), floorY)
            reading = r
            doseAcc += r.h * dt / 3600f
            // reading the code in the pot: the eye gaze when the eyes are tracked, else the head (design default)
            val e = eyeRay?.takeIf { DdGraph.debugHead == null }
            val looks = if (e != null) Drills.gazesAt(e.origin, e.dir, src.mouth) else Drills.gazesAt(h, f, src.mouth)
            gazeMs = if (looks) gazeMs + (dt * 1000f).toLong() else 0L
        } else if (src == null) {
            reading = null
            gazeMs = 0L
        }
        if (reading?.crossed != prevCrossed) placeViews()
        if (judged != null) fadeShields(h)
        if (now - lastTickMs >= TICK_MS) {
            lastTickMs = now
            val heading = P2(f.x, f.z).takeIf { it.length > 0.1f }?.normalized() ?: if (hasHead) pose.level() else null
            sink(DoseEvent.Tick(if (src != null) reading else null, doseAcc, judged, gazeMs, geiger.clicks.get(), now, heading, aim?.second))
            doseAcc = 0f
        }
        if (now - lastGeigerMs >= GEIGER_MS) {
            lastGeigerMs = now
            val on = state.settings.geiger && src != null && !lost && reading != null
            if (on) {
                geiger.start()
                val hh = reading!!.h
                geiger.set(Dose.clicksPerSecond(hh), hh >= Dose.TONE_FROM)
            } else geiger.stop()
        }
    }

    /**
     * Shields the user stands in or right at turn see-through (taste profile D14): head within
     * [Clearance.FADE_IN_M] of the panel on the floor, back above [Clearance.FADE_OUT_M].
     */
    private fun fadeShields(head: Vec3) {
        val hp = P2(head.x, head.z)
        for (s in effShields()) {
            val v = shieldViews[s.id] ?: continue
            val d = s.planDistance(hp)
            val was = s.id in fadedShields
            val on = if (was) d < Clearance.FADE_OUT_M else d < Clearance.FADE_IN_M
            if (on != was) {
                if (on) fadedShields += s.id else fadedShields -= s.id
                DdLog.i(String.format(Locale.US, "SHIELD fade id=%d on=%b d=%.2f", s.id, on, d))
            }
            v.fade(on)
        }
    }

    fun fadedShieldIds(): Set<Int> = fadedShields.toSet()

    /** Rings and shadows: rebuilt when the source, the shields, the isotope or the head height (> 10 cm) change, at most 10 Hz. */
    private fun geometry(now: Long) {
        val state = ui
        val rings = ringsView ?: return
        val src = effSource()
        val floorY = state.floorY
        if (src == null || floorY == null) {
            if (geomKey != "none") {
                geomKey = "none"
                heatKey = null
                for ((k, w) in rings.clearPlan()) meshWork[k] = w
                heatView?.let { hv -> for ((k, w) in hv.clearPlan()) meshWork[k] = w }
                for ((id, v) in shadowViews) for ((k, w) in v.plan("shadow$id", null, 0f)) meshWork[k] = w
            }
            return
        }
        val hy = judged?.y ?: (src.core.y + 0.85f)
        if (geomHeadY.isNaN() || abs(hy - geomHeadY) > HEAD_STEP_M) geomHeadY = hy
        val shields = effShields()
        val key = buildString {
            append(state.isotope.tag).append('|').append(src.x).append(',').append(src.z).append(',').append(src.planeY)
            append('|').append(geomHeadY).append('|').append(state.settings.rings).append('|').append(state.shieldsHidden).append('|').append(state.settings.heat)
            for (s in shields) append('|').append(s)
        }
        heat(key, now, src, shields, floorY)
        if (key == geomKey || now - lastGeomMs < GEOM_MS) return
        geomKey = key
        lastGeomMs = now
        rings.show(state.settings.rings)
        if (state.settings.rings) {
            val segs = Rings.segments(state.isotope, src.core, geomHeadY, shields, floorY)
            for ((k, w) in rings.plan(segs, src.foot, floorY)) meshWork[k] = w
        }
        for ((id, v) in shadowViews) {
            val s = shields.firstOrNull { it.id == id }
            val q = if (s == null || state.shieldsHidden) null else Shadow.quad(src.foot, src.core.y - floorY, s, geomHeadY - floorY)
            v.show(s != null && !state.shieldsHidden, withFill = !state.settings.heat)
            for ((k, w) in v.plan("shadow$id", q, floorY)) meshWork[k] = w
        }
    }

    /**
     * The floor map: same inputs as the rings, but its 8 meshes rebuild at most every [HEAT_LIVE_MS] while a
     * drag or a stick turn moves something (8 mesh swaps per rebuild), every [GEOM_MS] otherwise.
     */
    private fun heat(key: String, now: Long, src: SourceUi, shields: List<Shield>, floorY: Float) {
        val hv = heatView ?: return
        val state = ui
        hv.show(state.settings.heat)
        if (!state.settings.heat || key == heatKey) return
        val live = drag != null || turn != null
        if (now - lastHeatMs < (if (live) HEAT_LIVE_MS else GEOM_MS)) return
        heatKey = key
        lastHeatMs = now
        val t0 = SystemClock.elapsedRealtime()
        val cells = Heat.cells(state.isotope, src.core, geomHeadY, shields, floorY)
        for ((k, w) in hv.plan(cells, src.foot, floorY)) meshWork[k] = w
        val ms = SystemClock.elapsedRealtime() - t0
        if (ms > 8) DdLog.i("HEAT cells=${cells.size} ms=$ms")
    }

    /**
     * Picks this frame's aiming ray (controller in use > eyes > head, [Aim.choose]) and turns a trigger press while
     * aiming into a placement at the ghost's point (the eye + pinch path places through the system tap).
     */
    private fun updateAim(now: Long) {
        eyeRay = eyes.ray(now)
        aim = Aim.choose(stick.ray(now), stick.active(now), eyeRay, if (hasHead) pose else null)
        val source = aim?.second
        if (source != lastAimSource) {
            lastAimSource = source
            DdLog.i("AIM with=$source eyes=${eyes.status()} stick=${stick.status()}")
        }
        if (stick.takePress()) onTrigger()
        // a drag held with the controller follows its ray every frame (system drag events may come sparsely)
        drag?.takeIf { it.begun && source == AimSource.CONTROLLER }?.let { d -> d.dir?.let { moveDrag(d, it, now) } }
    }

    private fun onTrigger() {
        val s = ui
        if (!s.interactive || s.menuOpen || s.aiming == Aiming.NONE) return
        val hit = aimHit(floorOnly = s.aiming == Aiming.SHIELD)
        DdLog.i("TRIGGER aim=${s.aiming} with=${aim?.second} hit=${hit?.plane?.label} at=${DdLog.pos(hit?.point)}")
        if (hit != null) sink(DoseEvent.PlaneTapped(hit))
    }

    /**
     * Where the aiming ray lands on a TABLE / FLOOR plane (the ghost follows it, the trigger places there); with
     * [floorOnly] the floor level itself counts where no floor plane was scanned. Never the head direction unless
     * neither the controller nor the eyes are available ([AimSource.HEAD], the panel says so).
     */
    private fun aimHit(floorOnly: Boolean = false): SurfaceHit? {
        val ray = aim?.first ?: return null
        var best: SurfaceHit? = null
        for (p in planes.all) {
            if (!p.isHorizontal) continue
            if (floorOnly && p.semantic != FLOOR) continue
            val hit = p.hit(ray.origin, ray.dir, 8f) ?: continue
            if (best == null || hit.distance < best.distance) best = hit
        }
        if (best == null && floorOnly) {
            val floor = floorPlane() ?: return null
            val y = ui.floorY ?: return null
            val at = Aim.onLevel(ray, y) ?: return null
            best = SurfaceHit(at, Vec3.UP, ray.origin.distanceTo(at), floor)
        }
        return best
    }

    private fun drawAids(now: Long) {
        val a = aids ?: return
        val state = ui
        val floorY = state.floorY ?: 0f
        val src = effSource()
        // source ghost while aiming
        if (state.aiming == Aiming.SOURCE && state.interactive && !state.menuOpen) {
            val hit = debugAimHit() ?: aimHit()
            val ok = hit != null && (hit.plane.semantic == TABLE || hit.plane.semantic == FLOOR)
            val table = nearestTable(hit?.point ?: pose.position)
            val snapped = ok && table != null && hit!!.plane.id == table.id && P2(hit.point.x - table.centroid.x, hit.point.z - table.centroid.z).length <= SNAP_TABLE_M
            val at = if (snapped) table!!.project(table.centroid) else hit?.point
            a.source(at, ok, table?.let { it.project(it.centroid) }, snapped)
        } else a.hideSource()
        // shield aids
        val snap = shieldSnapPoint(state)
        val drill3 = state.drill?.takeIf { it.kind == DrillKind.SHIELD && it.running }
        when {
            src == null -> a.hideShield()
            state.aiming == Aiming.SHIELD && state.interactive -> {
                val p = DdGraph.debugAim?.let { (x, z) -> src.frame.floorPoint(x, z) } ?: aimHit(floorOnly = true)?.point?.let { P2(it.x, it.z) }
                if (p != null && snap == null) {
                    val at = shieldLanding(state, p)
                    val face = state.head?.let { Shield.faceTowards(at, P2(it.x, it.z)) } ?: Shield.faceTowards(at, src.foot)
                    a.shield(at, face, floorY, false, now, ghostAt = at, ghostFace = face)
                } else if (p == null || snap == null) a.shield(snap, snap?.let { Shield.faceTowards(it, src.foot) } ?: 0f, floorY, false, now)
                else {
                    val snapped = (p - snap).length <= SNAP_SHIELD_M
                    val at = if (snapped) snap else shieldLanding(state, p)
                    val face = if (snapped) Shield.faceTowards(at, src.foot) else state.head?.let { Shield.faceTowards(at, P2(it.x, it.z)) } ?: Shield.faceTowards(at, src.foot)
                    a.shield(at, face, floorY, snapped, now, ghostAt = at, ghostFace = face)
                }
            }
            liveShield != null || state.dragging is DragWhat.ShieldOf || drill3 != null -> {
                if (snap == null) a.hideShield()
                else {
                    val snappedShield = effShields().any { (it.center - snap).length < 0.01f }
                    a.shield(snap, Shield.faceTowards(snap, src.foot), floorY, snappedShield, now)
                }
            }
            else -> a.hideShield()
        }
        // drill 3 spot
        a.spot(drill3?.spot, floorY)
        // the drag's follow line (also the debug drag pose)
        val what = drag?.takeIf { it.begun }?.what ?: state.dragging
        val target: Vec3? =
            when (what) {
                DragWhat.Source -> src?.let { Vec3(it.x, it.planeY + 0.17f, it.z) }
                is DragWhat.ShieldOf -> effShields().firstOrNull { it.id == what.id }?.let { Vec3(it.x, floorY + 1.0f, it.z) }
                null -> null
            }
        a.follow(if (target != null && hasHead) pose.position + pose.right * 0.15f - Vec3(0f, 0.35f, 0f) else null, target)
    }

    /** Debug `aim` without a source: the ghost lands on the floor x m ahead, z m to the right of the head (emulator QA). */
    private fun debugAimHit(): SurfaceHit? {
        val (ahead, right) = DdGraph.debugAim ?: return null
        val floor = planes.all.filter { it.semantic == FLOOR }.maxByOrNull { it.area } ?: return null
        val f = pose.level()
        val p = floor.project(Vec3(pose.position.x + f.x * ahead + f.right.x * right, 0f, pose.position.z + f.z * ahead + f.right.z * right))
        return SurfaceHit(p, Vec3.UP, pose.position.distanceTo(p), floor)
    }

    private fun nearestTable(p: Vec3): PlaneSurface? = ui.tables.minByOrNull { P2(it.centroid.x - p.x, it.centroid.z - p.z).length + abs(it.centroid.y - p.y) }

    // =========================================================================================
    // thumbstick: turn the selected source / shield about the vertical (taste profile D)
    // =========================================================================================

    private fun turnTarget(): DragWhat? {
        val s = ui
        if (!s.interactive || s.menuOpen || s.aiming != Aiming.NONE || drag != null) return null
        if (s.selectedSource && !s.sourceLocked && s.source != null) return DragWhat.Source
        val id = s.selectedShield ?: return null
        return if (!s.shieldsHidden) DragWhat.ShieldOf(id) else null
    }

    private fun stepStick(now: Long, dt: Float) {
        val x = stick.x(now)
        val raw = if (abs(x) <= STICK_DEAD) 0f else sign(x) * (abs(x) - STICK_DEAD) / (1f - STICK_DEAD) * STICK_MAX_DEG
        smoothRate += (raw - smoothRate) * (if (dt <= 0f) 0f else (dt / STICK_LOWPASS_S).coerceAtMost(1f))
        if (raw == 0f && abs(smoothRate) < 0.5f) smoothRate = 0f
        val target = turnTarget()
        turn?.let { t -> if (t.what != target) finishTurn("target") }
        awaitCommit?.let { at -> if (now - at > COMMIT_WAIT_MS) { awaitCommit = null; if (turn == null && drag == null) clearLive() } }
        if (smoothRate != 0f && target != null) {
            val t = turn ?: Turn(target).also {
                turn = it
                DdLog.i(String.format(Locale.US, "STICK turn start %s x=%.2f", target, x))
            }
            t.neutralSinceMs = -1L
            t.frames++
            val step = smoothRate * dt
            when (target) {
                DragWhat.Source -> {
                    val s = liveSource ?: ui.source ?: return
                    liveSource = s.copy(faceDeg = s.faceDeg - step)
                }
                is DragWhat.ShieldOf -> {
                    val s = liveShield?.takeIf { it.id == target.id } ?: ui.shields.firstOrNull { it.id == target.id } ?: return
                    liveShield = s.copy(faceDeg = s.faceDeg - step)
                }
            }
        } else {
            val t = turn ?: return
            if (t.neutralSinceMs < 0) t.neutralSinceMs = now else if (now - t.neutralSinceMs >= STICK_RELEASE_MS) finishTurn("release")
        }
    }

    private fun finishTurn(why: String) {
        val t = turn ?: return
        turn = null
        DdLog.i("STICK turn end ${t.what} frames=${t.frames} why=$why")
        when (t.what) {
            DragWhat.Source -> liveSource?.let { sink(DoseEvent.SourceMoved(it)) }
            is DragWhat.ShieldOf -> liveShield?.let { sink(DoseEvent.ShieldMoved(it, snapped = false)) }
        }
        awaitCommit = SystemClock.elapsedRealtime()
    }

    /**
     * Debug `ray`: a controller ray for [ms] from 20 cm right of / 35 cm below the head to floor point [target] (null
     * clears); `trigger` presses it. Emulator QA of the controller path.
     */
    fun debugRay(target: P2?, ms: Long): String {
        if (target == null || !hasHead) {
            stick.debugAim(null, 0L)
            return "ray off"
        }
        val y = ui.floorY ?: return "ray: no floor"
        val origin = pose.position + pose.right * 0.2f - Vec3(0f, 0.35f, 0f)
        val dir = (target.at(y) - origin).normalized()
        stick.debugAim(PointerRay(origin, dir), ms)
        return "ray from=${DdLog.pos(origin)} to=${target} ms=$ms"
    }

    fun debugTrigger() = stick.debugPress()

    fun debugEyesOff(off: Boolean): String {
        eyes.forceOff = off
        return "eyes off=$off ${eyes.status()}"
    }

    fun aimLine(): String = "AIM with=${aim?.second} origin=${DdLog.pos(aim?.first?.origin)} dir=${DdLog.pos(aim?.first?.dir)} eyes=${eyes.status()} stick=${stick.status()} active=${stick.active(SystemClock.elapsedRealtime())}"

    /** Debug `stick`: the stick reads [x] for [ms]. */
    fun debugStick(x: Float, ms: Long): String {
        stick.debugHold(x, ms)
        return String.format(Locale.US, "stick x=%.2f ms=%d target=%s", x, ms, turnTarget() ?: "-")
    }

    private fun clearLive() {
        liveSource = null
        liveShield = null
        liveSnapped = false
        placeViews()
    }

    private fun cancelLive() {
        if (drag != null) endDrag(cancelled = true)
        turn = null
        clearLive()
    }

    // =========================================================================================
    // panels
    // =========================================================================================

    /** A rest pose from the level heading: [leftDeg] to the left, [upDeg] up, [dist] away. */
    private fun restAt(h: HeadPose, leftDeg: Float, upDeg: Float, dist: Float): Vec3 {
        val f = h.level()
        val l = Math.toRadians(leftDeg.toDouble()).toFloat()
        val u = Math.toRadians(upDeg.coerceIn(-80f, 80f).toDouble()).toFloat()
        val left = P2(f.z, -f.x)
        val d = (f * cos(l) + left * sin(l)).normalized()
        return h.position + d.at(0f) * (dist * cos(u)) + Vec3(0f, dist * sin(u), 0f)
    }

    private fun gazePitchDeg(h: HeadPose): Float = Math.toDegrees(asin(h.forward.y.coerceIn(-1f, 1f).toDouble())).toFloat().coerceIn(-GAZE_PITCH_MAX_DEG, GAZE_PITCH_MAX_DEG)

    private fun placePanels(now: Long) {
        val rig = rig ?: return
        val state = ui
        val active = rig[if (state.fullPanel) PanelIds.DOSIMETER else PanelIds.COMPACT] ?: return
        val other = rig[if (state.fullPanel) PanelIds.COMPACT else PanelIds.DOSIMETER]
        val hold = state.menuOpen || state.dialogOpen || now - lastPanelPressMs < KEY_HOLD_MS
        dosimeterFollow.step(pose, now, hold)?.let { p ->
            active.moveTo(p)
            other?.moveTo(p)
        }
        // the panel's top edge stays put when the compact panel turns into the full one: both share the centre, the full one hangs lower
        val facing = FacePanel.facing(active.position, pose.position, null)
        val ph = (if (state.fullPanel) PanelSizes.DOSIMETER_H else PanelSizes.COMPACT_H) * DP_M * PANEL_SCALE
        val pw = PanelSizes.DOSIMETER_W * DP_M * PANEL_SCALE
        if (state.menuOpen) {
            val mw = PanelSizes.MENU_W * DP_M * PANEL_SCALE
            val mh = PanelSizes.MENU_H * DP_M * PANEL_SCALE
            rig[PanelIds.MENU]?.moveTo(active.position + facing.up * (ph / 2f + mh / 2f + 0.008f) + facing.right * (pw / 2f - mw / 2f) + facing.normal * 0.01f)
        }
        if (state.snack != null) snackFollow.step(pose, now, hold = false)?.let { rig[PanelIds.SNACK]?.show(it) }
        placeWorldPanels(state)
    }

    private fun scaleAt(at: Vec3, base: Float): Float = base * (at.distanceTo(pose.position) / 0.8f).coerceIn(0.9f, 3.0f)

    /** Plates riding on the source, the shields, the rings and the spot; the coachmark above the source (design `panels.plates`, `coach`). */
    private fun placeWorldPanels(state: DoseUiState) {
        val rig = rig ?: return
        val src = effSource()
        val floorY = state.floorY
        if (src == null || floorY == null) {
            for (id in PanelIds.plates) rig[id]?.hide()
            rig[PanelIds.COACH]?.hide()
            return
        }
        val eye = pose.position
        fun towardEye(p: Vec3): Vec3 = (eye - p).rejectFrom(Vec3.UP).normalized().let { if (it.length < 0.5f) Vec3(src.frameEx.x, 0f, src.frameEx.z) else it }
        // source plate 12 cm above the pot
        val top = Vec3(src.x, src.planeY + SourceUi.POT_H + 0.12f, src.z)
        rig[PanelIds.PLATE_SOURCE]?.let { slot -> val k = scaleAt(top, PLATE_SCALE); slot.show(top, k, k) }
        // shield plates at the window's lower edge, on the viewer's side
        val shields = effShields()
        for (id in 1..Shield.MAX) {
            val slot = rig[PanelIds.plateShield(id)] ?: continue
            val s = shields.firstOrNull { it.id == id }
            if (s == null) {
                slot.hide()
                continue
            }
            val n = Vec3(s.normal.x, 0f, s.normal.z)
            val side = if (n.dot(eye - Vec3(s.x, eye.y, s.z)) >= 0f) 1f else -1f
            // 12 cm off the panel: a plate turned towards the viewer must not cut into the panel's own faces
            val at = Vec3(s.x, floorY + Shield.WINDOW + 0.03f, s.z) + n * (side * 0.12f)
            val k = scaleAt(at, PLATE_SCALE)
            slot.show(at, k, k)
        }
        // ring plates outside each ring on the user's side at placement, 3 cm off the floor
        for (i in Rings.RADII.indices) {
            val slot = rig[PanelIds.plateRing(i)] ?: continue
            if (!state.settings.rings) {
                slot.hide()
                continue
            }
            val p = src.frame.floorPoint(Rings.RADII[i] + 0.05f, 0f)
            val k = scaleAt(Vec3(p.x, floorY, p.z), PLATE_SCALE)
            slot.show(Vec3(p.x, floorY + 0.03f + PanelSizes.PLATE_H * DP_M * k / 2f, p.z), k, k)
        }
        // drill 3 spot plate at the circle's edge towards the source
        val spot = state.drill?.takeIf { it.kind == DrillKind.SHIELD && it.running }?.spot
        val spotSlot = rig[PanelIds.PLATE_SPOT]
        if (spot != null && spotSlot != null) {
            val dir = (src.foot - spot).normalized()
            val p = spot + dir * (Drills.SPOT_R + 0.08f)
            val k = scaleAt(Vec3(p.x, floorY, p.z), PLATE_SCALE)
            spotSlot.show(Vec3(p.x, floorY + 0.03f + PanelSizes.PLATE_H * DP_M * k / 2f, p.z), k, k)
        } else spotSlot?.hide()
        // coachmark 30 cm above the source
        val coach = rig[PanelIds.COACH]
        if (coachOn(state)) {
            val at = Vec3(src.x, src.planeY + SourceUi.POT_H + 0.30f, src.z) + towardEye(top) * 0.02f
            // constant visual size like the plates: at 2 - 3 m a 0.75 panel is unreadable (emulator S21)
            val k = scaleAt(at, COACH_SCALE)
            coach?.show(at, k, k)
        } else coach?.hide()
    }

    /** A key of a panel was pressed: the dosimeter holds still for a moment (lazy follow). */
    fun onPanelPointer() {
        lastPanelPressMs = SystemClock.elapsedRealtime()
    }

    // =========================================================================================
    // input: system taps and drags
    // =========================================================================================

    private fun shieldOfEntity(e: Entity?): Int? {
        if (e == null) return null
        for ((id, v) in shieldViews) if (v.owns(e)) return id
        return null
    }

    /** A tap on a plane collider as a surface hit at the tap point, normal towards the viewer. */
    private fun surfaceHit(planeId: String, stage: Vec3?): SurfaceHit? {
        val plane = planes.all.firstOrNull { it.id == planeId } ?: return null
        val p = plane.project(stage ?: plane.centroid)
        var n = plane.normal
        if (n.dot(pose.position - p) < 0f) n = -n
        return SurfaceHit(p, n, pose.position.distanceTo(p), plane)
    }

    fun onSpatialTap(value: SpatialTapValue) {
        val now = SystemClock.elapsedRealtime()
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        if (lastDragEndMs > 0 && now - lastDragEndMs < TAP_AFTER_DRAG_MS) {
            DdLog.i("TAP ignored reason=after-drag target=$name")
            return
        }
        val shieldId = shieldOfEntity(target)
        val planeId = target?.let { colliders?.planeIdOf(it) }
        when {
            target != null && sourceView?.owns(target) == true -> {
                DdLog.i("TAP source stage=${DdLog.pos(stage)}")
                sink(DoseEvent.SourceTapped)
            }
            shieldId != null -> {
                DdLog.i("TAP shield id=$shieldId stage=${DdLog.pos(stage)}")
                sink(DoseEvent.ShieldTapped(shieldId))
            }
            planeId != null -> {
                val hit = surfaceHit(planeId, stage)
                DdLog.i("TAP plane target=$name stage=${DdLog.pos(stage)} hit=${hit != null}")
                if (hit != null) sink(DoseEvent.PlaneTapped(hit)) else sink(DoseEvent.EmptyTapped)
            }
            target != null && colliders?.isRoom(target) == true -> {
                DdLog.i("TAP room target=$name stage=${DdLog.pos(stage)}")
                sink(DoseEvent.EmptyTapped)
            }
            else -> DdLog.i("TAP other target=$name stage=${DdLog.pos(stage)}")
        }
    }

    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        val now = SystemClock.elapsedRealtime()
        when (phase) {
            "start" -> {
                drag = null
                val target = value.targetEntity
                val name = runCatching { target?.getName() }.getOrNull() ?: "none"
                val press = if (start != null) toStage(start.x, start.y, start.z) else null
                val isSource = target != null && sourceView?.owns(target) == true
                val shieldId = shieldOfEntity(target)
                DdLog.i("DRAG start target=$name source=$isSource shield=${shieldId ?: "-"} stage=${DdLog.pos(press)}")
                if (press == null) return
                val s = ui
                if (!s.interactive || s.menuOpen || s.aiming != Aiming.NONE) {
                    DdLog.i("DRAG refused state=${s.designState}")
                    return
                }
                drag =
                    when {
                        isSource && !s.sourceLocked -> Drag(DragWhat.Source, press)
                        shieldId != null && !s.shieldsHidden -> Drag(DragWhat.ShieldOf(shieldId), press)
                        else -> null
                    }
            }
            "move" -> {
                val d = drag ?: return
                val a = value.dragAmount
                d.moves++
                d.totalPx = Vec3(d.totalPx.x + a.x, d.totalPx.y + a.y, d.totalPx.z + a.z)
                d.hand = handDelta(d.totalPx) ?: d.hand
                if (!d.begun && d.hand.length >= DRAG_START_M) beginDrag(d)
                if (d.begun) {
                    val dir = d.dir ?: return
                    val turned = DragLine.turn(dir, d.hand - d.prevHand, d.equivalent, pose.right, pose.up, pose.forward)
                    d.prevHand = d.hand
                    d.dir = turned
                    moveDrag(d, turned, now)
                    if (d.moves <= 3 || now - d.lastLogMs > 500) {
                        d.lastLogMs = now
                        DdLog.i("DRAG move ${d.what} live=${liveSource?.foot ?: liveShield?.center} snapped=$liveSnapped n=${d.moves}")
                    }
                }
            }
            "end", "cancel" -> {
                val d = drag ?: return
                drag = null
                if (!d.begun) {
                    DdLog.i("DRAG $phase not-begun n=${d.moves}")
                    return
                }
                lastDragEndMs = now
                DdLog.i("DRAG $phase ${d.what} n=${d.moves} snapped=$liveSnapped")
                endDrag(cancelled = phase == "cancel", d = d)
            }
        }
    }

    private fun beginDrag(d: Drag) {
        d.begun = true
        val to = d.press - pose.position
        if (to.length < 1e-3f) return
        d.dir = to.normalized()
        val pitch = Math.toDegrees(asin(pose.forward.y.coerceIn(-1f, 1f).toDouble())).toFloat()
        d.equivalent = DragLine.equivalentDepth(to.length, pitch)
        d.prevHand = d.hand
        liveSnapped = false
        sink(DoseEvent.DragStarted(d.what))
        DdLog.i("DRAG begin ${d.what} depth=${String.format(Locale.US, "%.2f", d.equivalent)}")
    }

    /** The piece follows where the pointing line meets the level of its surface (design `rules.move`). */
    private fun moveDrag(d: Drag, line: Vec3, now: Long) {
        val state = ui
        val floorY = state.floorY ?: return
        when (val w = d.what) {
            DragWhat.Source -> {
                val src = liveSource ?: state.source ?: return
                val p = levelPoint(line, src.planeY + 0.07f) ?: return
                val table = src.planeId?.let { id -> state.tables.firstOrNull { it.id == id } }
                var at = Vec3(p.x, src.planeY, p.z)
                var snapped = false
                if (src.surface == TABLE && table != null) {
                    val c = table.centroid
                    if (P2(at.x - c.x, at.z - c.z).length <= SNAP_TABLE_M) {
                        at = table.project(c)
                        snapped = true
                    } else at = table.clampInside(at, POT_INSET_M)
                }
                if (snapped && !liveSnapped) aids?.shake(now)
                liveSnapped = snapped
                liveSource = src.copy(x = at.x, z = at.z)
            }
            is DragWhat.ShieldOf -> {
                val s = liveShield?.takeIf { it.id == w.id } ?: state.shields.firstOrNull { it.id == w.id } ?: return
                val p = levelPoint(line, floorY + 0.93f) ?: return
                val snap = shieldSnapPoint(state)
                val src = state.source
                val dist = snap?.let { (p - it).length } ?: Float.MAX_VALUE
                val snapped = if (liveSnapped) dist <= UNSNAP_SHIELD_M else dist <= SNAP_SHIELD_M
                if (snapped && !liveSnapped) {
                    aids?.shake(now)
                    DdLog.i("SNAP shield id=${s.id} at=$snap")
                }
                liveSnapped = snapped
                liveShield =
                    if (snapped && snap != null && src != null) s.copy(x = snap.x, z = snap.z, faceDeg = Shield.faceTowards(snap, src.foot))
                    else shieldLanding(state, p).let { q -> s.copy(x = q.x, z = q.z) }
            }
        }
    }

    /**
     * Where the dragged piece goes on the level [y]: along the controller ray itself while the controller aims; for a
     * pinch drag (eyes + hand) along the system-drag pointing line (Spatial Ruler's model, [DragLine]).
     */
    private fun levelPoint(line: Vec3, y: Float): P2? {
        val ctl = aim?.takeIf { it.second == AimSource.CONTROLLER }?.first
        if (ctl != null) return Aim.onLevel(ctl, y)?.let { P2(it.x, it.z) }
        return DragLine.onLevel(pose.position, line, y)
    }

    private fun endDrag(cancelled: Boolean, d: Drag? = null) {
        val dd = d ?: drag
        drag = null
        when (dd?.what) {
            DragWhat.Source -> sink(DoseEvent.SourceMoved(if (cancelled) null else liveSource))
            is DragWhat.ShieldOf -> sink(DoseEvent.ShieldMoved(if (cancelled) null else liveShield, snapped = !cancelled && liveSnapped))
            null -> if (ui.dragging != null) sink(DoseEvent.SourceMoved(null))
        }
        if (dd != null) awaitCommit = SystemClock.elapsedRealtime()
        if (cancelled) clearLive()
    }

    private fun handDelta(px: Vec3): Vec3? {
        val moved = toStage(px.x, px.y, px.z) ?: return null
        val origin = toStage(0f, 0f, 0f) ?: return null
        return moved - origin
    }

    /** View-local pixels of a spatial gesture -> Stage metres (Spatial Ruler). */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .getOrNull()
    }

    // =========================================================================================
    // debug seams
    // =========================================================================================

    fun headForDebug(): Vec3? = if (hasHead) pose.position else null

    fun headPose(): HeadPose? = if (hasHead) pose else null

    fun debugHideMode(mode: String): String {
        val rig = rig ?: return "no rig"
        rig.hideMode = if (mode == "park") PanelRig.HideMode.PARK else PanelRig.HideMode.AUTO
        return "hidemode=${rig.hideMode}"
    }

    fun poseLine(): String =
        "POSE hmd pos=${DdLog.pos(pose.position)} ${DdLog.quat(pose.rotation)} fwd=${DdLog.pos(pose.forward)} valid=$hasHead judged=${DdLog.pos(judged)} judgedFwd=${DdLog.pos(judgedForward)} gazeMs=$gazeMs " +
            "H=${reading?.h} crossed=${reading?.crossed} rel=${DdLog.pos(DdGraph.debugHead)} pitch=${DdGraph.debugPitchDeg}"

    fun panelLines(): List<String> = rig?.dump() ?: emptyList()

    fun planeLines(): List<String> = planes.all.map { "PLANE ${it.label} center=${DdLog.pos(it.centroid)} normal=${DdLog.pos(it.normal)} area=${String.format(Locale.US, "%.2f", it.area)}" }

    /** The plane whose label starts with [prefix] (semantic or `SEMANTIC:uuid8`), for debug placement. */
    fun planeByLabel(prefix: String): PlaneSurface? = planes.all.firstOrNull { it.label.startsWith(prefix, ignoreCase = true) } ?: planes.all.firstOrNull { it.semantic.equals(prefix, ignoreCase = true) }

    /** The table nearest to the viewer (standard QA scene: the source on the table's centre). */
    fun tableNearViewer(): PlaneSurface? {
        val h = headForDebug() ?: return ui.tables.maxByOrNull { it.area }
        return ui.tables.filter { it.area > 0.15f }.minByOrNull { P2(it.centroid.x - h.x, it.centroid.z - h.z).length } ?: ui.tables.minByOrNull { P2(it.centroid.x - h.x, it.centroid.z - h.z).length }
    }

    fun floorPlane(): PlaneSurface? = planes.all.filter { it.semantic == FLOOR }.maxByOrNull { it.area }

    fun meshQueue(): Int = meshWork.size

    fun readingNow(): Reading? = reading

    companion object {
        const val TABLE = "TABLE"
        const val FLOOR = "FLOOR"
        const val PANEL_SCALE = 0.75f
        const val PLATE_SCALE = 0.75f
        const val DIALOG_SCALE = 1.0f
        const val SNACK_SCALE = 1.0f
        const val COACH_SCALE = 0.75f
        const val DOSIMETER_LEFT_DEG = 28f
        const val DOSIMETER_DOWN_DEG = 30f
        const val DOSIMETER_M = 0.6f
        const val DOSIMETER_WINDOW_DEG = 45f
        const val SNACK_UP_DEG = 14f
        const val SNACK_M = 0.9f
        const val DIALOG_M = 0.8f
        const val RESULT_M = 0.8f
        const val GAZE_PITCH_MAX_DEG = 60f
        const val KEY_HOLD_MS = 600L
        const val DRAG_START_M = 0.01f
        const val TAP_AFTER_DRAG_MS = 400L
        const val STICK_DEAD = 0.2f
        const val STICK_MAX_DEG = 90f
        const val STICK_LOWPASS_S = 0.1f
        const val STICK_RELEASE_MS = 150L
        const val COMMIT_WAIT_MS = 1500L
        const val TICK_MS = 100L
        const val GEIGER_MS = 250L
        const val GEOM_MS = 100L
        const val HEAT_LIVE_MS = 300L
        const val HEAD_STEP_M = 0.10f
        const val POT_INSET_M = 0.07f
        const val LOG_MS = 5000L
        const val MESH_PER_FRAME = 2
        const val FAST_FRAME_MS = 40L
        const val MID_FRAME_MS = 150L
        const val MESH_GAP_SLOW_MS = 2_500L
        const val ENTITY_GAP_SLOW_MS = 800L
    }
}

/**
 * Lazy follow by heading only (design `panels.dosimeter.placement`): the panel rests where it is; once
 * the head's level heading has turned more than [windowDeg] away from the panel for 0.5 s, it slides
 * (0.35 s, ease-in-out cubic) to the rest pose of that moment. Looking up or down never moves it.
 */
internal class HorizontalFollow(private val name: String, private val rest: (HeadPose) -> Vec3, private val windowDeg: Float) {
    private var at: Vec3? = null
    private var from = Vec3.ZERO
    private var to = Vec3.ZERO
    private var slideStartMs = NEVER
    private var outSinceMs = NEVER
    private var lastWritten: Vec3? = null

    fun reset() {
        at = null
        slideStartMs = NEVER
        outSinceMs = NEVER
        lastWritten = null
    }

    fun step(head: HeadPose, nowMs: Long, hold: Boolean): Vec3? {
        val current = at
        if (current == null) {
            val p = rest(head)
            at = p
            DdLog.i("FOLLOW $name park pos=${DdLog.pos(p)}")
            return write(p)
        }
        if (slideStartMs != NEVER) {
            val q = ((nowMs - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
            val e = if (q < 0.5f) 4f * q * q * q else 1f - (-2f * q + 2f).let { it * it * it } / 2f
            val p = from.lerp(to, e)
            if (q >= 1f) {
                slideStartMs = NEVER
                at = to
            }
            return write(p)
        }
        val toPanel = P2.of(current) - P2.of(head.position)
        val dist = toPanel.length
        val heading = head.level()
        val away = if (dist < 1e-3f) 0f else Math.toDegrees(acos((toPanel.normalized().dot(heading)).coerceIn(-1f, 1f).toDouble())).toFloat()
        val out = away > windowDeg || dist < 0.25f || dist > 1.5f
        if (!out || hold) {
            outSinceMs = NEVER
        } else if (outSinceMs == NEVER) {
            outSinceMs = nowMs
        } else if (nowMs - outSinceMs >= OUT_MS) {
            from = current
            to = rest(head)
            slideStartMs = nowMs
            outSinceMs = NEVER
            DdLog.i(String.format(Locale.US, "FOLLOW %s slide angle=%.1f dist=%.2f to=%s", name, away, dist, DdLog.pos(to)))
        }
        return write(current)
    }

    private fun write(p: Vec3): Vec3? {
        if (p == lastWritten) return null
        lastWritten = p
        return p
    }

    companion object {
        private const val NEVER = -1L
        private const val OUT_MS = 500L
        private const val SLIDE_MS = 350f
    }
}
