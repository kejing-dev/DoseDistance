package io.embodit.dosedistance.ui.dose.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.resource.Material
import io.embodit.dosedistance.domain.Heat
import io.embodit.dosedistance.domain.P2
import io.embodit.dosedistance.domain.Quat4
import io.embodit.dosedistance.domain.Rings
import io.embodit.dosedistance.domain.Shadow
import io.embodit.dosedistance.domain.Shield
import io.embodit.dosedistance.domain.Vec3
import io.embodit.dosedistance.domain.Zone
import io.embodit.dosedistance.ui.dose.SourceUi
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Rotation about the vertical that turns local +Z onto the floor direction of angle [faceDeg] ((cos, sin) on x, z). */
internal fun yawFacing(faceDeg: Float): Quat4 = Quat4.axisAngle(Vec3.UP, Math.toRadians(90.0 - faceDeg).toFloat())

private val FACE_UP: Quat4 = Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(-90.0).toFloat())

/**
 * The source (design `entities3d.source`, `primitiveRecipes.source`): lead pot Ø 12 cm with a 2 cm rim
 * around an Ø 8 cm opening, the magenta core flush with the opening, a yellow band with the trefoil
 * facing the user and the 3-digit code on the opening's floor. The holder sits on the surface; local +Z is
 * the trefoil's side. The holder carries the tap / drag collider.
 */
internal class SourceView(root: Entity, private val meshes: DdMeshes, private val m: DdMaterials, trefoil: Material?) {
    val holder: Entity = Entity()
    private val body: Part
    private val rim: Part
    private val floor: Part
    private val core: Part
    private val band: Part
    private val decal: Part?
    private var code: Part
    private val select: Part
    private var codeMaterial: Material? = null

    init {
        runCatching { holder.setName("source") }
        holder.components.set(boxCollision(Vec3(0.15f, 0.14f, 0.15f)))
        holder.components.set(InteractableComponent())
        root.addChild(holder)
        // the collider is centred on the holder: children sit 7 cm lower so the box covers the pot
        val pot = Entity()
        holder.addChild(pot)
        pot.setLocal(position = Vec3(0f, -0.07f, 0f))
        body = Part(pot, meshes.cylinder, m.lead, "source:pot").also { it.place(Vec3(0f, 0.05f, 0f), Vec3(0.12f, 0.10f, 0.12f)) }
        rim = Part(pot, meshes.tube, m.lead, "source:rim").also { it.place(Vec3(0f, 0.11f, 0f), Vec3(0.12f, 0.02f, 0.12f)) }
        floor = Part(pot, meshes.disc, m.recess, "source:floor").also { it.place(Vec3(0f, 0.1004f, 0f), Vec3(0.08f, 0.08f, 1f), FACE_UP) }
        core = Part(pot, meshes.cylinder, m.core, "source:core").also { it.place(Vec3(0f, 0.11f, 0f), Vec3(0.02f, 0.02f, 0.02f)) }
        band = Part(pot, meshes.cylinder, m.band, "source:band").also { it.place(Vec3(0f, 0.07f, 0f), Vec3(0.122f, 0.03f, 0.122f)) }
        decal = trefoil?.let { mat -> Part(pot, meshes.quad, mat, "source:trefoil").also { it.place(Vec3(0f, 0.07f, 0.0625f), Vec3(0.027f, 0.027f, 1f)) } }
        // the code lies on the opening's floor, on the half away from the user, upright for a user looking down from the front
        code = Part(pot, meshes.quad, m.recess, "source:code").also { it.place(CODE_AT, CODE_SIZE, CODE_ROT) }
        select = Part(pot, meshes.ring, m.selected, "source:select").also { it.place(Vec3(0f, 0.002f, 0f), Vec3(0.19f, 1f, 0.19f)) }
        select.show(false)
    }

    fun place(src: SourceUi, lifted: Boolean) {
        holder.setLocal(position = Vec3(src.x, src.planeY + 0.07f + if (lifted) 0.05f else 0f, src.z), rotation = yawFacing(src.faceDeg))
    }

    fun look(selected: Boolean, locked: Boolean) {
        select.show(selected || locked)
        select.material(if (locked) m.locked else m.selected)
    }

