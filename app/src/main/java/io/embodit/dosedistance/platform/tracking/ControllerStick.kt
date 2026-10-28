package io.embodit.dosedistance.platform.tracking

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerPose
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.dosedistance.domain.Aim
import io.embodit.dosedistance.domain.PointerRay
import io.embodit.dosedistance.domain.Quat4
import io.embodit.dosedistance.domain.Vec3
import io.embodit.dosedistance.platform.DdLog
import kotlin.math.abs

/**
 * The controllers (`ControllerTrackingProvider`, Full Space only; SDK doc "Controller tracking"): the thumbstick X
 * turns the selected piece (v0.1.1), and since v0.1.2 the aiming controller's ray (pose along its -Z, as in
 * Spatial Ruler / Access Check) places the source and the shields, its trigger confirms (taste profile D1: aiming
 * never follows the head). The hand whose trigger was touched last aims (the right one at first).
 *
 * The action listener runs on the data source thread at the device rate: it only stores values; the ECS system
 * reads them once per frame. A controller lying still keeps reporting a pose (Chime Bells: its ray then points
 * anywhere), so it only counts as in use for [Aim.CONTROLLER_ACTIVE_MS] after a button / trigger / stick change or a
 * real move. Hand gestures are untouched (another input path).
 */
class ControllerStick {
    private var provider: ControllerTrackingProvider? = null

    @Volatile private var x = 0f
    @Volatile private var atMs = 0L
    @Volatile var actions = 0L
        private set

    // trigger state per hand and presses (rising edges), counted on the data thread, consumed per frame
    @Volatile private var leftDown = false
    @Volatile private var rightDown = false
    @Volatile private var leftPresses = 0
    @Volatile private var rightPresses = 0
    @Volatile private var useRight = true
    @Volatile private var inputMs = 0L
    private var pressesSeen = 0
    private var lastRay: PointerRay? = null
    private var movedMs = 0L

    /** Debug: a stick value that wins over the controllers until [debugUntilMs] (emulator QA). */
    @Volatile private var debugX = 0f
    @Volatile private var debugUntilMs = 0L

    /** Debug `ray`: a controller ray (Stage metres) in use until [debugRayUntilMs]. */
    @Volatile var debugRay: PointerRay? = null
    @Volatile private var debugRayUntilMs = 0L

    private val listener =
        ControllerTrackingProvider.ControllerActionListener { a ->
            val l = a.left.thumbstickValue.x
            val r = a.right.thumbstickValue.x
            x = if (abs(r) >= abs(l)) r else l
            val lt = a.left.triggerPressed
            val rt = a.right.triggerPressed
            if (lt && !leftDown) leftPresses++
            if (rt && !rightDown) rightPresses++
            if (lt || a.left.triggerValue > TOUCH_AT) useRight = false
            if (rt || a.right.triggerValue > TOUCH_AT) useRight = true
            val busy = lt || rt || a.left.triggerValue > TOUCH_AT || a.right.triggerValue > TOUCH_AT || abs(l) > STICK_AT || abs(r) > STICK_AT ||
                a.left.gripPressed || a.right.gripPressed || a.left.xButtonPressed || a.left.yButtonPressed || a.right.aButtonPressed || a.right.bButtonPressed
            leftDown = lt
            rightDown = rt
            val now = SystemClock.elapsedRealtime()
            if (busy) inputMs = now
            atMs = now
            actions++
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }.onFailure { DdLog.w("STICK listener: $it") }
        val result = runCatching { p.start() }
        DdLog.i("STICK start=${result.getOrNull() ?: result.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
        x = 0f
        actions = 0L
        lastRay = null
    }

    private fun supported(): Boolean = runCatching { provider?.supportState }.getOrNull() == DataProvider.SupportState.SUPPORTED

    /** Thumbstick X in [-1, 1] (right +); 0 without a controller or when the last report is stale. */
    fun x(nowMs: Long): Float {
        if (nowMs < debugUntilMs) return debugX
        if (provider == null || !supported()) return 0f
        if (nowMs - atMs > STALE_MS) return 0f
        return x.coerceIn(-1f, 1f)
    }

    /** The aiming controller's ray (Stage space = global space under the calibrated stage root), or null. Once per frame. */
    fun ray(nowMs: Long): PointerRay? {
        if (nowMs < debugRayUntilMs) return debugRay
        val p = provider ?: return null
        if (!supported()) return null
        val d = runCatching { p.latestData }.getOrNull() ?: return null
        val pose: ControllerPose = (if (useRight) d.right ?: d.left else d.left ?: d.right) ?: return null
        val ray = Aim.rayOf(Vec3(pose.position.x, pose.position.y, pose.position.z), Quat4(pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w)) ?: return null
        if (ray.origin.length < 1e-4f) return null
        val last = lastRay
        if (last == null || last.origin.distanceTo(ray.origin) > MOVE_M || last.dir.dot(ray.dir) < MOVE_COS) {
            if (last != null) movedMs = nowMs
            lastRay = ray
        }
        return ray
    }

    /** In use: a button / trigger / stick change or a real move within [Aim.CONTROLLER_ACTIVE_MS]. */
    fun active(nowMs: Long): Boolean = nowMs < debugRayUntilMs || nowMs - maxOf(inputMs, movedMs) < Aim.CONTROLLER_ACTIVE_MS && maxOf(inputMs, movedMs) > 0L

    /** A trigger press since the last call (either hand). */
    fun takePress(): Boolean {
        val n = leftPresses + rightPresses
        val pressed = n != pressesSeen
        pressesSeen = n
        return pressed
    }

    /** Debug `stick`: holds [value] for [ms]. */
    fun debugHold(value: Float, ms: Long) {
        debugX = value.coerceIn(-1f, 1f)
        debugUntilMs = SystemClock.elapsedRealtime() + ms
    }

    /** Debug `ray`: a controller ray for [ms] (null clears). */
    fun debugAim(ray: PointerRay?, ms: Long) {
        debugRay = ray
        debugRayUntilMs = if (ray == null) 0L else SystemClock.elapsedRealtime() + ms
    }

    /** Debug `trigger`: one trigger press. */
    fun debugPress() {
        rightPresses++
        inputMs = SystemClock.elapsedRealtime()
    }

    fun status(): String = "support=${runCatching { provider?.supportState }.getOrNull()} actions=$actions x=${"%.2f".format(x)} hand=${if (useRight) "R" else "L"}"

    private companion object {
        const val STALE_MS = 500L
        const val TOUCH_AT = 0.05f
        const val STICK_AT = 0.2f
        const val MOVE_M = 0.02f
        const val MOVE_COS = 0.9994f // 2°
    }
}
