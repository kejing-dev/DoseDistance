package io.embodit.dosedistance.domain

/** In-app language (design `i18n`: 中文 / EN, first from the system language, then remembered). */
enum class Language(val tag: String) {
    ZH("zh"),
    EN("en");

    companion object {
        fun byTag(tag: String?): Language? = entries.firstOrNull { it.tag == tag }

        fun ofSystem(languageTag: String): Language = if (languageTag.startsWith("zh")) ZH else EN
    }
}

/** Preferences (design `persistence.what`); the scene itself lives only in memory. */
data class Settings(
    val isotope: Isotope = Isotope.CS137,
    val thickness: Int = Dose.DEFAULT_T,
    val geiger: Boolean = true,
    val rings: Boolean = true,
    /** v0.1.1: the dose-rate floor map, and the shield's snap to its suggested point (an aid that can be turned off). */
    val heat: Boolean = true,
    val snap: Boolean = true,
    val language: Language = Language.ZH,
    val coachSeen: Boolean = false,
)
