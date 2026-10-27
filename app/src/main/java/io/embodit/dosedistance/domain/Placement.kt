package io.embodit.dosedistance.domain

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * The space kept free around the user (taste profile D14, v0.1.1): nothing the app places, snaps or moves
 * lands closer to the head than [SHIELD_M] (the 1.85 m shield) or [SMALL_M] (the source on the floor),
 * measured on the floor; a point inside is pushed out along head -> point.
 */
object Clearance {
    const val SHIELD_M = 0.8f
    const val SMALL_M = 0.6f

    /** The shield fades once the head comes this close to its panel (and back above [FADE_OUT_M]). */
    const val FADE_IN_M = 0.4f
    const val FADE_OUT_M = 0.45f

    /** [p] pushed out to [min] from [head]; [fallback] is the direction when [p] is right under the head. */
    fun pushOut(p: P2, head: P2?, min: Float, fallback: P2): P2 {
        if (head == null) return p
        val d = p - head
        val l = d.length
        if (l >= min) return p
        val dir = if (l > 1e-3f) d * (1f / l) else fallback.normalized().takeIf { it.length > 0.5f } ?: P2(0f, -1f)
        return head + dir * min
    }

    /** "Bring to front" (D9): [dist] ahead of the head along its level heading. */
    fun inFront(head: P2, heading: P2, dist: Float = FRONT_M): P2 = head + heading.normalized() * dist

    const val FRONT_M = 1.2f
}

/**
 * The shield's snap point (design `rules.snap`, changed in v0.1.1): 0.5 m from the source towards the user
 * (drill 3: the source - spot midpoint), slid towards the source until it is [Clearance.SHIELD_M] from the
 * anchor (the head, or drill 3's spot), never closer than [MIN_FROM_SOURCE] to the source. Null when no such
 * point keeps the live head clear: the shield then lands where it is put (pushed out of the clearance).
 */
object Snap {
    const val FROM_SOURCE = 0.5f
    const val MIN_FROM_SOURCE = 0.3f

    fun point(source: P2, head: P2?, spot: P2?, fallbackDir: P2): P2? {
        val anchor = spot ?: head
        val dir = anchor?.let { (it - source).takeIf { d -> d.length > 0.05f }?.normalized() } ?: fallbackDir.normalized()
        val dAnchor = anchor?.let { (it - source).length } ?: (FROM_SOURCE + Clearance.SHIELD_M)
        val wanted = if (spot != null) dAnchor / 2f else FROM_SOURCE
        val along = minOf(wanted, dAnchor - Clearance.SHIELD_M)
        if (along < MIN_FROM_SOURCE) return null
        val p = source + dir * along
        if (head != null && (p - head).length < Clearance.SHIELD_M - 1e-3f) return null
        return p
    }
}

/** Horizontal distance from [p] to the shield's panel (its 0.60 m foot segment). */
fun Shield.planDistance(p: P2): Float {
    val c = center
    val lat = lateral
    val t = ((p.x - c.x) * lat.x + (p.z - c.z) * lat.z).coerceIn(-Shield.HALF_W, Shield.HALF_W)
    val q = c + lat * t
    return (p - q).length
}

/**
 * The dose-rate floor map (v0.1.1, user: "可以用底面辐射颜色来表示强弱"): a polar grid around the source's
 * foot, rings spaced logarithmically from [R0] to [R1], every cell coloured by the rate a head at the current
 * height reads above its centre (with the shields, so the safe shadow shows up by itself). The colour is a
 * level 0..1 on a log scale from [H_LO] (safe) to [H_HI] (danger), drawn in [BINS] steps.
 */
object Heat {
    const val R0 = 0.10f
    const val R1 = 4.0f
    const val RINGS = 22
    const val SECTORS = 120
    const val BINS = 8
    const val H_LO = 0.5f
    const val H_HI = 1000f

    data class Cell(val ring: Int, val sector: Int, val bin: Int)

    /** 0 at [H_LO] and below, 1 at [H_HI] and above, log in between. */
    fun level(h: Float): Float = (log10(max(h, 1e-6f) / H_LO) / log10(H_HI / H_LO)).coerceIn(0f, 1f)

    fun bin(h: Float): Int = (level(h) * BINS).toInt().coerceIn(0, BINS - 1)

    /** The level a bin is drawn with (its middle). */
    fun binLevel(bin: Int): Float = (bin + 0.5f) / BINS

    fun radius(i: Int): Float = R0 * (R1 / R0).pow(i.toFloat() / RINGS)

    fun angle(j: Int): Double = 2.0 * Math.PI * j / SECTORS

    /** Rate at the centre of cell (i, j). */
    fun rateAt(iso: Isotope, core: Vec3, i: Int, j: Int, headY: Float, shields: List<Shield>, floorY: Float): Float {
        val r = (radius(i) + radius(i + 1)) / 2f
        val a = (angle(j) + angle(j + 1)) / 2.0
        val p = P2(core.x + (cos(a) * r).toFloat(), core.z + (sin(a) * r).toFloat())
        return Reading.at(iso, core, Vec3(p.x, headY, p.z), shields, floorY).h
    }

    fun cells(iso: Isotope, core: Vec3, headY: Float, shields: List<Shield>, floorY: Float): List<Cell> {
        val out = ArrayList<Cell>(RINGS * SECTORS)
        for (i in 0 until RINGS) for (j in 0 until SECTORS) out += Cell(i, j, bin(rateAt(iso, core, i, j, headY, shields, floorY)))
        return out
    }

    /** Legend ticks (µSv/h) and their place on the bar. */
    val TICKS = listOf(H_LO, 10f, 100f, H_HI)
}
