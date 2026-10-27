package io.embodit.dosedistance.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.embodit.dosedistance.domain.Dose
import io.embodit.dosedistance.domain.Drills
import io.embodit.dosedistance.domain.Isotope
import io.embodit.dosedistance.domain.Language
import io.embodit.dosedistance.domain.P2
import io.embodit.dosedistance.domain.Vec3
import io.embodit.dosedistance.platform.DdGraph
import io.embodit.dosedistance.platform.DdLog
import io.embodit.dosedistance.ui.dose.Aiming
import io.embodit.dosedistance.ui.dose.DoseEvent
import io.embodit.dosedistance.ui.dose.DoseViewModel
import io.embodit.dosedistance.ui.dose.DragWhat
import io.embodit.dosedistance.ui.dose.DrillKind
import io.embodit.dosedistance.ui.dose.DrillPhase
import io.embodit.dosedistance.ui.dose.FailWhy
import io.embodit.dosedistance.ui.dose.Phase
import io.embodit.dosedistance.ui.dose.SnackKind
import io.embodit.dosedistance.ui.dose.Tab
import io.embodit.dosedistance.ui.dose.debug.Probe
import io.embodit.dosedistance.ui.dose.scene.DoseScene
import java.io.File
import java.util.Locale

/**
 * Test hooks of the debug build (design `qaHooks`). Every command answers with one `CMD` line (tag
 * `DoseDistance`); every design-state change logs `STATE Sxx ...` with H, d, A, blocked %, cumulative,
 * the head relative to the source core and every shield in the source frame.
 *
 * Coordinates (design `qaHooks.broadcast`): x / z relative to the source core's foot point, y relative to
 * the core, metres; x = from the source towards the user at placement; shield yaw 0 faces +x, 180 faces the source.
 *
 * Commands: `goto` s=S01..S27 [v=variant] · `place` surface=table|floor [x z yaw] · `head` x y z [pitch] | v=off ·
 * `shield` n x z yaw [t] · `thickness` n t · `iso` id=tc99m|cs137|co60 · `lang` id=zh|en ·
 * `drill` id=distance|time|shield do=start|pass|fail|code|read · `dose` cum · `drag_pose` what=source|shield on=1|0 ·
 * `lost` [on=0] · `coach_reset` · `state` · `aim` x z | v=off · `stick` a=x,ms · `select` what=source|shield [n] ·
 * `tap` · `ui` s=control · `snack` v=long|normal · `dump` · `pose` · `panels` · `planes` · `bounds` · `hidemode` s=auto|park
 *
 * v0.1.1: `front` n (bring shield n in front of the head) · `hide` n [on=1|0] · `inside` n (shield n stands on the
 * real head's floor point, debug head off: the view from inside the shield, fade check) · `sound` on=1|0 ·
 * `heat` on=1|0 · `snap` on=1|0 · `fade` (which shields are see-through). Shield standard scenes put the head
 * 1.5 m from the source so that every shield keeps the 0.8 m clearance.
 */
internal object DebugCommands {
    private val main = Handler(Looper.getMainLooper())

    /** `qa --es surface floor|table [--ef x 2.4]`: where `goto` puts the standard scene's source (emulator QA framing). */
    private var qaSurface = "table"
    private var qaAheadM = 2.4f

