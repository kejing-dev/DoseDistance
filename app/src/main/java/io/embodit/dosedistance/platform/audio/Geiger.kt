package io.embodit.dosedistance.platform.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.embodit.dosedistance.platform.DdLog
import java.util.concurrent.atomic.AtomicLong

/**
 * Geiger clicks (design `model.geiger`, N5): one streaming AudioTrack fed by a small thread with
 * [GeigerSynth]. Clicks come as a Poisson-like stream at [rate] per second; [tone] adds a short soft pip
 * (v0.1.1: no continuous beep). Muted from the dosimeter's speaker key (`Settings.geiger`).
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
        val synth = GeigerSynth(sr)
        runCatching { track.play() }
        try {
            while (running) {
                val before = synth.clicks
                synth.fill(buf, rate, tone)
                clicks.addAndGet(synth.clicks - before)
                if (track.write(buf, 0, buf.size) < 0) break
            }
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 22050
        const val CHUNK = 441
    }
}
