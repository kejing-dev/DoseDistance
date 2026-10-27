package io.embodit.dosedistance.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** design `model.unitTests` (tolerance ±0.1) */
class DoseModelTest {
    private val cs = Isotope.CS137
    private val co = Isotope.CO60
    private val tc = Isotope.TC99M

    // standard scene: table 0.75, core 0.86, floor 0, frame x = +X
    private val floorY = 0f
    private val frame = SourceFrame(Vec3(0f, 0.86f, 0f), P2(1f, 0f))
    private fun head(x: Float, dy: Float, z: Float) = frame.world(x, dy, z)
    private fun shieldAt(id: Int, x: Float, z: Float, yawDeg: Float, t: Int): Shield {
        val p = frame.floorPoint(x, z)
        return Shield(id, p.x, p.z, frame.worldFace(yawDeg), t)
    }
    private fun read(h: Vec3, shields: List<Shield> = emptyList(), iso: Isotope = cs) = Reading.at(iso, frame.origin, h, shields, floorY)

    @Test fun rates() {
        assertEquals(93.1f, Dose.rate(cs, 1f), 0.1f)
        assertEquals(23.4f, Dose.rate(cs, 2f), 0.1f)
        assertEquals(372.1f, Dose.rate(cs, 0.5f), 0.1f)
        assertEquals(1033.4f, Dose.rate(cs, 0.2f), 0.1f)
    }

    @Test fun shielding() {
        assertEquals(49.1f, Dose.rate(cs, 1f, Dose.attenuation(cs, listOf(6))), 0.1f)
        assertEquals(26.0f, Dose.rate(cs, 1f, Dose.attenuation(cs, listOf(12))), 0.1f)
        assertEquals(7.3f, Dose.rate(cs, 1f, Dose.attenuation(cs, listOf(24))), 0.1f)
        assertEquals(7.3f, Dose.rate(cs, 1f, Dose.attenuation(cs, listOf(12, 12))), 0.1f)
        assertEquals(87.6f, Dose.rate(co, 1f, Dose.attenuation(co, listOf(24))), 0.1f)
        assertEquals(43.85f, Dose.rate(co, 1f, Dose.attenuation(co, listOf(24, 12))), 0.1f)
        assertEquals(22.0f, Dose.rate(co, 1f, Dose.attenuation(co, listOf(24, 24))), 0.1f)
        assertEquals(2.1f, Dose.rate(tc, 1f, Dose.attenuation(tc, listOf(1))), 0.1f)
        assertEquals(0.1f, Dose.rate(tc, 1f, Dose.attenuation(tc, listOf(3))), 0.1f)
        assertEquals("47%", Dose.blockedText(Dose.attenuation(cs, listOf(6))))
        assertEquals("72%", Dose.blockedText(Dose.attenuation(cs, listOf(12))))
        assertEquals("92%", Dose.blockedText(Dose.attenuation(cs, listOf(24))))
        assertEquals("75%", Dose.blockedText(Dose.attenuation(co, listOf(24))))
        assertEquals("88%", Dose.blockedText(Dose.attenuation(co, listOf(24, 12))))
        assertEquals("94%", Dose.blockedText(Dose.attenuation(co, listOf(24, 24))))
        assertEquals("29%", Dose.blockedText(Dose.attenuation(co, listOf(6))))
        assertEquals("90%", Dose.blockedText(Dose.attenuation(tc, listOf(1))))
        assertEquals("99.9%", Dose.blockedText(Dose.attenuation(tc, listOf(3))))
        assertEquals("> 99.9%", Dose.blockedText(Dose.attenuation(tc, listOf(6))))
        assertEquals("10%", Dose.blockedText(Dose.attenuation(cs, listOf(1))))
        assertEquals("27%", Dose.blockedText(Dose.attenuation(cs, listOf(3))))
    }

