package io.embodit.dosedistance.ui.dose.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.core.math.Vector4
import io.embodit.dosedistance.domain.Heat
import io.embodit.dosedistance.domain.Vec3
import io.embodit.dosedistance.domain.Zone
import io.embodit.dosedistance.platform.DdLog
import io.embodit.dosedistance.ui.theme.DdColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Scene-lifetime materials, one per look, shared by every entity (design `palette.brandTokens`). Unlit
 * colours are exact only with tone mapping off (Spatial Ruler). The pot and the shield are lit.
 */
internal class DdMaterials(private val shared: SharedResources) {
    val lead: Material = lit(DdColors.LeadGrey, roughness = 0.7f, metallic = 0.3f)
    val base: Material = lit(DdColors.BaseGrey, roughness = 0.8f, metallic = 0.1f)
    val band: Material = lit(DdColors.RadYellow, roughness = 0.6f, metallic = 0f, glow = 0.35f)
    val recess: Material = unlit(DdColors.BaseGrey)
    val core: Material = unlit(DdColors.SourceMagenta.boost(1.5f))
    val glass: Material = sheer(DdColors.GlassBlue, 0.35f)
    val selected: Material = unlit(DdColors.Interaction, twoSided = true)
    val locked: Material = unlit(DdColors.StaleGrey, twoSided = true)
    val shieldedOverlay: Material = sheer(DdColors.Passable, 0.15f)
    val shadowFill: Material = sheer(DdColors.Passable, 0.22f)
    val shadowEdge: Material = sheer(DdColors.Passable, 0.60f)
    val ghost: Material = sheer(DdColors.LeadGrey, 0.30f)
    val landOk: Material = sheer(DdColors.Passable, 0.85f)
    val landNo: Material = sheer(DdColors.StaleGrey, 0.6f)
    val snapDash: Material = sheer(DdColors.Interaction, 0.85f)
    val snapSolid: Material = unlit(DdColors.Interaction, twoSided = true)
    val spot: Material = unlit(DdColors.Interaction, twoSided = true)
    val follow: Material = sheer(DdColors.White, 0.5f)
    val stale: Material = sheer(DdColors.StaleGrey, 0.8f)

    /** v0.1.1: faded looks of the shield while the user stands in it (taste profile D14: at most 30 %). */
    val leadFaded: Material = sheer(DdColors.LeadGrey, 0.25f)
    val baseFaded: Material = sheer(DdColors.BaseGrey, 0.25f)
    val glassFaded: Material = sheer(DdColors.GlassBlue, 0.12f)

    /** The floor map's colour steps (domain `Heat`), see-through so the real floor still shows. */
    val heat: List<Material> = List(Heat.BINS) { sheer(DdColors.heat(Heat.binLevel(it)), HEAT_OPACITY) }

    /** Ring segment colours by zone (design `palette.brandTokens.zone*`); rings are opaque flat bands on the floor. */
    val zone: Map<Zone, Material> = Zone.entries.associateWith { sheer(DdColors.ofZone(it), 0.9f) }

    fun unlit(color: Color, twoSided: Boolean = false): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                runCatching { setApplyToneMapping(false) }
                if (twoSided) setCullingMode(MaterialCullingMode.NONE)
            }
        )

    /** See-through: the colour is opaque, the material's opacity carries the share (Spatial Ruler). */
    fun sheer(color: Color, opacity: Float): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )

    private fun lit(color: Color, roughness: Float, metallic: Float, glow: Float = 0.2f): Material =
        shared.keep(
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                setMetallic(metallic)
                setRoughness(roughness)
                setEmissiveColor(Color4(color.red * glow, color.green * glow, color.blue * glow, 1f))
            }
        )

    /** An opaque unlit material showing [bitmap]; null when the platform refuses the texture (the caller falls back to a colour). */
    fun textured(bitmap: Bitmap, name: String): Material? =
        runCatching {
                val texture = shared.keep(TextureResource.create(bitmap))
                shared.keep(
                    UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                        setBaseColor(Color4(1f, 1f, 1f, 1f))
                        setBaseColorTexture(texture)
                        runCatching { setApplyToneMapping(false) }
                        setCullingMode(MaterialCullingMode.NONE)
                    }
                )
            }
            .onFailure { DdLog.w("TEXTURE $name failed: $it") }
            .getOrNull()
}

private const val HEAT_OPACITY = 0.42f

