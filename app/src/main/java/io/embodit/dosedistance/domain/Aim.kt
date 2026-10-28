package io.embodit.dosedistance.domain

/**
 * Where the user points (v0.1.2, user: "应该是用手柄射线 或者 eye tracking +pinch 来放置铅屏，不是用头显";
 * taste profile D1): aiming, placing and dragging follow ① the controller ray (trigger confirms) or ② the eye gaze
 * (pinch confirms, through the system tap). The head direction is only the last fallback and the panel says so.
 */
enum class AimSource { CONTROLLER, EYES, HEAD }

/** A pointing ray in Stage metres; [dir] is a unit vector. */
data class PointerRay(val origin: Vec3, val dir: Vec3) {
    fun at(t: Float): Vec3 = origin + dir * t
}

object Aim {
    /** A controller counts as in use for this long after its last trigger / stick / button change or move. */
    const val CONTROLLER_ACTIVE_MS = 8_000L

    /**
     * The ray to aim with: the controller while it is in use (or when the eyes are not available: a controller in
     * hand beats the head), else the eyes, else the head. Null without any pose.
     */
    fun choose(controller: PointerRay?, controllerActive: Boolean, eyes: PointerRay?, head: HeadPose?): Pair<PointerRay, AimSource>? =
        when {
            controller != null && (controllerActive || eyes == null) -> controller to AimSource.CONTROLLER
            eyes != null -> eyes to AimSource.EYES
            head != null -> PointerRay(head.position, head.forward.normalized()) to AimSource.HEAD
            else -> null
        }

    /** Where [ray] meets the horizontal plane at height [y], within [maxM]; null when it points away or is level. */
    fun onLevel(ray: PointerRay, y: Float, maxM: Float = 8f): Vec3? {
        if (kotlin.math.abs(ray.dir.y) < 1e-4f) return null
        val t = (y - ray.origin.y) / ray.dir.y
        if (t <= 0f || t > maxM) return null
        return ray.at(t)
    }

    /** A pose's ray along its local -Z (controller and eye-gaze poses, SDK convention; Spatial Ruler / Access Check). */
    fun rayOf(position: Vec3, rotation: Quat4): PointerRay? {
        val f = rotation.rotate(Vec3.AHEAD)
        if (!position.isFinite() || !f.isFinite() || f.length < 0.5f) return null
        return PointerRay(position, f.normalized())
    }
}