    fun run(context: Context, cmd: String, t: Map<String, String>, i: Map<String, Int>, f: Map<String, Float>) {
        val host = Probe.host
        if (host == null) {
            DdLog.w("CMD ignored: no Stage")
            return
        }
        val vm = host.viewModel()
        val scene = host.scene()
        val s = t["s"]
        val v = t["v"]
        when (cmd) {
            "goto" -> CmdOut.log(goto(vm, scene, s ?: "", v))
            "qa" -> {
                t["surface"]?.let { qaSurface = it }
                f["x"]?.let { qaAheadM = it }
                CmdOut.log("qa surface=$qaSurface ahead=$qaAheadM")
            }
            "state" -> if (!s.isNullOrBlank()) CmdOut.log(goto(vm, scene, s, v)) else DdLog.i(vm.stateLine())
            "place" -> CmdOut.log(place(vm, scene, t["surface"] ?: s ?: "table", f["x"], f["z"], f["yaw"]))
            "head" -> {
                if (v == "off" || s == "off") {
                    DdGraph.debugHead = null
                    DdGraph.debugPitchDeg = null
                } else {
                    DdGraph.debugHead = Vec3(f["x"] ?: 1f, f["y"] ?: 0.85f, f["z"] ?: 0f)
                    DdGraph.debugPitchDeg = f["pitch"]
                }
                CmdOut.log("head rel=${DdLog.pos(DdGraph.debugHead)} pitch=${DdGraph.debugPitchDeg}")
            }
            "shield" -> CmdOut.log(shield(vm, i["n"] ?: 1, f["x"] ?: 0.5f, f["z"] ?: 0f, f["yaw"] ?: 180f, i["t"]))
            "thickness" -> {
                val n = i["n"] ?: 1
                val tt = i["t"] ?: 6
                vm.debugSet { st -> st.copy(shields = st.shields.map { if (it.id == n) it.copy(tMm = tt) else it }) }
                CmdOut.log("thickness n=$n t=$tt")
            }
            "iso" -> {
                val iso = Isotope.byTag(t["id"] ?: v ?: s)
                vm.debugSet { it.copy(settings = it.settings.copy(isotope = iso)) }
                CmdOut.log("iso=${iso.tag}")
            }
            "lang" -> Language.byTag(t["id"] ?: v ?: s)?.let { vm.onEvent(DoseEvent.LanguageSelected(it)) }
            "drill" -> CmdOut.log(drill(vm, DrillKind.byTag(t["id"]), t["do"] ?: v ?: "start"))
            "dose" -> {
                val cum = f["cum"] ?: 0f
                vm.debugSet { st -> if (st.drill != null) st.copy(drill = st.drill.copy(cum = cum)) else st.copy(cumulative = cum) }
                CmdOut.log("dose cum=$cum")
            }
            "drag_pose" -> {
                val on = (i["on"] ?: 1) != 0
                val what = if ((t["what"] ?: v) == "source") DragWhat.Source else DragWhat.ShieldOf(i["n"] ?: vm.state.value.shields.firstOrNull()?.id ?: 1)
                vm.debugSet { it.copy(dragging = if (on) what else null) }
                CmdOut.log("drag_pose $what on=$on")
            }
            "lost" -> {
                DdGraph.head.forceLost = (i["on"] ?: 1) != 0
                CmdOut.log("lost=${DdGraph.head.forceLost}")
            }
            "coach_reset" -> vm.debugCoachReset()
            "aim" -> {
                DdGraph.debugAim = if (v == "off") null else (f["x"] ?: 0.6f) to (f["z"] ?: 0f)
                CmdOut.log("aim=${DdGraph.debugAim}")
            }
            "stick" -> {
                val nums = (t["a"] ?: "").split(',').mapNotNull { it.trim().toFloatOrNull() }
                CmdOut.log(scene.debugStick(nums.getOrNull(0) ?: f["x"] ?: 0f, (nums.getOrNull(1) ?: 1000f).toLong()))
            }
            "front" -> {
                val n = i["n"] ?: 1
                syncHead(vm)
                CmdOut.log("front n=$n -> ${vm.shieldToFront(n)}")
            }
            "hide" -> {
                val n = i["n"] ?: 1
                vm.onEvent(DoseEvent.ShieldHidden(n, (i["on"] ?: 1) != 0))
                CmdOut.log("hide n=$n on=${i["on"] ?: 1}")
            }
            "inside" -> {
                val n = i["n"] ?: 1
                DdGraph.debugHead = null
                DdGraph.debugPitchDeg = null
                val pose = scene.headPose()
                if (pose == null) CmdOut.log("inside: no head") else {
                    val f = pose.level()
                    // the panel 15 cm ahead of the eyes, facing them: what "walked into the shield" looks like
                    val at = P2(pose.position.x + f.x * 0.15f, pose.position.z + f.z * 0.15f)
                    vm.debugSet { st -> st.copy(shields = st.shields.map { if (it.id == n) it.copy(x = at.x, z = at.z, faceDeg = io.embodit.dosedistance.domain.Shield.faceTowards(at, P2(pose.position.x, pose.position.z)), hidden = false) else it }) }
                    CmdOut.log("inside n=$n at=$at")
                }
            }
            "sound" -> vm.onEvent(DoseEvent.GeigerSet((i["on"] ?: 1) != 0))
            "heat" -> vm.onEvent(DoseEvent.HeatSet((i["on"] ?: 1) != 0))
            "snap" -> vm.onEvent(DoseEvent.SnapSet((i["on"] ?: 1) != 0))
            "fade" -> CmdOut.log("fade ${scene.fadedShieldIds()}")
            "select" -> if ((t["what"] ?: v) == "source") vm.onEvent(DoseEvent.SourceTapped) else vm.onEvent(DoseEvent.ShieldTapped(i["n"] ?: 1))
            "tap" -> vm.onEvent(DoseEvent.EmptyTapped)
            "ui" -> ui(vm, s ?: "")
            "snack" -> vm.longSnacks = (v ?: s) == "long"
            "dump" -> {
                val line = vm.stateLine()
                runCatching { File(context.filesDir, "dump.txt").writeText(line) }
                DdLog.i("DUMP $line")
            }
            "hidemode" -> CmdOut.log(scene.debugHideMode(s ?: "auto"))
            "pose" -> DdLog.i(scene.poseLine())
            "panels" -> scene.panelLines().forEach { DdLog.i(it) }
            "planes" -> scene.planeLines().forEach { DdLog.i(it) }
            "bounds" ->
                listOf("dosimeter", "compact", "menu", "dialog", "snack", "coach", "result").forEach { panel ->
                    Probe.controlsOf(panel).forEach { (name, b) -> DdLog.i(String.format(Locale.US, "CTRL panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f", panel, name, b.cx, b.cy, b.w, b.h)) }
                }
            else -> DdLog.w("CMD unknown '$cmd'")
        }
    }