    @Test fun standardScene() {
        assertEquals(7.3f, read(head(3.5f, 0.85f, 0f)).h, 0.1f)
        assertEquals(9.7f, read(head(3.0f, 0.85f, 0f)).h, 0.1f)
        assertEquals(19.8f, read(head(2.0f, 0.85f, 0f)).h, 0.1f)
        assertEquals(54.1f, read(head(1.0f, 0.85f, 0f)).h, 0.1f)
        assertEquals(95.7f, read(head(0.5f, 0.85f, 0f)).h, 0.1f)
        assertEquals(609.9f, read(head(0.3f, 0.25f, 0f)).h, 0.1f)
        val close = read(head(0.2f, 0.15f, 0f))
        assertEquals(1033.4f, close.h, 0.1f)
        assertTrue(close.tooClose)
        assertEquals(Zone.EXTREME, close.zone)
        assertEquals(Zone.LOW, read(head(3.5f, 0.85f, 0f)).zone)
        assertEquals(Zone.MID, read(head(1.0f, 0.85f, 0f)).zone)
        assertEquals(Zone.HIGH, read(head(0.3f, 0.25f, 0f)).zone)
        // ring plates at the head height 1.71
        for ((r, want) in listOf(1f to 54.1f, 2f to 19.8f, 3f to 9.7f)) assertEquals(want, Rings.rateAt(cs, frame.origin, frame.floorPoint(r, 0f), 1.71f, emptyList(), floorY).h, 0.1f)
    }

    @Test fun standardSceneShielded() {
        val s1 = shieldAt(1, 0.5f, 0f, 180f, 6)
        val r = read(head(1f, 0.85f, 0f), listOf(s1))
        assertEquals(28.6f, r.h, 0.1f)
        assertEquals("47%", Dose.blockedText(r.attenuation))
        val two = listOf(shieldAt(1, 0.5f, 0f, 180f, 12), shieldAt(2, 0.7f, 0f, 180f, 12))
        val r2 = read(head(1f, 0.85f, 0f), two)
        assertEquals(4.3f, r2.h, 0.1f)
        assertEquals("92%", Dose.blockedText(r2.attenuation))
        val off = read(head(0.6f, 0.85f, 0.8f), listOf(s1))
        assertEquals(54.1f, off.h, 0.1f)
        assertFalse(off.shielded)
    }

    @Test fun isotopes() {
        val h = head(1f, 0.85f, 0f)
        assertEquals(203.3f, read(h, iso = co).h, 0.1f)
        assertEquals("203", Dose.formatRate(read(h, iso = co).h).first)
        assertEquals(143.8f, read(h, listOf(shieldAt(1, 0.5f, 0f, 180f, 6)), co).h, 0.1f)
        assertEquals("12", Dose.formatRate(read(h, iso = tc).h).first)
        assertEquals(74f, Rings.rateAt(co, frame.origin, frame.floorPoint(2f, 0f), 1.71f, emptyList(), floorY).h, 0.6f)
        assertEquals(36f, Rings.rateAt(co, frame.origin, frame.floorPoint(3f, 0f), 1.71f, emptyList(), floorY).h, 0.6f)
        assertEquals(4.3f, Rings.rateAt(tc, frame.origin, frame.floorPoint(2f, 0f), 1.71f, emptyList(), floorY).h, 0.1f)
        assertEquals(2.2f, Rings.rateAt(tc, frame.origin, frame.floorPoint(3f, 0f), 1.71f, emptyList(), floorY).h, 0.1f)
    }

    @Test fun formats() {
        assertEquals("9.3" to "µSv/h", Dose.formatRate(9.3f))
        assertEquals("93" to "µSv/h", Dose.formatRate(93.1f))
        assertEquals("1.03" to "mSv/h", Dose.formatRate(1033.4f))
        assertEquals("2.4", Dose.sig2(2.43f))
        assertEquals("0.043", Dose.sig2(0.0432f))
        assertEquals("12", Dose.sig2(12.3f))
    }

    @Test fun shadowStanding() {
        val q = Shadow.quad(P2(0f, 0f), 0.86f, Shield(1, 0.5f, 0f, 180f, 6), 1.71f)
        assertNotNull(q)
        q!!
        assertEquals(0.5f, q.nearA.x, 1e-3f)
        assertEquals(0.3f, kotlin.math.abs(q.nearA.z), 1e-3f)
        assertEquals(4.0f, q.farA.x, 1e-3f)
        assertEquals(2.4f, kotlin.math.abs(q.farA.z), 1e-3f)
    }

