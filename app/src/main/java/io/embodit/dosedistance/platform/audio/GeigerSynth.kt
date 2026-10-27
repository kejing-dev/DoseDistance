package io.embodit.dosedistance.platform.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Geiger sound, sample by sample (plain Kotlin, so the unit tests render it offline to a WAV and check the
 * levels). v0.1.1 (user: "音效很吵，需要能mute"): clicks are short soft ticks (a 2.2 kHz ping with a little
 * low-passed noise, peak [CLICK_AMP]); above the tone threshold there is no continuous beep any more, only a
 * short soft 880 Hz pip ([PIP_MS] every [PIP_EVERY_MS]) on top of the clicks.
 */
class GeigerSynth(private val sampleRate: Int = 22050, private val random: Random = Random.Default) {
    private val click = clickShape()
    private var untilNext = 0.0
    private var clickPos = -1
    private var pipPhase = 0.0
    private var pipClock = 0L

    /** Clicks started so far. */
    var clicks = 0L
        private set

    /** Fills [buf] at [rate] clicks per second; [alarm] adds the pip. */
    fun fill(buf: ShortArray, rate: Float, alarm: Boolean) {
        val r = rate.toDouble()
        val pipLen = sampleRate * PIP_MS / 1000
        val pipEvery = sampleRate * PIP_EVERY_MS / 1000
        for (i in buf.indices) {
            var v = 0.0
            if (r > 0.0) {
                untilNext -= 1.0 / sampleRate
                if (untilNext <= 0.0) {
                    clickPos = 0
                    clicks++
                    // exponential gaps around the mean, the tail clamped
                    val mean = 1.0 / r
                    val gap = -ln(1.0 - random.nextDouble().coerceIn(0.0, 0.999)) * mean
                    untilNext = gap.coerceIn(mean * 0.2, mean * 3.0)
                }
            }
            if (clickPos >= 0) {
                v += click[clickPos]
                clickPos++
                if (clickPos >= click.size) clickPos = -1
            }
            if (alarm) {
                val k = pipClock % pipEvery
                if (k < pipLen) {
                    pipPhase += 2 * PI * PIP_HZ / sampleRate
                    if (pipPhase > 2 * PI) pipPhase -= 2 * PI
                    // raised-cosine envelope: no edge clicks
                    val env = 0.5 - 0.5 * cos(2 * PI * k / pipLen)
                    v += sin(pipPhase) * PIP_AMP * env
                }
                pipClock++
            } else pipClock = 0L
            buf[i] = (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun clickShape(): DoubleArray {
        val n = (sampleRate * 0.003).toInt()
        var lp = 0.0
        return DoubleArray(n) { i ->
            val t = i.toDouble() / sampleRate
            lp += ((random.nextDouble() * 2 - 1) - lp) * 0.35
            (sin(2 * PI * 2200.0 * t) * 0.7 + lp * 0.6) * exp(-t * 1600.0) * CLICK_AMP
        }
    }

    companion object {
        const val CLICK_AMP = 0.22
        const val PIP_HZ = 880.0
        const val PIP_AMP = 0.08
        const val PIP_MS = 120
        const val PIP_EVERY_MS = 1200
    }
}
