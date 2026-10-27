package io.embodit.dosedistance.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.dosedistance.platform.DdLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action
 * `io.embodit.dosedistance.DEBUG`, design `qaHooks.broadcast`); see [DebugCommands] for the command list.
 *
 *   adb -s <serial> shell "am broadcast -a io.embodit.dosedistance.DEBUG -p io.embodit.dosedistance --es cmd goto --es s S13"
 *
 * Extras: `cmd`; text `s` / `v` / `id` / `do` / `what` / `surface` / `a`; ints `n` / `t` / `on`; floats `x` / `y` /
 * `z` / `yaw` / `pitch` / `cum`.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val texts = HashMap<String, String>()
        for (k in listOf("s", "v", "id", "do", "what", "surface", "a")) intent.getStringExtra(k)?.let { texts[k] = it }
        val ints = HashMap<String, Int>()
        for (k in listOf("n", "t", "on")) if (intent.hasExtra(k)) ints[k] = intent.getIntExtra(k, 0)
        val floats = HashMap<String, Float>()
        for (k in listOf("x", "y", "z", "yaw", "pitch", "cum")) if (intent.hasExtra(k)) floats[k] = intent.getFloatExtra(k, 0f)
        DdLog.i("CMD recv '$cmd' s=$texts i=$ints f=$floats")
        // broadcasts arrive on the main thread, which is where the ViewModel and the scene live
        DebugCommands.run(context.applicationContext, cmd, texts, ints, floats)
    }
}