    private object CmdOut {
        fun log(line: String) = DdLog.i("CMD $line")
    }

    /** Presses a panel control by its stable name (the same names the probe reports). */
    private fun ui(vm: DoseViewModel, name: String) {
        val e: DoseEvent? =
            when (name) {
                "more" -> DoseEvent.MoreClicked
                "place_source" -> DoseEvent.PlaceSourceClicked
                "remove_source" -> DoseEvent.RemoveSourceClicked
                "place_shield" -> DoseEvent.PlaceShieldClicked
                "delete_shield" -> DoseEvent.DeleteShield(null)
                "mute" -> DoseEvent.GeigerSet(!vm.state.value.settings.geiger)
                "menu_heat" -> DoseEvent.HeatSet(!vm.state.value.settings.heat)
                "menu_snap" -> DoseEvent.SnapSet(!vm.state.value.settings.snap)
                "thickness_minus" -> DoseEvent.ThicknessStep(-1)
                "thickness_plus" -> DoseEvent.ThicknessStep(1)
                "reset" -> DoseEvent.ResetCumulative
                "tab_source" -> DoseEvent.TabSelected(Tab.SOURCE)
                "tab_shield" -> DoseEvent.TabSelected(Tab.SHIELD)
                "tab_drill" -> DoseEvent.TabSelected(Tab.DRILL)
                "iso_tc99m", "iso_cs137", "iso_co60" -> DoseEvent.IsotopeSelected(Isotope.byTag(name.removePrefix("iso_")))
                "drill_distance", "drill_time", "drill_shield" -> DrillKind.byTag(name.removePrefix("drill_"))?.let { DoseEvent.DrillStart(it) }
                "drill_quit" -> DoseEvent.DrillQuit
                "result_again" -> DoseEvent.ResultAgain
                "result_next" -> DoseEvent.ResultNext
                "result_later" -> DoseEvent.ResultLater
                "menu_geiger" -> DoseEvent.GeigerSet(!vm.state.value.settings.geiger)
                "menu_rings" -> DoseEvent.RingsSet(!vm.state.value.settings.rings)
                "lang_zh" -> DoseEvent.LanguageSelected(Language.ZH)
                "lang_en" -> DoseEvent.LanguageSelected(Language.EN)
                "menu_about" -> DoseEvent.AboutClicked
                "menu_clear" -> DoseEvent.ClearAllClicked
                "menu_exit" -> DoseEvent.ExitClicked
                "dialog_cancel", "dialog_ok" -> DoseEvent.DialogDismissed
                "dialog_confirm" -> DoseEvent.ClearConfirmed
                "blocked_exit" -> DoseEvent.BlockedExit
                "blocked_retry" -> DoseEvent.BlockedRetry
                "coach_ok" -> DoseEvent.CoachDismissed
                else -> shieldControl(name)
            }
        if (e == null) DdLog.w("CMD ui unknown '$name'") else {
            vm.onEvent(e)
            CmdOut.log("ui $name")
        }
    }

