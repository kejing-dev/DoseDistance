package io.embodit.dosedistance.data

import android.content.Context
import io.embodit.dosedistance.domain.Dose
import io.embodit.dosedistance.domain.Isotope
import io.embodit.dosedistance.domain.Language
import io.embodit.dosedistance.domain.Settings
import java.util.Locale

/** Preferences on the device (no network, no account). */
interface SettingsStore {
    fun load(): Settings

    fun save(settings: Settings)
}

class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("dose_distance", Context.MODE_PRIVATE)

    override fun load(): Settings =
        Settings(
            isotope = Isotope.byTag(prefs.getString("iso", null)),
            thickness = prefs.getInt("t", Dose.DEFAULT_T).takeIf { it in Dose.THICKNESSES } ?: Dose.DEFAULT_T,
            geiger = prefs.getBoolean("geiger", true),
            rings = prefs.getBoolean("rings", true),
            heat = prefs.getBoolean("heat", true),
            snap = prefs.getBoolean("snap", true),
            language = Language.byTag(prefs.getString("lang", null)) ?: Language.ofSystem(Locale.getDefault().toLanguageTag()),
            coachSeen = prefs.getBoolean("coach", false),
        )

    override fun save(settings: Settings) {
        prefs.edit()
            .putString("iso", settings.isotope.tag)
            .putInt("t", settings.thickness)
            .putBoolean("geiger", settings.geiger)
            .putBoolean("rings", settings.rings)
            .putBoolean("heat", settings.heat)
            .putBoolean("snap", settings.snap)
            .putString("lang", settings.language.tag)
            .putBoolean("coach", settings.coachSeen)
            .apply()
    }
}
