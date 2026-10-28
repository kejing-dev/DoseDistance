package io.embodit.dosedistance.ui.dose

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.SpatialSoundEffect
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.dosedistance.platform.DdLog
import io.embodit.dosedistance.platform.EyePermissionActivity
import io.embodit.dosedistance.ui.dose.components.DoseStage
import io.embodit.dosedistance.ui.dose.components.LocalDdResources
import io.embodit.dosedistance.ui.dose.components.localizedResources
import io.embodit.dosedistance.ui.dose.debug.Probe
import io.embodit.dosedistance.ui.dose.scene.DoseFrameSystem
import io.embodit.dosedistance.ui.dose.scene.DoseScene

/**
 * The one screen of the app, in a Mixed Stage (Full Space). It wires the ViewModel (state), the
 * [DoseScene] (entities, per-frame dose, taps and drags) and [DoseStage] (the panels), and owns nothing.
 */
@Composable
fun DoseScreen() {
    val context = LocalContext.current
    val viewModel: DoseViewModel = viewModel(factory = DoseViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { DoseScene(viewModel::onEvent) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        // the one per-frame system; every start has its stop in onDispose
        registerSystem<DoseFrameSystem>()
        Probe.host =
            object : Probe.Host {
                override fun viewModel(): DoseViewModel = viewModel

                override fun scene(): DoseScene = scene
            }
        // in the background every tracker stops, the clicks too (design `container.lifecycle`)
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> scene.pause()
                    Lifecycle.Event.ON_START -> scene.resume()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        DdLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            Probe.host = null
            unregisterSystem<DoseFrameSystem>()
            scene.detach()
            DdLog.i("STAGE disposed")
        }
    }

    LaunchedEffect(scene, state) { scene.render(state) }
    // v0.1.2: eye gaze + pinch aims (taste profile D1); asked once, after the Stage is up
    val ready = state.ready
    LaunchedEffect(ready) { if (ready) EyePermissionActivity.askOnce(context.applicationContext) }

    val audio = LocalAudioEffectPlayer.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                DoseEffect.Exit -> {
                    DdLog.i("STAGE close requested")
                    runCatching { navigator.closeStage() }.onFailure { DdLog.e("STAGE close failed: $it", it) }
                }
                is DoseEffect.Feedback ->
                    runCatching {
                        audio.playSystem(if (effect.kind == "snap") SpatialSoundEffect.StateSelected else SpatialSoundEffect.OpClick)
                    }.onFailure { DdLog.w("FEEDBACK failed: $it") }
            }
        }
    }

    // the in-app language switches at run time: panels read their copy from this local
    val resources = remember(state.settings.language, context) { localizedResources(context, state.settings.language) }
    CompositionLocalProvider(LocalDdResources provides resources) {
        DoseStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}