    /** `shield_item_N` / `shield_front_N` / `shield_hide_N` / `shield_delete_N` (the shield list). */
    private fun shieldControl(name: String): DoseEvent? {
        val n = name.substringAfterLast('_').toIntOrNull() ?: return null
        val st = Probe.host?.viewModel()?.state?.value ?: return null
        return when {
            name.startsWith("shield_item_") -> DoseEvent.ShieldListTapped(n)
            name.startsWith("shield_front_") -> DoseEvent.ShieldToFront(n)
            name.startsWith("shield_hide_") -> DoseEvent.ShieldHidden(n, st.shields.firstOrNull { it.id == n }?.hidden != true)
            name.startsWith("shield_delete_") -> DoseEvent.DeleteShield(n)
            else -> null
        }
    }

    /** The ViewModel learns the head 10 times a second: an injected head is handed over at once before a placement. */
    private fun syncHead(vm: DoseViewModel) {
        val src = vm.state.value.source ?: return
        val rel = DdGraph.debugHead ?: return
        val h = src.frame.world(rel.x, rel.y, rel.z)
        vm.debugSet { it.copy(head = h, heading = P2(src.x - h.x, src.z - h.z).normalized()) }
    }

    /** Back to an empty, ready room (keeps the planes and the preferences). */
    private fun reset(vm: DoseViewModel) {
        vm.debugHoldScanning = false
        DdGraph.head.forceLost = false
        DdGraph.debugAim = null
        vm.debugSet {
            it.copy(
                phase = if (it.floorY != null) Phase.READY else it.phase,
                blocked = false,
                trackingLost = false,
                source = null,
                shields = emptyList(),
                lastShieldId = null,
                aiming = Aiming.NONE,
                selectedSource = false,
                selectedShield = null,
                tab = Tab.SOURCE,
                dragging = null,
                reading = null,
                cumulative = 0f,
                drill = null,
                menuOpen = false,
                clearDialog = false,
                aboutDialog = false,
                snack = null,
                coach = false,
                settings = it.settings.copy(isotope = Isotope.CS137, thickness = Dose.DEFAULT_T, coachSeen = true, rings = true, heat = true, snap = true),
            )
        }
    }

    /** The source on the centre of the table nearest to the viewer (QA standard scene), else on the floor 1.2 m ahead. */
    private fun place(vm: DoseViewModel, scene: DoseScene, surface: String, x: Float?, z: Float?, yaw: Float?): String {
        val table = scene.tableNearViewer()
        val floor = scene.floorPlane()
        val head = scene.headForDebug() ?: Vec3(0f, 1.6f, 0f)
        val useTable = surface != "floor" && table != null
        val plane = (if (useTable) table else floor) ?: return "no plane for $surface"
        val base =
            if (useTable) plane.project(plane.centroid)
            else {
                val f = scene.headPose()?.level() ?: P2(0f, -1f)
                plane.project(Vec3(head.x + f.x * qaAheadM, head.y, head.z + f.z * qaAheadM))
            }
        val at = Vec3(base.x + (x ?: 0f), base.y, base.z + (z ?: 0f))
        val ex = P2(head.x - at.x, head.z - at.z).let { if (it.length < 0.05f) P2(0f, 1f) else it.normalized() }
        val face = yaw?.let { Math.toDegrees(kotlin.math.atan2(ex.z.toDouble(), ex.x.toDouble())).toFloat() + it }
        val src = vm.placeSourceAt(plane, at, faceDeg = face, frameEx = ex)
        return "place ${plane.label} foot=${src.foot} planeY=${src.planeY}"
    }

    private fun shield(vm: DoseViewModel, n: Int, x: Float, z: Float, yaw: Float, t: Int?): String {
        val src = vm.state.value.source ?: return "shield: no source"
        syncHead(vm)
        val p = src.frame.floorPoint(x, z)
        vm.releaseShield(n, p, src.frame.worldFace(yaw), t)
        val s = vm.state.value.shields.firstOrNull { it.id == n }
        return "shield n=$n rel=($x, $z) yaw=$yaw -> ${s?.let { val l = src.frame.local(Vec3(it.x, src.core.y, it.z)); String.format(Locale.US, "(%.2f, %.2f) yaw=%.0f t=%d", l.x, l.z, src.frame.localFace(it.faceDeg), it.tMm) }}"
    }

