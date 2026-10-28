package io.embodit.dosedistance.platform.tracking

import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.eye.EyeTrackingProvider
import io.embodit.dosedistance.domain.Aim
import io.embodit.dosedistance.domain.PointerRay
import io.embodit.dosedistance.domain.Quat4
import io.embodit.dosedistance.domain.Vec3
import io.embodit.dosedistance.platform.DdLog

/**
 * Eye gaze (`EyeTrackingProvider`, SDK doc "Eye tracking": Full Space only, runtime permission
 * `com.picovr.permission.EYE_TRACKING`), ported from Rehearsal Hall. Read once per frame from the ECS system. The gaze
 * pose is in global space with the gaze along local -Z; the stage root's local space equals that space.
 *
 * [ray] is null whenever the eyes cannot be used (no permission, not supported, no fresh sample): aiming then
 * uses the controller or, last, the head (v0.1.2, taste profile D1).
 */
class EyeTracker {
    private var provider: EyeTrackingProvider? = null
    private var lastStamp = 0L
    private var lastFreshMs = 0L
    private var lastSupport: DataProvider.SupportState? = null

    /** Debug: pretend the eyes are unavailable. */
    @Volatile var forceOff = false

    fun start() {
        if (provider != null) return
        val p = EyeTrackingProvider()
        val result = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        DdLog.i("TRACK eye start=${result.getOrNull() ?: result.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
        lastStamp = 0L
    }

    /** Latest gaze ray, or null when the eyes are not usable now. */
    fun ray(nowMs: Long): PointerRay? {
        val p = provider ?: return null
        val support = runCatching { p.supportState }.getOrNull()
        if (support != lastSupport) {
            lastSupport = support
            DdLog.i("TRACK eye support=$support")
        }
        if (forceOff || support != DataProvider.SupportState.SUPPORTED) return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        if (data.timestamp == 0L) return null
        if (data.timestamp != lastStamp) {
            lastStamp = data.timestamp
            lastFreshMs = nowMs
        }
        if (nowMs - lastFreshMs > STALE_MS) return null
        val pos = data.eyePose.position
        val rot = data.eyePose.rotation
        val ray = Aim.rayOf(Vec3(pos.x, pos.y, pos.z), Quat4(rot.x, rot.y, rot.z, rot.w)) ?: return null
        return if (ray.origin.length < 1e-4f) null else ray
    }

    fun status(): String = "support=$lastSupport off=$forceOff"

    private companion object {
        const val STALE_MS = 1_000L
    }
}