    /** The code's decal (a new material per code; the caller owns closing old ones through the shared resources). */
    fun code(material: Material?) {
        if (material === codeMaterial) return
        codeMaterial = material
        this.code.material(material ?: m.recess)
    }

    fun owns(e: Entity): Boolean = e.id == holder.id

    fun show(visible: Boolean) {
        holder.enabled = visible
    }

    fun destroy() {
        runCatching { holder.destroy(true) }
    }

    private companion object {
        val CODE_AT = Vec3(0f, 0.1008f, -0.0235f)
        val CODE_SIZE = Vec3(0.034f, 0.019f, 1f)

        // lie flat facing up (+Z -> +Y), then the text's up (-Z after the first turn) points away from the user (-Z stays: identity)
        val CODE_ROT: Quat4 = Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(-90.0).toFloat())
    }
}

/**
 * A mobile lead shield (design `entities3d.shield`): 0.60 x 1.80 m panel (lead 0.05 - 1.20, lead glass
 * window 1.20 - 1.85) on a 0.60 x 0.05 x 0.40 base with two posts; the panel is drawn at least 10 mm thick.
 * Local +Z is the facing; local +X the lateral axis. v0.1.1: [fade] turns it see-through (25 %) while the user
 * stands in or right at it (taste profile D14).
 */
internal class ShieldView(private val root: Entity, val id: Int, meshes: DdMeshes, private val m: DdMaterials, pb: Material?) {
    val holder: Entity = Entity()
    private val lead: Part
    private val window: Part
    private val greyParts = ArrayList<Part>()
    private var faded = false
    private val overlay: Part
    private val pbPatch: Part?
    private val panelFrame = Frame(root, meshes.band, m.selected, "shield$id:selPanel")
    private val baseFrame = Frame(root, meshes.band, m.selected, "shield$id:selBase")
    private var tRender = -1f

    init {
        runCatching { holder.setName("shield$id") }
        holder.components.set(boxCollision(Vec3(0.64f, 1.86f, 0.40f)))
        holder.components.set(InteractableComponent())
        root.addChild(holder)
        // collider centred at mid-height: children sit 0.93 m lower
        val body = Entity()
        holder.addChild(body)
        body.setLocal(position = Vec3(0f, -0.93f, 0f))
        greyParts += Part(body, meshes.cube, m.base, "shield$id:base").also { it.place(Vec3(0f, 0.025f, 0f), Vec3(Shield.WIDTH, 0.05f, Shield.BASE_D)) }
        lead = Part(body, meshes.cube, m.lead, "shield$id:lead")
        window = Part(body, meshes.cube, m.glass, "shield$id:window")
        for (sx in listOf(-1f, 1f)) greyParts += Part(body, meshes.cylinder, m.base, "shield$id:post").also { it.place(Vec3(sx * (Shield.HALF_W + 0.01f), 0.95f, 0f), Vec3(0.02f, 1.80f, 0.02f)) }
        overlay = Part(body, meshes.cube, m.shieldedOverlay, "shield$id:overlay")
        pbPatch = pb?.let { Part(body, meshes.quad, it, "shield$id:pb") }
        thickness(6)
        overlay.show(false)
        panelFrame.hide()
        baseFrame.hide()
    }

    fun thickness(tMm: Int) {
        val t = max(tMm, 10) / 1000f
        if (t == tRender) return
        tRender = t
        lead.place(Vec3(0f, (Shield.BOTTOM + Shield.WINDOW) / 2f, 0f), Vec3(Shield.WIDTH, Shield.WINDOW - Shield.BOTTOM, t))
        window.place(Vec3(0f, (Shield.WINDOW + Shield.TOP) / 2f, 0f), Vec3(Shield.WIDTH, Shield.TOP - Shield.WINDOW, t))
        overlay.place(Vec3(0f, (Shield.BOTTOM + Shield.TOP) / 2f, 0f), Vec3(Shield.WIDTH + 0.02f, Shield.TOP - Shield.BOTTOM + 0.02f, t + 0.012f))
        pbPatch?.place(Vec3(-Shield.HALF_W + 0.05f, Shield.WINDOW - 0.06f, t / 2f + 0.0015f), Vec3(0.06f, 0.06f, 1f))
    }