private fun Color.boost(k: Float): Color = Color(min(1f, red * k), min(1f, green * k), min(1f, blue * k), alpha)

/**
 * The shared meshes of the scene, built one at a time with [pace] between them: each mesh waits for the render
 * thread, and at the idle emulator's 1 - 2 fps a batch of them blocks the main thread into an ANR (factory finding).
 */
internal class DdMeshes {
    lateinit var cube: MeshResource
    lateinit var band: MeshResource
    lateinit var dashes: MeshResource
    lateinit var disc: MeshResource
    lateinit var ring: MeshResource
    lateinit var thinRing: MeshResource
    lateinit var dashedRing: MeshResource
    lateinit var cylinder: MeshResource
    lateinit var tube: MeshResource
    lateinit var quad: MeshResource

    suspend fun build(shared: SharedResources, pace: suspend () -> Unit) {
        cube = shared.keep(MeshFactory.unitCube("unitCube")); pace()
        band = shared.keep(MeshFactory.unitBand("unitBand")); pace()
        dashes = shared.keep(MeshFactory.unitBand("unitDashes", dashes = 8)); pace()
        disc = shared.keep(MeshFactory.unitDisc("unitDisc")); pace()
        ring = shared.keep(MeshFactory.unitRing("unitRing", inner = 0.44f)); pace()
        thinRing = shared.keep(MeshFactory.unitRing("thinRing", inner = 0.47f, segments = 64)); pace()
        dashedRing = shared.keep(ExtraMeshes.dashedRing("dashedRing", inner = 0.46f, dashes = 16)); pace()
        cylinder = shared.keep(ExtraMeshes.unitCylinder("unitCylinder")); pace()
        tube = shared.keep(ExtraMeshes.unitTube("unitTube", inner = 0.8f / 1.2f)); pace()
        quad = shared.keep(ExtraMeshes.unitQuadUv("unitQuadUv"))
    }
}

internal object ExtraMeshes {
    private val FRONT = Vector3(0f, 0f, 1f)
    private val UP = Vector3(0f, 1f, 0f)

