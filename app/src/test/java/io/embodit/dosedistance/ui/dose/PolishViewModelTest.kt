package io.embodit.dosedistance.ui.dose

import io.embodit.dosedistance.data.SettingsStore
import io.embodit.dosedistance.domain.Clearance
import io.embodit.dosedistance.domain.P2
import io.embodit.dosedistance.domain.PlaneSurface
import io.embodit.dosedistance.domain.Settings
import io.embodit.dosedistance.domain.Vec3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.1.1 behaviour of the ViewModel: mute remembered, shields clear of the user, the shield list's actions. */
class PolishViewModelTest {
    private class MemStore(var saved: Settings = Settings()) : SettingsStore {
        override fun load() = saved

        override fun save(settings: Settings) {
            saved = settings
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = MemStore(Settings(coachSeen = true))
    private val vm = DoseViewModel(store, { null }, scope)
    private val floor = PlaneSurface("f", "FLOOR", listOf(Vec3(-5f, 0f, -5f), Vec3(5f, 0f, -5f), Vec3(5f, 0f, 5f), Vec3(-5f, 0f, 5f)))

    @After fun tearDown() = scope.cancel()

    /** Ready room, head at (0, 1.6, 0) looking -z, source on the floor 1.0 m ahead. */
    private fun room(sourceAheadM: Float = 1.0f) {
        vm.onEvent(DoseEvent.PlanesChanged(0f, emptyList()))
        vm.onEvent(DoseEvent.SceneReady)
        vm.debugSet { it.copy(head = Vec3(0f, 1.6f, 0f), heading = P2(0f, -1f)) }
        vm.placeSourceAt(floor, Vec3(0f, 0f, -sourceAheadM))
    }

    private fun headDist(id: Int): Float {
        val s = vm.state.value.shields.first { it.id == id }
        return P2(s.x, s.z).length
    }

    @Test fun muteIsRemembered() {
        room()
        assertTrue(vm.state.value.settings.geiger)
        vm.onEvent(DoseEvent.GeigerSet(false))
        assertFalse(vm.state.value.settings.geiger)
        assertFalse(store.saved.geiger)
        // a new ViewModel (app restart) starts muted
        assertFalse(DoseViewModel(store, { null }, scope).state.value.settings.geiger)
        vm.onEvent(DoseEvent.GeigerSet(true))
        assertTrue(store.saved.geiger)
    }

    @Test fun shieldTappedAtTheFeetLandsOutsideTheClearance() {
        room()
        val sh = vm.placeShieldAt(P2(0.05f, -0.1f))
        assertNotNull(sh)
        assertTrue("dist ${headDist(1)}", headDist(1) >= Clearance.SHIELD_M - 1e-3f)
    }

    @Test fun snapNeverPutsTheShieldOnTheUser() {
        // v0.1.0: source 1.0 m away -> snap 0.5 m from the face. Now 0.8 m clear (or no snap at all).
        room(1.2f)
        val snap = shieldSnapPoint(vm.state.value)
        assertNotNull(snap)
        assertTrue(snap!!.length >= Clearance.SHIELD_M - 1e-3f)
        vm.placeShieldAt(snap)
        assertTrue(headDist(1) >= Clearance.SHIELD_M - 1e-3f)
    }

    @Test fun snapCanBeTurnedOff() {
        room(2f)
        assertNotNull(shieldSnapPoint(vm.state.value))
        vm.onEvent(DoseEvent.SnapSet(false))
        assertEquals(null, shieldSnapPoint(vm.state.value))
        assertFalse(store.saved.snap)
    }

    @Test fun dragReleaseInsideTheClearanceIsPushedOut() {
        room(2f)
        vm.placeShieldAt(P2(1.5f, -1.5f))
        val s = vm.state.value.shields.first()
        vm.onEvent(DoseEvent.ShieldMoved(s.copy(x = 0.1f, z = 0.1f), snapped = false))
        assertTrue(headDist(1) >= Clearance.SHIELD_M - 1e-3f)
    }

    @Test fun shieldListBringsToFrontHidesAndDeletes() {
        room(2f)
        vm.placeShieldAt(P2(1.5f, -1.5f))
        vm.placeShieldAt(P2(-1.5f, -1.5f))
        // the user turns to face +x and brings shield 2 in front
        vm.debugSet { it.copy(heading = P2(1f, 0f)) }
        vm.onEvent(DoseEvent.ShieldToFront(2))
        val s2 = vm.state.value.shields.first { it.id == 2 }
        assertEquals(Clearance.FRONT_M, s2.x, 1e-4f)
        assertEquals(0f, s2.z, 1e-4f)
        assertEquals(2, vm.state.value.selectedShield)
        // facing the user (normal points back to the head)
        assertTrue(s2.normal.x < -0.99f)
        // hide: out of the room and the reading, still listed
        vm.onEvent(DoseEvent.ShieldHidden(1, true))
        assertEquals(2, vm.state.value.shields.size)
        assertEquals(listOf(2), vm.state.value.visibleShields.map { it.id })
        vm.onEvent(DoseEvent.ShieldHidden(1, false))
        assertEquals(2, vm.state.value.visibleShields.size)
        // delete from the list, not only the selected one
        vm.onEvent(DoseEvent.DeleteShield(1))
        assertEquals(listOf(2), vm.state.value.shields.map { it.id })
        assertEquals(2, vm.state.value.selectedShield)
    }

    @Test fun floorSourceKeepsSmallClearance() {
        room()
        vm.onEvent(DoseEvent.RemoveSourceClicked)
        val src = vm.placeSourceAt(floor, Vec3(0.1f, 0f, -0.1f))
        assertTrue(P2(src.x, src.z).length >= Clearance.SMALL_M - 1e-3f)
    }
}