    private fun head(x: Float, y: Float, z: Float, pitch: Float? = null) {
        DdGraph.debugHead = Vec3(x, y, z)
        DdGraph.debugPitchDeg = pitch
    }

    private fun drill(vm: DoseViewModel, kind: DrillKind?, action: String): String {
        val st = vm.state.value
        when (action) {
            "start" -> {
                val k = kind ?: return "drill: id?"
                vm.onEvent(DoseEvent.DrillStart(k))
            }
            "pass" -> {
                val d = st.drill ?: return "drill pass: no drill"
                val r = st.reading
                val a = r?.attenuation?.takeIf { it <= Drills.PASS_A } ?: if (d.kind == DrillKind.SHIELD) Dose.attenuation(st.isotope, listOf(12, 12)) else (r?.attenuation ?: 1f)
                val h = if (d.kind == DrillKind.SHIELD) Dose.rate(st.isotope, r?.d ?: 1.31f, a) else if (d.kind == DrillKind.DISTANCE) minOf(d.target, r?.h ?: d.target) else r?.h ?: d.hStart
                vm.debugSet { it.copy(drill = d.copy(phase = DrillPhase.PASSED, resultA = a, resultH = h, elapsedMs = maxOf(d.elapsedMs, 14_000L), closestD = minOf(d.closestD, r?.d ?: 0.6f))) }
            }
            "fail" -> {
                val d = st.drill ?: return "drill fail: no drill"
                vm.debugSet { it.copy(drill = d.copy(phase = DrillPhase.FAILED, failWhy = FailWhy.OVER_BUDGET, failD = st.reading?.d ?: 0.6f, overStaySec = if (d.read) 9 else 0)) }
            }
            "code" -> return "drill code=${st.drill?.code ?: "-"}"
            "read" -> {
                val d = st.drill ?: return "drill read: no drill"
                vm.debugSet { it.copy(drill = d.copy(readAtMs = d.elapsedMs)) }
            }
            else -> return "drill: unknown do=$action"
        }
        return "drill ${kind?.tag ?: st.drill?.kind?.tag} $action -> ${vm.state.value.drill?.phase}"
    }

    private fun later(ms: Long, block: () -> Unit) = main.postDelayed(block, ms)