    fun place(s: Shield, floorY: Float, lifted: Boolean) {
        val lift = if (lifted) 0.05f else 0f
        holder.setLocal(position = Vec3(s.x, floorY + 0.93f + lift, s.z), rotation = yawFacing(s.faceDeg))
        thickness(s.tMm)
        lastPose = Triple(s, floorY, lift)
        frames()
    }

    private var lastPose: Triple<Shield, Float, Float>? = null
    private var selectedNow = false

    private fun frames() {
        val (s, floorY, lift) = lastPose ?: return
        if (!selectedNow) {
            panelFrame.hide()
            baseFrame.hide()
            return
        }
        val n = Vec3(s.normal.x, 0f, s.normal.z)
        val lat = Vec3(s.lateral.x, 0f, s.lateral.z)
        val c = Vec3(s.x, floorY + lift, s.z)
        panelFrame.draw(c + Vec3(0f, (Shield.BOTTOM + Shield.TOP) / 2f, 0f) + n * 0.012f, lat, Vec3.UP, Shield.HALF_W + 0.015f, (Shield.TOP - Shield.BOTTOM) / 2f + 0.015f, 0.012f, n)
        baseFrame.draw(c + Vec3(0f, 0.004f, 0f), lat, n, Shield.HALF_W + 0.03f, Shield.BASE_D / 2f + 0.03f, 0.014f, Vec3.UP)
    }

    /** See-through while the user stands in / right at the shield (the view is never filled by a grey wall). */
    fun fade(on: Boolean) {
        if (on == faded) return
        faded = on
        lead.material(if (on) m.leadFaded else m.lead)
        window.material(if (on) m.glassFaded else m.glass)
        greyParts.forEach { it.material(if (on) m.baseFaded else m.base) }
        pbPatch?.show(!on)
        if (on) overlay.show(false)
    }

    fun look(selected: Boolean, shielding: Boolean) {
        overlay.show(shielding && !faded)
        if (selected != selectedNow) {
            selectedNow = selected
            frames()
        }
    }

    fun owns(e: Entity): Boolean = e.id == holder.id

    fun show(visible: Boolean) {
        holder.enabled = visible
        if (!visible) {
            panelFrame.hide()
            baseFrame.hide()
        } else frames()
    }

    fun destroy() {
        runCatching { holder.destroy(true) }
        panelFrame.destroy()
        baseFrame.destroy()
    }
}

/**
 * The isodose rings (design `entities3d.rings`): 1 / 2 / 3 m, 1.5 cm wide, 0.5 cm off the floor, one mesh per
 * zone colour; segments inside a shadow are dashed.
 */
internal class RingsView(root: Entity, m: DdMaterials) {
    private val meshes = Zone.entries.associateWith { DynMesh(root, m.zone[it]!!, "rings:${it.name.lowercase()}") }

    /** One mesh per zone: (zone, rebuild) pairs for the scene's mesh queue. */
    fun plan(segments: List<Rings.Segment>, center: P2, floorY: Float): List<Pair<String, () -> Unit>> {
        val byZone = segments.groupBy { it.zone }
        return Zone.entries.map { z ->
            val segs = byZone[z].orEmpty()
            "rings:${z.name}" to {
                val b = MeshBuilder()
                val y = floorY + 0.005f
                for (s in segs) {
                    val r = Rings.RADII[s.ring]
                    val a0 = 2.0 * Math.PI * s.index / Rings.SEGMENTS
                    val a1 = 2.0 * Math.PI * (s.index + 1) / Rings.SEGMENTS
                    // two pieces per 5 degrees keep the band round; dashed pieces keep their first 55 %
                    for (k in 0 until 2) {
                        val s0 = a0 + (a1 - a0) * k / 2.0
                        val s1 = s0 + (a1 - a0) / 2.0 * (if (s.shielded) 0.55 else 1.0)
                        b.floorBand(center.x + (cos(s0) * r).toFloat(), center.z + (sin(s0) * r).toFloat(), center.x + (cos(s1) * r).toFloat(), center.z + (sin(s1) * r).toFloat(), y, RING_W)
                    }
                }
                meshes[z]!!.set(if (b.isEmpty) null else b)
            }
        }
    }