    @Test fun shadowRatios() {
        assertEquals(2.237f, Shadow.ratios(0.86f, 0.05f, 1.05f, 1.71f)!!.first * 0.5f, 1e-3f)
        val crouch = Shadow.ratios(0.86f, 0.05f, 1.85f, 0.6f)!!
        assertEquals(0.5f, crouch.first * 0.5f, 1e-3f)
        assertTrue(crouch.second.isInfinite())
        val high = Shadow.ratios(2.0f, 0.05f, 1.85f, 1.71f)!!
        assertEquals(0.5f, high.first * 0.5f, 1e-3f)
        assertEquals(0.967f, high.second * 0.5f, 1e-3f)
        assertNull(Shadow.ratios(2.0f, 0.05f, 1.85f, 1.9f))
    }

    @Test fun shadowSlantedMatchesFrontalAndHeadTest() {
        // frontal: the quad equals the symmetric trapezoid (regression)
        val frontal = Shadow.quad(P2(0f, 0f), 0.86f, Shield(1, 0.5f, 0f, 180f, 6), 1.71f)!!
        assertEquals(frontal.nearA.x, frontal.nearB.x, 1e-4f)
        // slanted panel (1.2, 0.6) yaw 30: every point inside the shadow reads shielded, points outside not
        val s = shieldAt(1, 1.2f, 0.6f, 30f, 6)
        val q = Shadow.quad(P2(frame.origin.x, frame.origin.z), 0.86f, s, 1.71f)!!
        var inside = 0
        for (i in 0..40) for (j in 0..40) {
            val p = P2(-1f + i * 0.125f, -2f + j * 0.125f)
            // the drawn shadow stops at 4 m (design model.shadow); the head test has no such cut
            if ((p - P2(frame.origin.x, frame.origin.z)).length > 3.5f) continue
            val shielded = read(Vec3(p.x, 1.71f, p.z), listOf(s)).shielded
            val inQ = q.contains(p)
            if (inQ) inside++
            // allow the one-cell band around the edges
            if (inQ != shielded) {
                val near = q.points.indices.any { k -> distToSegment(p, q.points[k], q.points[(k + 1) % 4]) < 0.07f }
                assertTrue("mismatch at $p inQ=$inQ shielded=$shielded", near)
            }
        }
        assertTrue(inside > 10)
    }

    @Test fun ringSegmentInShadow() {
        val s1 = shieldAt(1, 0.5f, 0f, 180f, 6)
        val segs = Rings.segments(cs, frame.origin, 1.71f, listOf(s1), floorY)
        val front = segs.first { it.ring == 0 && it.index == 0 }
        assertTrue(front.shielded)
        val side = segs.first { it.ring == 0 && it.index == 18 }
        assertFalse(side.shielded)
    }

    @Test fun drillCriteria() {
        assertTrue(Drills.shieldPasses(Dose.attenuation(co, listOf(24, 12))))
        assertTrue(Drills.shieldPasses(Dose.attenuation(cs, listOf(24))))
        assertTrue(Drills.shieldPasses(Dose.attenuation(tc, listOf(1))))
        assertFalse(Drills.shieldPasses(Dose.attenuation(cs, listOf(6, 12))))
        assertFalse(Drills.shieldPasses(1f))
        val (d0, h0, target) = Drills.distanceStart(cs, 0.2f)
        assertEquals(0.5f, d0, 1e-4f)
        assertEquals(372.1f, h0, 0.1f)
        assertEquals(93.1f, target, 0.1f)
        assertEquals(1.0f, Drills.toGo(0.5f, 0f, 0f), 1e-3f)
        assertEquals(13.6f, Drills.distanceStart(cs, 1.3124f).third, 0.1f)
        assertEquals(7.0f, Drills.shieldTarget(54.1f), 0.1f)
        assertTrue(Drills.gazesAt(Vec3(0.2f, 0.55f, 0f), Vec3(-0.4226f, -0.9063f, 0f), Vec3(0f, 0.01f, 0f)))
    }

    private fun distToSegment(p: P2, a: P2, b: P2): Float {
        val ab = b - a
        val t = ((p - a).dot(ab) / ab.dot(ab)).coerceIn(0f, 1f)
        return (p - (a + ab * t)).length
    }
}