    /** Unit cylinder: radius 0.5, height 1 along Y, centred, with caps. */
    fun unitCylinder(name: String, segments: Int = 24): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (i in 0..segments) {
            val a = 2 * PI * i / segments
            val x = (cos(a) * 0.5).toFloat()
            val z = (sin(a) * 0.5).toFloat()
            positions += Vector3(x, -0.5f, z)
            positions += Vector3(x, 0.5f, z)
            val n = Vector3(x * 2f, 0f, z * 2f)
            normals += n
            normals += n
        }
        for (i in 0 until segments) {
            val a = i * 2
            indices += listOf(a, a + 2, a + 1, a + 1, a + 2, a + 3)
        }
        for ((y, up) in listOf(0.5f to true, -0.5f to false)) {
            val c = positions.size
            positions += Vector3(0f, y, 0f)
            normals += Vector3(0f, if (up) 1f else -1f, 0f)
            for (i in 0 until segments) {
                val a = 2 * PI * i / segments
                positions += Vector3((cos(a) * 0.5).toFloat(), y, (sin(a) * 0.5).toFloat())
                normals += Vector3(0f, if (up) 1f else -1f, 0f)
            }
            for (i in 0 until segments) {
                val b = c + 1 + i
                val d = c + 1 + (i + 1) % segments
                if (up) indices += listOf(c, d, b) else indices += listOf(c, b, d)
            }
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.5f)), name = name)
    }

    /** A tube (outer radius 0.5, inner 0.5 x [inner], height 1 along Y, centred) with a top annulus: the pot's rim around the opening. */
    fun unitTube(name: String, inner: Float, segments: Int = 24): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        val ri = 0.5f * inner
        for ((r, outward) in listOf(0.5f to true, ri to false)) {
            val base = positions.size
            for (i in 0..segments) {
                val a = 2 * PI * i / segments
                val x = (cos(a) * r).toFloat()
                val z = (sin(a) * r).toFloat()
                positions += Vector3(x, -0.5f, z)
                positions += Vector3(x, 0.5f, z)
                val s = if (outward) 1f else -1f
                val n = Vector3((cos(a) * s).toFloat(), 0f, (sin(a) * s).toFloat())
                normals += n
                normals += n
            }
            for (i in 0 until segments) {
                val a = base + i * 2
                if (outward) indices += listOf(a, a + 2, a + 1, a + 1, a + 2, a + 3) else indices += listOf(a, a + 1, a + 2, a + 1, a + 3, a + 2)
            }
        }
        val top = positions.size
        for (i in 0..segments) {
            val a = 2 * PI * i / segments
            positions += Vector3((cos(a) * 0.5).toFloat(), 0.5f, (sin(a) * 0.5).toFloat())
            positions += Vector3((cos(a) * ri).toFloat(), 0.5f, (sin(a) * ri).toFloat())
            normals += UP
            normals += UP
        }
        for (i in 0 until segments) {
            val a = top + i * 2
            indices += listOf(a, a + 1, a + 2, a + 1, a + 3, a + 2)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.5f)), name = name)
    }

    /** A flat dashed ring (outer 0.5) in the local XZ plane facing +Y: snap circles. */
    fun dashedRing(name: String, inner: Float, dashes: Int, fill: Float = 0.6f, perDash: Int = 4): MeshResource {
        val positions = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (d in 0 until dashes) {
            val a0 = 2 * PI * d / dashes
            val span = 2 * PI / dashes * fill
            for (k in 0 until perDash) {
                val s = a0 + span * k / perDash
                val e = a0 + span * (k + 1) / perDash
                val b = positions.size
                positions += Vector3((cos(s) * inner).toFloat(), 0f, (sin(s) * inner).toFloat())
                positions += Vector3((cos(s) * 0.5).toFloat(), 0f, (sin(s) * 0.5).toFloat())
                positions += Vector3((cos(e) * 0.5).toFloat(), 0f, (sin(e) * 0.5).toFloat())
                positions += Vector3((cos(e) * inner).toFloat(), 0f, (sin(e) * inner).toFloat())
                indices += listOf(b, b + 2, b + 1, b, b + 3, b + 2)
            }
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { UP })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.001f, 0.5f)), name = name)
    }

    /**
     * A unit quad in local XY facing +Z with UVs for decals (trefoil, pot code, Pb patch). The engine samples v from
     * the bottom (emulator: v = 0 at the top drew "Pb" upside down as "bP"; bianzhong found the same), so v = 0 is -Y.
     */
    fun unitQuadUv(name: String): MeshResource {
        val positions = listOf(Vector3(-0.5f, -0.5f, 0f), Vector3(0.5f, -0.5f, 0f), Vector3(0.5f, 0.5f, 0f), Vector3(-0.5f, 0.5f, 0f))
        val uv = listOf(Vector2(0f, 0f), Vector2(1f, 0f), Vector2(1f, 1f), Vector2(0f, 1f))
        val model =
            MeshModel(
                positions = positions,
                triangleIndices = listOf(0, 1, 2, 0, 2, 3),
                normals = List(4) { FRONT },
                tangents = List(4) { Vector4(1f, 0f, 0f, 1f) },
                uv0 = uv,
            )
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.001f)), name = name)
    }
}

/** Bitmaps drawn at run time for the decals (design `primitiveRecipes`): no image assets. */
internal object Decals {
    /** The radiation trefoil, black on radiation yellow (public symbol; design `brandTokens.sourceMagenta` note). */
    fun trefoil(size: Int = 256): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(DdColors.RadYellow.toArgb())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DdColors.Trefoil.toArgb() }
        val cx = size / 2f
        val cy = size / 2f
        val r = size * 0.42f
        val r0 = r * 0.2f
        val rInner = r * 0.3f
        c.drawCircle(cx, cy, r0, p)
        for (k in 0 until 3) {
            // blades span 60 degrees, one pointing down (canvas angles run clockwise from 3 o'clock)
            val start = 60f + k * 120f
            val path = Path()
            path.arcTo(RectF(cx - r, cy - r, cx + r, cy + r), start, 60f, true)
            path.arcTo(RectF(cx - rInner, cy - rInner, cx + rInner, cy + rInner), start + 60f, -60f)
            path.close()
            c.drawPath(path, p)
        }
        return bmp
    }

    /** The 3-digit code in the pot (drill 2): black on white, or "— — —". */
    fun code(text: String, w: Int = 256, h: Int = 144): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(DdColors.White.toArgb())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DdColors.Trefoil.toArgb()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = h * 0.78f
        }
        val shown = if (text.isBlank()) "— — —" else text
        if (text.isBlank()) p.textSize = h * 0.5f
        val y = h / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(shown, w / 2f, y, p)
        return bmp
    }

    /** The "Pb" patch on the shield. */
    fun pb(size: Int = 128): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(DdColors.White.toArgb())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DdColors.Trefoil.toArgb()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = size * 0.55f
        }
        c.drawText("Pb", size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p)
        return bmp
    }
}

