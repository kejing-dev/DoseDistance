package io.embodit.dosedistance.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Transparent helper for the eye-tracking permission `com.picovr.permission.EYE_TRACKING` (SDK doc "Eye tracking":
 * a runtime permission), ported from Rehearsal Hall. Asked once; refused or no dialog: aiming uses the controller ray,
 * else the head, and the panel says so (v0.1.2).
 */
class EyePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (granted(this)) {
            finish()
            return
        }
        requestPermissions(arrayOf(PERMISSION), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        DdLog.i("EYE permission granted=${grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED}")
        finish()
    }

    companion object {
        const val PERMISSION = "com.picovr.permission.EYE_TRACKING"
        private const val REQUEST = 53

        fun granted(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

        /** Once per install (the system dialog may not show over a Full Space Stage). */
        fun askOnce(context: Context) {
            if (granted(context)) return
            val prefs = context.getSharedPreferences("dose_distance", Context.MODE_PRIVATE)
            if (prefs.getBoolean("eyeAsked", false)) return
            prefs.edit().putBoolean("eyeAsked", true).apply()
            val ok = runCatching { context.startActivity(Intent(context, EyePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            DdLog.i("EYE permission request opened=$ok")
        }
    }
}