    fun show(visible: Boolean) = meshes.values.forEach { it.show(visible) }

    fun clearPlan(): List<Pair<String, () -> Unit>> = Zone.entries.map { z -> "rings:${z.name}" to { meshes[z]!!.set(null) } }

    fun destroy() = meshes.values.forEach { it.destroy() }

    private companion object {
        const val RING_W = 0.015f
    }
}

/**
 * The dose-rate floor map (v0.1.1, domain [Heat]): one see-through mesh per colour step, each a set of polar
 * cells around the source's foot, 1.5 mm off the floor (under the shadow and the rings).
 */
internal class HeatView(root: Entity, m: DdMaterials) {
    private val meshes = List(Heat.BINS) { DynMesh(root, m.heat[it], "heat:$it") }

    fun plan(cells: List<Heat.Cell>, center: P2, floorY: Float): List<Pair<String, () -> Unit>> {
        val byBin = cells.groupBy { it.bin }
        val y = floorY + 0.0015f
        return List(Heat.BINS) { b ->
            val cs = byBin[b].orEmpty()
            "heat:$b" to {
                val mb = MeshBuilder()
                for (c in cs) {
                    val r0 = Heat.radius(c.ring)
                    val r1 = Heat.radius(c.ring + 1)
                    val a0 = Heat.angle(c.sector)
                    val a1 = Heat.angle(c.sector + 1)
                    fun p(r: Float, a: Double) = Vec3(center.x + (cos(a) * r).toFloat(), y, center.z + (sin(a) * r).toFloat())
                    mb.quad(p(r0, a0), p(r1, a0), p(r1, a1), p(r0, a1))
                }
                meshes[b].set(if (mb.isEmpty) null else mb)
            }
        }
    }

    fun show(visible: Boolean) = meshes.forEach { it.show(visible) }

    fun clearPlan(): List<Pair<String, () -> Unit>> = List(Heat.BINS) { b -> "heat:$b" to { meshes[b].set(null) } }

    fun destroy() = meshes.forEach { it.destroy() }
}

/** The safe shadow of one shield (design `entities3d.shadow`): passable 22 % fill + 1 cm dashed edge 60 %, 3 mm off the floor. */
internal class ShadowView(root: Entity, m: DdMaterials, id: Int) {
    private val fill = DynMesh(root, m.shadowFill, "shadow$id:fill")
    private val edge = DynMesh(root, m.shadowEdge, "shadow$id:edge")
    var drawn: Shadow.Quad? = null
        private set

    fun plan(key: String, shape: Shadow.Quad?, floorY: Float): List<Pair<String, () -> Unit>> =
        listOf(
            "$key:fill" to {
                drawn = shape
                fill.set(shape?.let { q ->
                    MeshBuilder().apply {
                        val y = floorY + 0.003f
                        quad(q.nearA.at(y), q.nearB.at(y), q.farB.at(y), q.farA.at(y))
                    }
                })
            },
            "$key:edge" to {
                edge.set(shape?.let { q ->
                    MeshBuilder().apply {
                        val y = floorY + 0.0035f
                        val pts = q.points
                        for (i in pts.indices) {
                            val a = pts[i]
                            val b = pts[(i + 1) % pts.size]
                            dashedFloorLine(a.x, a.z, b.x, b.z, y, 0.01f)
                        }
                    }
                })
            },
        )

    private var fillOn = true

    /** [withFill] = false while the floor map is on: the map shows the shadow itself, the dashed edge stays. */
    fun show(visible: Boolean, withFill: Boolean = fillOn) {
        fillOn = withFill
        fill.show(visible && withFill)
        edge.show(visible)
    }

    fun destroy() {
        fill.destroy()
        edge.destroy()
    }
}

