package io.embodit.dosedistance.platform.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.embodit.dosedistance.platform.DdLog
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

/**
 * Geiger clicks (design `model.geiger`, N5): one streaming AudioTrack fed by a small thread. Clicks come
 * as a Poisson-like stream at [rate] per second (±20 % jitter); [tone] switches to a continuous beep.
 * Plain Android audio, no SDK. Every [start] has a [stop] (scene pause / detach).
 */
class Geiger {
    @Volatile private var rate = 0f
    @Volatile private var tone = false
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Clicks played so far (the panel's Geiger icon blinks on it; logged as the click rate). */
    val clicks = AtomicLong(0L)

    fun set(clicksPerSecond: Float, continuous: Boolean) {
        rate = clicksPerSecond
        tone = continuous
    }

    fun start() {
        if (running) return
        running = true
        thread =
            Thread({ loop() }, "geiger").apply {
                isDaemon = true
                start()
            }
        DdLog.i("GEIGER start")
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { thread?.join(300) }
        thread = null
        rate = 0f
        DdLog.i("GEIGER stop clicks=${clicks.get()}")
    }

    private fun loop() {
        val sr = SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track =
            runCatching {
                    AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(maxOf(minBuf, CHUNK * 4))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                }
                .onFailure { DdLog.w("GEIGER track failed: $it") }
                .getOrNull() ?: return
        val buf = ShortArray(CHUNK)
        val click = clickShape(sr)
        var untilNext = 0.0
        var clickPos = -1
        var phase = 0.0
        runCatching { track.play() }
        try {
            while (running) {
                val r = rate.toDouble()
                val beep = tone
                for (i in buf.indices) {
                    var v = 0.0
                    if (beep) {
                        phase += 2 * PI * TONE_HZ / sr
                        if (phase > 2 * PI) phase -= 2 * PI
                        v = sin(phase) * 0.18
                    } else if (r > 0.0) {
                        untilNext -= 1.0 / sr
                        if (untilNext <= 0.0) {
                            clickPos = 0
                            clicks.incrementAndGet()
                            // exponential gaps around the mean, kept within ±20 % of it on average by clamping the tail
                            val mean = 1.0 / r
                            val gap = -ln(1.0 - Random.nextDouble().coerceIn(0.0, 0.999)) * mean
                            untilNext = gap.coerceIn(mean * 0.2, mean * 3.0)
                        }
                    }
                    if (clickPos >= 0) {
                        v += click[clickPos]
                        clickPos++
                        if (clickPos >= click.size) clickPos = -1
                    }
                    buf[i] = (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
                }
                if (track.write(buf, 0, buf.size) < 0) break
            }
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }

    private fun clickShape(sr: Int): DoubleArray {
        val n = (sr * 0.004).toInt()
        return DoubleArray(n) { i ->
            val t = i.toDouble() / sr
            (Random.nextDouble() * 2 - 1) * exp(-t * 1400.0) * 0.6
        }
    }

    private companion object {
        const val SAMPLE_RATE = 22050
        const val CHUNK = 441
        const val TONE_HZ = 1800.0
    }
}
