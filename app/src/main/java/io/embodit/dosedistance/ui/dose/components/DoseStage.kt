package io.embodit.dosedistance.ui.dose.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.dosedistance.platform.DdLog
import io.embodit.dosedistance.ui.dose.DoseEvent
import io.embodit.dosedistance.ui.dose.DoseUiState
import io.embodit.dosedistance.ui.dose.debug.LocalProbePanel
import io.embodit.dosedistance.ui.dose.debug.Probe
import io.embodit.dosedistance.ui.dose.scene.DoseScene
import io.embodit.dosedistance.ui.dose.scene.PanelIds
import kotlinx.coroutines.delay

/**
 * The Stage content: one full-size SpatialView with the source, the shields, the room's tap targets and
 * the panel pool. Every attachment panel is declared once with a fixed size, shown or not ([DoseScene]
 * parents and places them). Tap and drag recognizers live in separate pointerInput blocks (Spatial Ruler).
 *
 * Window model: single panel with popups (design `windowModel`). The dosimeter is the one resident
 * panel; plates ride on 3D things; menu, dialogs, snackbar, coachmark and result card float.
 */
@Composable
internal fun DoseStage(state: DoseUiState, scene: DoseScene, onEvent: (DoseEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value -> scene.onSpatialDrag("move", null, value) }
                },
        attachments = {
            AttachmentPanel(PanelIds.DOSIMETER) {
                PoolPanel(PanelIds.DOSIMETER, scene, pressed = { scene.onPanelPointer() }, refresh = Triple(state.tab, state.drill?.phase, state.drill?.kind)) { inView -> DosimeterPanel(state = if (inView) state else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.COMPACT) {
                PoolPanel(PanelIds.COMPACT, scene, pressed = { scene.onPanelPointer() }, refresh = state.designState) { inView -> CompactPanel(state = if (inView) state else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.MENU) {
                PoolPanel(PanelIds.MENU, scene, pressed = { scene.onPanelPointer() }) { inView -> MoreMenu(state = if (inView) state else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.DIALOG) {
                PoolPanel(PanelIds.DIALOG, scene, refresh = state.designState) { inView -> DialogPanel(state = if (inView) state else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.SNACK) {
                PoolPanel(PanelIds.SNACK, scene, refresh = state.snack?.seq) { inView -> SnackPanel(snack = if (inView) state.snack else null) }
            }
            AttachmentPanel(PanelIds.COACH) {
                PoolPanel(PanelIds.COACH, scene) { inView -> CoachPanel(state = if (inView && state.coach) state else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.RESULT) {
                PoolPanel(PanelIds.RESULT, scene, refresh = state.drill?.phase) { inView -> ResultCard(state = if (inView) state else null, onEvent = onEvent) }
            }
            for (id in PanelIds.plates) {
                AttachmentPanel(id) {
                    PoolPanel(id, scene) { inView -> Plate(id = id, state = if (inView) state else null) }
                }
            }
        },
        update = { _, attachments ->
            // reading the tick makes this block run again while panel entities are still missing
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel: [content] gets `inView` (the panel has been moved into view; a pooled panel
 * draws only then, Spatial Ruler finding 3). Debug builds log every press as `PTR panel=<id> ev=`.
 */
@Composable
private fun PoolPanel(id: String, scene: DoseScene, pressed: (() -> Unit)? = null, refresh: Any? = null, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    var gen by remember { mutableIntStateOf(0) }
    LaunchedEffect(inView, refresh) {
        if (!inView) return@LaunchedEffect
        for (wait in REFRESH_STEPS_MS) {
            delay(wait)
            gen++
        }
    }
    val logged = Probe.enabled
    val watcher =
        if (logged || pressed != null) {
            Modifier.pointerInput(id, logged) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move) continue
                        if (event.type == PointerEventType.Press) pressed?.invoke()
                        if (logged) {
                            val p = event.changes.firstOrNull()?.position
                            DdLog.i(String.format(java.util.Locale.US, "PTR panel=%s ev=%s x=%.1f y=%.1f", id, event.type, p?.x ?: 0f, p?.y ?: 0f))
                        }
                    }
                }
            }
        } else Modifier
    CompositionLocalProvider(LocalProbePanel provides id) {
        Box(modifier = watcher) { key(gen) { content(inView) } }
    }
}

private val REFRESH_STEPS_MS = longArrayOf(1_500L, 2_500L, 4_000L)
