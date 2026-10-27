package io.embodit.dosedistance.domain

import io.embodit.dosedistance.platform.audio.GeigerSynth
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.1.1 (user feedback 2026-10-26): clearance, snap, floor map, fade distance, the quieter Geiger. */
class PolishTest {
    private val cs = Isotope.CS137

    @Test fun pushOutKeepsClearance() {
        val head = P2(0f, 0f)
        // a point 0.3 m ahead is pushed out to 0.8 m along the same direction
        val p = Clearance.pushOut(P2(0f, -0.3f), head, Clearance.SHIELD_M, P2(1f, 0f))
        assertEquals(0.8f, (p - head).length, 1e-4f)
        assertEquals(0f, p.x, 1e-4f)
        assertTrue(p.z < 0f)
        // right under the head: the fallback direction
        val q = Clearance.pushOut(P2(0f, 0f), head, Clearance.SHIELD_M, P2(1f, 0f))
        assertEquals(0.8f, q.x, 1e-4f)
        // outside: untouched
        val r = P2(1.5f, 0.2f)
        assertEquals(r, Clearance.pushOut(r, head, Clearance.SHIELD_M, P2(1f, 0f)))
        // no head known: untouched
        assertEquals(P2(0.1f, 0f), Clearance.pushOut(P2(0.1f, 0f), null, Clearance.SHIELD_M, P2(1f, 0f)))
    }

    @Test fun snapPointNeverOnTheUser() {
        val src = P2(0f, 0f)
        // far user: 0.5 m from the source as before
        val far = Snap.point(src, P2(2f, 0f), null, P2(1f, 0f))!!
        assertEquals(0.5f, far.x, 1e-4f)
        // user 1.0 m away (the v0.1.0 case: 0.5 m from the face): slid to 0.8 m from the head
        val near = Snap.point(src, P2(1.1f, 0f), null, P2(1f, 0f))!!
        assertEquals(0.3f, near.x, 1e-4f)
        assertTrue((P2(1.1f, 0f) - near).length >= Clearance.SHIELD_M - 1e-3f)
        // user right at the source: no snap point at all
        assertNull(Snap.point(src, P2(0.9f, 0f), null, P2(1f, 0f)))
        // drill 3: midpoint of source and spot, clear of a user standing in the spot
        val spot = P2(2.0f, 0f)
        val d3 = Snap.point(src, spot, spot, P2(1f, 0f))!!
        assertEquals(1.0f, d3.x, 1e-4f)
        // drill 3 at the nearest spot (1.3 m): 0.8 m from the spot
        val d3n = Snap.point(src, P2(Drills.SPOT_MIN, 0f), P2(Drills.SPOT_MIN, 0f), P2(1f, 0f))
        assertNotNull(d3n)
        assertEquals(Drills.SPOT_MIN - Clearance.SHIELD_M, d3n!!.x, 1e-4f)
    }

    @Test fun inFrontIsAheadOfTheHead() {
        val p = Clearance.inFront(P2(1f, 1f), P2(0f, -2f))
        assertEquals(1f, p.x, 1e-4f)
        assertEquals(1f - Clearance.FRONT_M, p.z, 1e-4f)
    }

    @Test fun planDistanceForFade() {
        val s = Shield(1, 0f, 0f, 0f, 6) // faces +x, panel along z from -0.3 to 0.3
        assertEquals(0.2f, s.planDistance(P2(0.2f, 0f)), 1e-4f)
        assertEquals(0.2f, s.planDistance(P2(-0.2f, 0.25f)), 1e-4f)
        assertEquals(0.5f, s.planDistance(P2(0f, 0.8f)), 1e-4f)
        assertTrue(s.planDistance(P2(0.1f, 0f)) < Clearance.FADE_IN_M)
        assertTrue(s.planDistance(P2(1.0f, 0f)) > Clearance.FADE_OUT_M)
    }