    /** Every design state directly (design `qaHooks.sceneTable`). */
    private fun goto(vm: DoseViewModel, scene: DoseScene, s: String, v: String?): String {
        val id = s.uppercase(Locale.US)
        fun standard(x: Float = 1f, y: Float = 0.85f, z: Float = 0f, pitch: Float? = null): String {
            reset(vm)
            head(x, y, z, pitch)
            return place(vm, scene, qaSurface, null, null, null)
        }
        val out: String =
            when (id) {
                "S01" -> {
                    reset(vm)
                    vm.debugSet { it.copy(phase = Phase.STARTING) }
                    "starting"
                }
                "S02" -> {
                    reset(vm)
                    vm.debugHoldScanning = true
                    vm.debugSet { it.copy(phase = Phase.SCANNING) }
                    vm.showSnack(SnackKind.SCANNING)
                    "scanning (held)"
                }
                "S03" -> {
                    reset(vm)
                    vm.debugSet { it.copy(aiming = Aiming.SOURCE) }
                    DdGraph.debugAim = qaAheadM to 0f
                    "aiming source (ghost ${qaAheadM} m ahead)"
                }
                "S27" -> {
                    reset(vm)
                    "idle, no source"
                }
                "S04" -> standard(3.5f)
                "S05" -> standard().also { if (v == "en") vm.onEvent(DoseEvent.LanguageSelected(Language.EN)) }
                "S06" -> if (v == "extreme") standard(0.2f, 0.15f) else standard(0.3f, 0.25f)
                "S07" -> standard().also { vm.onEvent(DoseEvent.SourceTapped) }
                "S08" -> standard().also {
                    vm.onEvent(DoseEvent.TabSelected(Tab.SHIELD))
                    vm.onEvent(DoseEvent.PlaceShieldClicked)
                    DdGraph.debugAim = 0.6f to 0f
                }
                "S09" -> standard(1.5f).also {
                    shield(vm, 1, 0.8f, 0.9f, 30f, 6)
                    vm.debugSet { st -> st.copy(dragging = DragWhat.ShieldOf(1), selectedShield = 1, tab = Tab.SHIELD) }
                }
                "S10" -> standard(1.5f).also { shield(vm, 1, 0.8f, 0.9f, 30f, 6) }
                "S11" -> standard(1.5f).also { shield(vm, 1, 0.55f, 0.05f, 170f, 6) }
                "S12" -> standard(1.5f).also {
                    shield(vm, 1, 0.55f, 0.05f, 170f, 6)
                    vm.onEvent(DoseEvent.ShieldTapped(1))
                    if (v == "24") vm.debugSet { st -> st.copy(shields = st.shields.map { it.copy(tMm = 24) }) }
                }
                "S13" -> when (v) {
                    "two" -> standard(1.5f).also {
                        shield(vm, 1, 0.5f, 0f, 180f, 12)
                        shield(vm, 2, 0.7f, 0f, 180f, 12)
                        vm.debugSet { it.copy(snack = null) }
                    }
                    "out" -> standard(0.6f, 0.85f, 0.8f).also { shield(vm, 1, 0.5f, 0f, 180f, 6); vm.debugSet { it.copy(snack = null) } }
                    else -> standard(1.5f).also { shield(vm, 1, 0.5f, 0f, 180f, 6); vm.debugSet { it.copy(snack = null) } }
                }
                "S14" -> standard(1.5f).also {
                    shield(vm, 1, 0.5f, 0f, 180f, 6)
                    vm.debugSet { it.copy(snack = null) }
                    later(400) {
                        vm.onEvent(DoseEvent.DrillStart(DrillKind.DISTANCE))
                        if (v == "pass") later(300) { head(2.5f, 0.85f, 0f) }
                    }
                }
                "S15" -> standard().also {
                    later(400) {
                        vm.onEvent(DoseEvent.DrillStart(DrillKind.TIME))
                        if (v == "read") later(300) { head(0.2f, 0.55f, 0f, -65f) }
                    }
                }
                "S16" -> standard().also {
                    later(400) {
                        vm.onEvent(DoseEvent.DrillStart(DrillKind.SHIELD))
                        if (v == "out") later(300) { head(1.6f, 0.85f, 0f) }
                    }
                }
                "S17" -> standard(1.3f).also {
                    later(400) {
                        vm.onEvent(DoseEvent.DrillStart(DrillKind.SHIELD))
                        shield(vm, 1, 0.35f, 0f, 180f, 12)
                        shield(vm, 2, 0.5f, 0f, 180f, 12)
                        later(300) { drill(vm, null, "pass") }
                    }
                }
                "S18" -> standard().also {
                    later(400) {
                        vm.onEvent(DoseEvent.DrillStart(if (v == "time") DrillKind.TIME else DrillKind.DISTANCE))
                        later(300) { drill(vm, null, "fail") }
                    }
                }
                "S19" -> standard().also { DdGraph.head.forceLost = true }
                "S20" -> {
                    reset(vm)
                    vm.debugSet { it.copy(blocked = true) }
                    "blocked dialog"
                }
                "S21" -> {
                    reset(vm)
                    vm.debugCoachReset()
                    head(1f, 0.85f, 0f)
                    place(vm, scene, qaSurface, null, null, null)
                }
                "S22" -> standard().also { vm.onEvent(DoseEvent.MoreClicked) }
                "S23" -> standard().also { vm.onEvent(DoseEvent.ClearAllClicked) }
                "S24" -> standard(1.5f).also {
                    shield(vm, 1, 0.5f, 0f, 180f, 6)
                    shield(vm, 2, 0.6f, 0.9f, 30f, 6)
                    vm.onEvent(DoseEvent.TabSelected(Tab.SHIELD))
                    vm.onEvent(DoseEvent.PlaceShieldClicked)
                }
                "S25" -> standard().also { vm.onEvent(DoseEvent.AboutClicked) }
                "S26" -> standard().also {
                    vm.debugSet { it.copy(settings = it.settings.copy(isotope = if (v == "tc") Isotope.TC99M else Isotope.CO60)) }
                    if (v == "shield") {
                        head(1.5f, 0.85f, 0f)
                        shield(vm, 1, 0.5f, 0f, 180f, 6)
                    }
                }
                "RELEASE" -> {
                    vm.debugHoldScanning = false
                    DdGraph.head.forceLost = false
                    "released"
                }
                else -> return "goto: unknown state $s"
            }
        return "goto $id ${v ?: ""} -> $out"
    }
}
