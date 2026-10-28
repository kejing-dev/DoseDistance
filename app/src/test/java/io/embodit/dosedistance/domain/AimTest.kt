package io.embodit.dosedistance.domain

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.1.2 (user: "应该是用手柄射线 或者 eye tracking +pinch 来放置铅屏，不是用头显"): aiming input and ray × floor. */
class AimTest {
    private val head = HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY) // looks along -Z, level
    private val ctl = PointerRay(Vec3(0.2f, 1.2f, -0.2f), Vec3(0f, -1f, -1f).normalized())
    private val eyes = PointerRay(Vec3(0f, 1.6f, 0f), Vec3(1f, -1f, 0f).normalized())

    @Test fun controllerRayHitsTheFloorWhereItPoints() {
        val p = Aim.onLevel(ctl, 0f)!!
        // 45° down from 1.2 m: 1.2 m ahead of the controller
        assertEquals(0.2f, p.x, 1e-4f)
        assertEquals(-1.4f, p.z, 1e-4f)
        assertEquals(0f, p.y, 1e-6f)
        // pointing up or level never hits the floor; beyond 8 m neither
        assertNull(Aim.onLevel(PointerRay(Vec3(0f, 1f, 0f), Vec3(0f, 1f, 0f)), 0f))
        assertNull(Aim.onLevel(PointerRay(Vec3(0f, 1f, 0f), Vec3(0f, 0f, -1f)), 0f))
        assertNull(Aim.onLevel(PointerRay(Vec3(0f, 1f, 0f), Vec3(0f, -0.1f, -1f).normalized()), 0f))
    }

    @Test fun poseRayLeavesAlongMinusZ() {
        val r = Aim.rayOf(Vec3(0f, 1f, 0f), Quat4.IDENTITY)!!
        assertEquals(-1f, r.dir.z, 1e-5f)
        // a controller turned 90° to the left (about +Y) points along -X
        val q = Quat4.axisAngle(Vec3.UP, (Math.PI / 2).toFloat())
        val l = Aim.rayOf(Vec3(0f, 1f, 0f), q)!!
        assertEquals(-1f, l.dir.x, 1e-5f)
        // tilted 45° down: hits the floor 1 m ahead from 1 m up
        val down = Quat4.axisAngle(Vec3.RIGHT, (-Math.PI / 4).toFloat())
        val hit = Aim.onLevel(Aim.rayOf(Vec3(0f, 1f, 0f), down)!!, 0f)!!
        assertEquals(-1f, hit.z, 1e-4f)
    }

    @Test fun controllerInUseWins() {
        val (ray, src) = Aim.choose(ctl, controllerActive = true, eyes = eyes, head = head)!!
        assertEquals(AimSource.CONTROLLER, src)
        assertEquals(ctl, ray)
    }

    @Test fun eyesWhenTheControllerIsPutDown() {
        // a controller lying on the desk still reports a pose (its ray points anywhere): the eyes aim
        val (ray, src) = Aim.choose(ctl, controllerActive = false, eyes = eyes, head = head)!!
        assertEquals(AimSource.EYES, src)
        assertEquals(eyes, ray)
        // no controller at all: the eyes
        assertEquals(AimSource.EYES, Aim.choose(null, false, eyes, head)!!.second)
    }

    @Test fun eyesPathLandsWhereTheEyesLook() {
        val (ray, _) = Aim.choose(null, false, eyes, head)!!
        val p = Aim.onLevel(ray, 0f)!!
        assertEquals(1.6f, p.x, 1e-4f) // 45° to the right and down from 1.6 m
        assertEquals(0f, p.z, 1e-4f)
    }

    @Test fun headOnlyWhenNeitherIsThere() {
        // a controller in hand but idle, no eyes: still the controller, never the head
        assertEquals(AimSource.CONTROLLER, Aim.choose(ctl, false, null, head)!!.second)
        val (ray, src) = Aim.choose(null, false, null, head)!!
        assertEquals(AimSource.HEAD, src)
        assertEquals(-1f, ray.dir.z, 1e-5f)
        assertNull(Aim.choose(null, false, null, null))
    }

    @Test fun rayLengthIsUnit() {
        val r = Aim.rayOf(Vec3(1f, 1f, 1f), Quat4.axisAngle(Vec3(1f, 1f, 0f).normalized(), 0.7f))
        assertNotNull(r)
        val d = r!!.dir
        assertTrue(kotlin.math.abs(sqrt(d.x * d.x + d.y * d.y + d.z * d.z) - 1f) < 1e-4f)
    }
}
