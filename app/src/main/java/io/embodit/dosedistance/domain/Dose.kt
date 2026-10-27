package io.embodit.dosedistance.domain

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The three teaching isotopes (design `model.isotopes`): dose rate at 1 m from a 1 GBq source (rounded,
 * µSv/h) and the half-value layer in lead (mm).
 */
enum class Isotope(val tag: String, val h1: Float, val hvlMm: Float) {
    TC99M("tc99m", 20f, 0.3f),
    CS137("cs137", 93f, 6.5f),
    CO60("co60", 350f, 12f);

    companion object {
        fun byTag(tag: String?): Isotope = entries.firstOrNull { it.tag == tag } ?: CS137
    }
}

/** Interface zones of a dose rate (design `model.zones`); not legal limits. */
enum class Zone {
    BACKGROUND, LOW, MID, HIGH, EXTREME;

    companion object {
        fun of(h: Float): Zone =
            when {
                h < 0.5f -> BACKGROUND
                h < 10f -> LOW
                h < 100f -> MID
                h < 1000f -> HIGH
                else -> EXTREME
            }
    }
}

/**
 * The teaching model (design `model.doseRate`): point source, inverse square, lead half-value layers and
 * a fixed background. Nothing else: no scatter, no air, real walls do not shield.
 */
object Dose {
    const val BACKGROUND = 0.1f
    const val MIN_D = 0.3f
    val THICKNESSES = listOf(1, 3, 6, 12, 24)
    const val DEFAULT_T = 6

    /** H(d) in µSv/h; [attenuation] is the product of the crossed plates' factors. */
    fun rate(iso: Isotope, d: Float, attenuation: Float = 1f): Float {
        val dd = max(MIN_D, d)
        return iso.h1 / (dd * dd) * attenuation + BACKGROUND
    }

    fun factor(iso: Isotope, tMm: Int): Float = 2.0.pow(-tMm / iso.hvlMm.toDouble()).toFloat()

    fun attenuation(iso: Isotope, thicknesses: List<Int>): Float = thicknesses.fold(1f) { a, t -> a * factor(iso, t) }

    /** "47%", "99.9%", "> 99.9%" (design `model.attenuation`). */
    fun blockedText(a: Float): String {
        if (a < 0.0005f) return "> 99.9%"
        val pct = (1f - a) * 100f
        return if (pct > 99f) String.format(Locale.US, "%.1f%%", floor(pct * 10f) / 10f) else "${pct.roundToInt()}%"
    }

    /** Number and unit of a dose rate (design `model.format`): 9.3 / 93 / 1.03 mSv/h. */
    fun formatRate(h: Float): Pair<String, String> =
        when {
            h < 10f -> String.format(Locale.US, "%.1f", h) to "µSv/h"
            h < 999.5f -> "${h.roundToInt()}" to "µSv/h"
            else -> String.format(Locale.US, "%.2f", h / 1000f) to "mSv/h"
        }

    fun rateText(h: Float): String = formatRate(h).let { (n, u) -> if (u == "µSv/h") n else "$n $u" }

    /** Two significant figures (cumulative dose, design `model.cumulative`). */
    fun sig2(v: Float): String {
        if (v <= 0f || !v.isFinite()) return "0"
        val bd = BigDecimal(v.toDouble()).round(MathContext(2))
        return bd.stripTrailingZeros().toPlainString().let { if (it.contains('E')) bd.toPlainString() else it }
    }

    /** Half-value layers in a factor (result card: "3.7 half-value layers"). */
    fun layers(a: Float): Float = if (a <= 0f) 0f else (ln(1.0 / a) / ln(2.0)).toFloat()

    /** Geiger clicks per second (design `model.geiger`); >= 400 µSv/h is a continuous tone. */
    fun clicksPerSecond(h: Float): Float = (1f + h / 10f).coerceIn(1f, 40f)

    const val TONE_FROM = 400f
}

/**
 * The frame QA coordinates are written in (design `model.qaScene`): origin at the source core, x towards
 * the user at placement, y up, z = x's right-hand side seen from above. Yaw 0 faces +x.
 */
data class SourceFrame(val origin: Vec3, val ex: P2) {
    val ez: P2
        get() = ex.right

    fun world(x: Float, y: Float, z: Float): Vec3 = Vec3(origin.x + ex.x * x + ez.x * z, origin.y + y, origin.z + ex.z * x + ez.z * z)

    fun floorPoint(x: Float, z: Float): P2 = P2(origin.x + ex.x * x + ez.x * z, origin.z + ex.z * x + ez.z * z)

    fun local(p: Vec3): Vec3 {
        val d = P2(p.x - origin.x, p.z - origin.z)
        return Vec3(d.dot(ex), p.y - origin.y, d.dot(ez))
    }

    /** A facing in this frame (yaw degrees, 0 = +x) as a world facing angle (see [Shield.faceDeg]). */
    fun worldFace(yawDeg: Float): Float {
        val r = Math.toRadians(yawDeg.toDouble())
        val f = ex * cos(r).toFloat() + ez * sin(r).toFloat()
        return Math.toDegrees(atan2(f.z.toDouble(), f.x.toDouble())).toFloat()
    }