/** Placement aids (design `entities3d.ghost`, `spot`): source ghost + landing ring + table snap circle; shield snap frame; drill 3 spot. */
internal class AidsView(root: Entity, meshes: DdMeshes, private val m: DdMaterials) {
    private val ghostPot = Part(root, meshes.cylinder, m.ghost, "ghost:pot")
    private val land = Part(root, meshes.ring, m.landOk, "ghost:land")
    private val tableDash = Part(root, meshes.dashedRing, m.snapDash, "ghost:tableSnap")
    private val tableSolid = Part(root, meshes.thinRing, m.snapSolid, "ghost:tableSnapSolid")
    private val shieldDash = Frame(root, meshes.dashes, m.snapDash, "ghost:shieldSnap")
    private val shieldSolid = Frame(root, meshes.band, m.snapSolid, "ghost:shieldSnapSolid")
    private val heading = Strip(root, meshes.band, m.snapDash, "ghost:heading")
    private val spot = Part(root, meshes.thinRing, m.spot, "spot")
    private val follow = Strip(root, meshes.band, m.follow, "dragFollow")
    private var shakeUntil = 0L

    init {
        hideSource()
        hideShield()
        spot.show(false)
        follow.hide()
    }

    /** Source ghost at [at] (on a table / floor = ok) and the nearest table's centre circle ([snapped] = solid). */
    fun source(at: Vec3?, ok: Boolean, tableCenter: Vec3?, snapped: Boolean) {
        if (at == null) {
            ghostPot.show(false)
            land.show(false)
        } else {
            ghostPot.place(at + Vec3(0f, 0.06f, 0f), Vec3(0.12f, 0.12f, 0.12f))
            land.place(at + Vec3(0f, 0.003f, 0f), Vec3(0.18f, 1f, 0.18f))
            land.material(if (ok) m.landOk else m.landNo)
            ghostPot.show(ok)
        }
        if (tableCenter == null) {
            tableDash.show(false)
            tableSolid.show(false)
        } else {
            val p = tableCenter + Vec3(0f, 0.004f, 0f)
            tableDash.place(p, Vec3(0.20f, 1f, 0.20f))
            tableSolid.place(p, Vec3(0.20f, 1f, 0.20f))
            tableDash.show(!snapped)
            tableSolid.show(snapped)
        }
    }

    fun hideSource() = source(null, false, null, false)

    /** The shield's snap frame at [center] facing [face] (0.60 x 0.40); [snapped] = solid with a short shake. */
    fun shield(center: P2?, faceDeg: Float, floorY: Float, snapped: Boolean, nowMs: Long, ghostAt: P2? = null, ghostFace: Float = 0f) {
        if (center == null) {
            shieldDash.hide()
            shieldSolid.hide()
        } else {
            val n = Shield.facing(faceDeg)
            val shake = if (nowMs < shakeUntil) (sin(nowMs / 18.0) * 0.01).toFloat() else 0f
            val c = Vec3(center.x, floorY + 0.004f, center.z) + Vec3(n.right.x, 0f, n.right.z) * shake
            val lat = Vec3(n.right.x, 0f, n.right.z)
            val nn = Vec3(n.x, 0f, n.z)
            if (snapped) {
                shieldDash.hide()
                shieldSolid.draw(c, lat, nn, Shield.HALF_W, Shield.BASE_D / 2f, 0.012f, Vec3.UP)
            } else {
                shieldSolid.hide()
                shieldDash.draw(c, lat, nn, Shield.HALF_W, Shield.BASE_D / 2f, 0.01f, Vec3.UP)
            }
        }
        if (ghostAt == null) heading.hide()
        else {
            val f = Shield.facing(ghostFace)
            val a = Vec3(ghostAt.x, floorY + 0.005f, ghostAt.z)
            heading.draw(a, a + Vec3(f.x, 0f, f.z) * 0.25f, 0.012f, Vec3.UP)
        }
    }

    fun shake(nowMs: Long) {
        shakeUntil = nowMs + 250L
    }

    fun hideShield() = shield(null, 0f, 0f, false, 0L)

    fun spot(center: P2?, floorY: Float) {
        if (center == null) spot.show(false) else spot.place(Vec3(center.x, floorY + 0.004f, center.z), Vec3(0.6f, 1f, 0.6f))
    }

    /** The drag's follow line (design S09): from below the head to the lifted object. */
    fun follow(from: Vec3?, to: Vec3?) {
        if (from == null || to == null) follow.hide() else follow.draw(from, to, 0.006f, Vec3.UP)
    }
}