    @Test fun heatFallsWithDistance() {
        val core = Vec3(0f, 0.86f, 0f)
        var last = Float.MAX_VALUE
        var lastLevel = 2f
        for (i in 0 until Heat.RINGS) {
            val h = Heat.rateAt(cs, core, i, 0, 1.6f, emptyList(), 0f)
            assertTrue("ring $i: $h >= $last", h < last)
            val lv = Heat.level(h)
            assertTrue(lv <= lastLevel)
            last = h
            lastLevel = lv
        }
        assertEquals(0f, Heat.level(0.1f), 0f)
        assertEquals(1f, Heat.level(5000f), 0f)
        assertEquals(0, Heat.bin(0.2f))
        assertEquals(Heat.BINS - 1, Heat.bin(2000f))
    }

    @Test fun heatIsLowerBehindAShield() {
        val core = Vec3(0f, 0.86f, 0f)
        // shield 0.5 m out along +x facing the source; cells along +x behind it vs. along -x unshielded
        val shield = Shield(1, 0.5f, 0f, 180f, 12)
        val ring = (0 until Heat.RINGS).first { (Heat.radius(it) + Heat.radius(it + 1)) / 2f > 1.5f }
        val behind = Heat.rateAt(cs, core, ring, 0, 1.6f, listOf(shield), 0f)
        val open = Heat.rateAt(cs, core, ring, Heat.SECTORS / 2, 1.6f, listOf(shield), 0f)
        assertTrue("behind $behind open $open", behind < open * 0.5f)
        // hidden shields do not shield
        val hidden = Heat.rateAt(cs, core, ring, 0, 1.6f, listOf(shield.copy(hidden = true)), 0f)
        assertEquals(open, hidden, 0.01f)
        val cells = Heat.cells(cs, core, 1.6f, listOf(shield), 0f)
        assertEquals(Heat.RINGS * Heat.SECTORS, cells.size)
        assertTrue(cells[ring * Heat.SECTORS].bin < cells[ring * Heat.SECTORS + Heat.SECTORS / 2].bin)
    }

    /** Renders 4 s of clicks (10 µSv/h, 93 µSv/h) and 4 s of the alarm (40 /s + pip) to build/geiger-*.wav and checks the levels. */
    @Test fun geigerIsQuiet() {
        val sr = 22050
        for ((name, rate, alarm) in listOf(Triple("10", Dose.clicksPerSecond(10f), false), Triple("93", Dose.clicksPerSecond(93f), false), Triple("alarm", Dose.clicksPerSecond(800f), true))) {
            val synth = GeigerSynth(sr, Random(7))
            val all = ShortArray(sr * 4)
            val buf = ShortArray(441)
            var at = 0
            while (at < all.size) {
                synth.fill(buf, rate, alarm)
                System.arraycopy(buf, 0, all, at, minOf(buf.size, all.size - at))
                at += buf.size
            }
            var peak = 0.0
            var sum = 0.0
            for (v in all) {
                val x = v / 32767.0
                peak = maxOf(peak, abs(x))
                sum += x * x
            }
            val rms = sqrt(sum / all.size)
            println("GEIGER $name peak=${"%.3f".format(peak)} rms=${"%.4f".format(rms)} clicks=${synth.clicks}")
            // v0.1.0: clicks peaked 0.6, the >= 400 µSv/h tone was a continuous 1.8 kHz sine at 0.18 (rms 0.127)
            assertTrue("$name peak $peak", peak <= 0.35)
            assertTrue("$name rms $rms", rms <= 0.06)
            writeWav(File("build/geiger-$name.wav"), all, sr)
        }
    }

    private fun writeWav(f: File, pcm: ShortArray, sr: Int) {
        f.parentFile?.mkdirs()
        RandomAccessFile(f, "rw").use { w ->
            w.setLength(0)
            fun i32(v: Int) = w.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun i16(v: Int) = w.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            w.writeBytes("RIFF"); i32(36 + pcm.size * 2); w.writeBytes("WAVEfmt "); i32(16); i16(1); i16(1); i32(sr); i32(sr * 2); i16(2); i16(16)
            w.writeBytes("data"); i32(pcm.size * 2)
            val bytes = ByteArray(pcm.size * 2)
            for (k in pcm.indices) {
                bytes[2 * k] = pcm[k].toInt().toByte()
                bytes[2 * k + 1] = (pcm[k].toInt() shr 8).toByte()
            }
            w.write(bytes)
        }
    }
}