/** A triangle soup under construction (rings, shadows); turned into a mesh resource at the end. */
internal class MeshBuilder {
    val positions = ArrayList<Vector3>()
    val indices = ArrayList<Int>()
    private var minX = Float.MAX_VALUE; private var minY = Float.MAX_VALUE; private var minZ = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE; private var maxY = -Float.MAX_VALUE; private var maxZ = -Float.MAX_VALUE

    val isEmpty: Boolean
        get() = indices.isEmpty()

    fun vertex(p: Vec3): Int {
        positions += Vector3(p.x, p.y, p.z)
        minX = min(minX, p.x); minY = min(minY, p.y); minZ = min(minZ, p.z)
        maxX = max(maxX, p.x); maxY = max(maxY, p.y); maxZ = max(maxZ, p.z)
        return positions.size - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
        indices += a
        indices += b
        indices += c
    }

    fun quad(a: Vec3, b: Vec3, c: Vec3, d: Vec3) {
        val i = vertex(a)
        val j = vertex(b)
        val k = vertex(c)
        val l = vertex(d)
        tri(i, j, k)
        tri(i, k, l)
    }

    /** A flat band of width [w] on the horizontal plane at height [y], from [a] to [b] (x, z). */
    fun floorBand(ax: Float, az: Float, bx: Float, bz: Float, y: Float, w: Float) {
        val dx = bx - ax
        val dz = bz - az
        val l = kotlin.math.sqrt(dx * dx + dz * dz)
        if (l < 1e-5f) return
        val nx = -dz / l * w / 2f
        val nz = dx / l * w / 2f
        quad(Vec3(ax - nx, y, az - nz), Vec3(bx - nx, y, bz - nz), Vec3(bx + nx, y, bz + nz), Vec3(ax + nx, y, az + nz))
    }

    /** A dashed floor line: [dash] long pieces every [period] metres. */
    fun dashedFloorLine(ax: Float, az: Float, bx: Float, bz: Float, y: Float, w: Float, dash: Float = 0.06f, period: Float = 0.10f) {
        val dx = bx - ax
        val dz = bz - az
        val l = kotlin.math.sqrt(dx * dx + dz * dz)
        if (l < 1e-4f) return
        var s = 0f
        while (s < l) {
            val e = min(l, s + dash)
            floorBand(ax + dx * s / l, az + dz * s / l, ax + dx * e / l, az + dz * e / l, y, w)
            s += period
        }
    }

    fun build(name: String): MeshResource? {
        if (isEmpty) return null
        val center = Vector3((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)
        val half = Vector3(max(0.001f, (maxX - minX) / 2f), max(0.001f, (maxY - minY) / 2f), max(0.001f, (maxZ - minZ) / 2f))
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { Vector3(0f, 1f, 0f) })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = center, halfExtent = half), name = name)
    }
}

/**
 * A mesh that is rebuilt from scratch when its data changes (ring bands, shadows). The old mesh is closed
 * once the new entity is up. Creating a mesh waits for the render thread (about a frame): the scene
 * queues the rebuilds, at most two per frame (cam-coverage emulator finding).
 */
internal class DynMesh(private val host: Entity, private var material: Material, private val name: String) {
    private var mesh: MeshResource? = null
    private var entity: Entity? = null
    private var on = true

    fun set(builder: MeshBuilder?) {
        val next = builder?.build(name)
        runCatching { entity?.destroy(true) }
        entity = null
        val old = mesh
        mesh = next
        if (next != null) {
            next.toGlobal()
            val e = ModelEntity(next, material)
            runCatching { e.setName(name) }
            host.addChild(e)
            e.enabled = on
            entity = e
        }
        old?.let { runCatching { it.close() } }
    }

    fun material(m: Material) {
        if (m === material) return
        material = m
        entity?.setMaterial(m)
    }

    fun show(visible: Boolean) {
        if (on == visible) return
        on = visible
        entity?.enabled = visible
    }

    fun destroy() {
        runCatching { entity?.destroy(true) }
        entity = null
        mesh?.let { runCatching { it.close() } }
        mesh = null
    }
}