    fun localFace(faceDeg: Float): Float {
        val f = Shield.facing(faceDeg)
        return Math.toDegrees(atan2(f.dot(ez).toDouble(), f.dot(ex).toDouble())).toFloat()
    }
}

/**
 * A mobile lead shield standing on the floor (design `entities3d.shield`): centre (x, z) in Stage metres,
 * facing angle (degrees; facing = (cos, sin) on the floor) and nominal thickness in mm.
 */
data class Shield(val id: Int, val x: Float, val z: Float, val faceDeg: Float, val tMm: Int) {
    val center: P2
        get() = P2(x, z)

    val normal: P2
        get() = facing(faceDeg)

    /** Lateral axis of the panel. */
    val lateral: P2
        get() = normal.right

    /**
     * Whether the segment [a] -> [b] crosses the panel (lead + lead glass window, 0.6 x 1.8 m from 0.05 to
     * 1.85 above the floor at [floorY]); the nominal thickness counts whatever the angle.
     */
    fun crosses(a: Vec3, b: Vec3, floorY: Float): Boolean {
        val n = normal
        val sa = (a.x - x) * n.x + (a.z - z) * n.z
        val sb = (b.x - x) * n.x + (b.z - z) * n.z
        if (sa == 0f && sb == 0f) return false
        if ((sa > 0f) == (sb > 0f) && sa != 0f && sb != 0f) return false
        val t = sa / (sa - sb)
        val p = a + (b - a) * t
        val lat = (p.x - x) * lateral.x + (p.z - z) * lateral.z
        if (abs(lat) > Shield.HALF_W) return false
        val h = p.y - floorY
        return h >= Shield.BOTTOM && h <= Shield.TOP
    }

    companion object {
        const val WIDTH = 0.60f
        const val HALF_W = 0.30f
        const val BOTTOM = 0.05f
        const val WINDOW = 1.20f
        const val TOP = 1.85f
        const val BASE_D = 0.40f
        const val MAX = 2

        fun facing(deg: Float): P2 {
            val r = Math.toRadians(deg.toDouble())
            return P2(cos(r).toFloat(), sin(r).toFloat())
        }

        fun faceTowards(from: P2, to: P2): Float {
            val d = to - from
            return Math.toDegrees(atan2(d.z.toDouble(), d.x.toDouble())).toFloat()
        }
    }
}

/** One frame's reading at the head (design `rules.dose`). */
data class Reading(
    val h: Float,
    val d: Float,
    val attenuation: Float,
    val crossed: Set<Int>,
) {
    val zone: Zone
        get() = Zone.of(h)

    val tooClose: Boolean
        get() = d < Dose.MIN_D

    val shielded: Boolean
        get() = crossed.isNotEmpty()

    companion object {
        /** H at [head] from the source core [core], with the plates the segment crosses. */
        fun at(iso: Isotope, core: Vec3, head: Vec3, shields: List<Shield>, floorY: Float): Reading {
            val crossed = HashSet<Int>()
            var a = 1f
            for (s in shields) if (s.crosses(core, head, floorY)) {
                crossed += s.id
                a *= Dose.factor(iso, s.tMm)
            }
            val d = core.distanceTo(head)
            return Reading(Dose.rate(iso, d, a), d, a, crossed)
        }
    }
}

/**
 * The safe shadow (design `model.shadow`): the floor region where a head at height y_h is behind the panel.
 * The projection of a panel point of height y onto the head plane lies at x(y) / L = (y_h - y_s) / (y - y_s)
 * from the source, the same ratio for every point of the panel's foot line: so the shadow is the panel's
 * foot segment scaled from the source by k_near .. k_far, cut at 4 m (scaled by the centre's distance).
 */
object Shadow {
    const val CAP_M = 4f

    data class Quad(val nearA: P2, val nearB: P2, val farB: P2, val farA: P2) {
        val points: List<P2>
            get() = listOf(nearA, nearB, farB, farA)

        /** Point-in-quad (convex, either winding). */
        fun contains(p: P2): Boolean {
            val pts = points
            var sign = 0f
            for (i in pts.indices) {
                val a = pts[i]
                val b = pts[(i + 1) % pts.size]
                val c = (b.x - a.x) * (p.z - a.z) - (b.z - a.z) * (p.x - a.x)
                if (abs(c) < 1e-9f) continue
                if (sign == 0f) sign = c else if (c * sign < 0f) return false
            }
            return true
        }
    }

    /**
     * The scale range (k_near, k_far) for source height [ys], panel bottom [yb] / top [yt] and head height
     * [yh] (all above the floor); k_far may be infinite; null = no shadow.
     */
    fun ratios(ys: Float, yb: Float, yt: Float, yh: Float): Pair<Float, Float>? {
        val inf = Float.POSITIVE_INFINITY
        return when {
            yh > ys -> {
                if (yt <= ys) return null
                max(1f, (yh - ys) / (yt - ys)) to inf
            }
            yh < ys -> {
                if (yb >= ys) return null
                val near = max(1f, (yh - ys) / (yb - ys))
                val far = if (yt < ys) (yh - ys) / (yt - ys) else inf
                if (far < 1f) null else near to far
            }
            else -> if (yb <= ys && ys <= yt) 1f to inf else null
        }
    }

