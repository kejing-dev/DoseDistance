package io.embodit.dosedistance.platform

import io.embodit.dosedistance.domain.Vec3
import io.embodit.dosedistance.platform.audio.Geiger
import io.embodit.dosedistance.platform.sense.PlaneTracker
import io.embodit.dosedistance.platform.tracking.ControllerStick
import io.embodit.dosedistance.platform.tracking.EyeTracker
import io.embodit.dosedistance.platform.tracking.HeadTracker

/**
 * Process-wide platform services. The scene starts and stops them (every start has a stop:
 * `DoseScene.attach` / `detach`, `pause` / `resume`); the ViewModel only sees lambdas.
 */
object DdGraph {
    val planes = PlaneTracker()
    val head = HeadTracker()
    val stick = ControllerStick()
    val eyes = EyeTracker()
    val geiger = Geiger()

    /**
     * Debug `head`: a head position relative to the source core in the source frame (design `qaHooks`:
     * x towards the user at placement, y up, z to its right) and an optional pitch in degrees; null = the real head.
     */
    @Volatile var debugHead: Vec3? = null
    @Volatile var debugPitchDeg: Float? = null

    /** Debug: where the aiming ghost lands, floor point in the source frame (S08). */
    @Volatile var debugAim: Pair<Float, Float>? = null
}