    /** The quad of one panel, or null. [ys] / [yh] above the floor. */
    fun quad(source: P2, ys: Float, shield: Shield, yh: Float): Quad? {
        val (kNear, kFarRaw) = ratios(ys, Shield.BOTTOM, Shield.TOP, yh) ?: return null
        val c = shield.center
        val lc = (c - source).length
        if (lc < 0.05f) return null
        val kFar = minOf(kFarRaw, CAP_M / lc)
        if (kFar <= kNear) return null
        val e1 = c - shield.lateral * Shield.HALF_W
        val e2 = c + shield.lateral * Shield.HALF_W
        fun at(e: P2, k: Float) = source + (e - source) * k
        return Quad(at(e1, kNear), at(e2, kNear), at(e2, kFar), at(e1, kFar))
    }
}

/**
 * Isodose rings (design `entities3d.rings`, `model.ringRate`): 1 / 2 / 3 m around the source's foot point,
 * 72 segments each, every segment coloured by the rate a head at the current height would read there.
 */
object Rings {
    val RADII = listOf(1f, 2f, 3f)
    const val SEGMENTS = 72

    data class Segment(val ring: Int, val index: Int, val zone: Zone, val shielded: Boolean)

    /** Rate a head at height [headY] above floor point [p] reads. */
    fun rateAt(iso: Isotope, core: Vec3, p: P2, headY: Float, shields: List<Shield>, floorY: Float): Reading = Reading.at(iso, core, Vec3(p.x, headY, p.z), shields, floorY)

    fun segments(iso: Isotope, core: Vec3, headY: Float, shields: List<Shield>, floorY: Float): List<Segment> {
        val out = ArrayList<Segment>(RADII.size * SEGMENTS)
        for ((ri, r) in RADII.withIndex()) for (i in 0 until SEGMENTS) {
            val a = 2.0 * Math.PI * (i + 0.5) / SEGMENTS
            val p = P2(core.x + (cos(a) * r).toFloat(), core.z + (sin(a) * r).toFloat())
            val reading = rateAt(iso, core, p, headY, shields, floorY)
            out += Segment(ri, i, reading.zone, reading.shielded)
        }
        return out
    }
}

/** The three drills' arithmetic (design `drills[]`). */
object Drills {
    const val BUDGET_DISTANCE = 2f
    const val BUDGET_TIME = 5f
    const val BUDGET_SHIELD = 10f
    const val HOLD_MS = 3000L
    const val READ_MS = 1000L
    const val READ_MAX_D = 0.8f
    const val TIME_LIMIT_MS = 60_000L
    const val SPOT_R = 0.3f
    const val SPOT_MIN = 1.0f
    const val SPOT_MAX = 2.5f
    const val OUT_MS = 500L
    const val PASS_A = 0.1275f
    const val GAZE_R = 0.05f

    /** Drill 1: the clamped start distance and the target (a quarter of the start, background kept). */
    fun distanceStart(iso: Isotope, d: Float): Triple<Float, Float, Float> {
        val dStart = max(0.5f, d)
        val hStart = Dose.rate(iso, dStart)
        return Triple(dStart, hStart, (hStart - Dose.BACKGROUND) / 4f + Dose.BACKGROUND)
    }

    /** Drill 1 status: metres still to go = sqrt(4 d0² - Δy²) - r. */
    fun toGo(dStart: Float, dy: Float, r: Float): Float = sqrt(max(0f, 4f * dStart * dStart - dy * dy)) - r

    /** Drill 3: the target shown on the panel. */
    fun shieldTarget(h0: Float): Float = (h0 - Dose.BACKGROUND) * PASS_A + Dose.BACKGROUND

    fun shieldPasses(a: Float): Boolean = a <= PASS_A

    /** Drill 3 spot: the head's floor point pulled into 1.0 .. 2.5 m of the source. Second = was pulled in from afar. */
    fun spot(source: P2, head: P2, fallbackDir: P2): Pair<P2, Boolean> {
        val d = head - source
        val r = d.length
        val dir = if (r < 1e-3f) fallbackDir.normalized() else d / r
        return when {
            r < SPOT_MIN -> source + dir * SPOT_MIN to false
            r > SPOT_MAX -> source + dir * SPOT_MAX to true
            else -> head to false
        }
    }

    /** Drill 2: the head's view ray passes within [GAZE_R] of the pot opening [mouth]. */
    fun gazesAt(head: Vec3, forward: Vec3, mouth: Vec3): Boolean {
        val f = forward.normalized()
        val to = mouth - head
        val t = to.dot(f)
        if (t <= 0f) return false
        return (to - f * t).length <= GAZE_R
    }
}

private operator fun P2.div(s: Float) = P2(x / s, z / s)
