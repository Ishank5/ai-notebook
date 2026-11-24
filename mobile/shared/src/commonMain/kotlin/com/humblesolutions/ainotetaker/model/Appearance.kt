package com.humblesolutions.ainotetaker.model

/**
 * Light, dark, or whatever the phone is set to.
 *
 * THE DEFAULT IS THE PHONE'S. Somebody who has set their phone to dark has
 * already said what they want, and an app that ignores that is the one app on
 * their home screen that burns their eyes at night.
 *
 * The override exists because the phone's setting is not always theirs to
 * change — a shared handset, or a phone on an automatic schedule that flips to
 * dark at six in the evening, which is exactly when a booth worker is outside
 * in the last of the daylight with the screen at full brightness.
 */
enum class Appearance(val code: String, val labelKey: String) {
    SYSTEM("system", "profile.themeSystem"),
    LIGHT("light", "profile.themeLight"),
    DARK("dark", "profile.themeDark");

    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        val default = SYSTEM
        fun fromCode(code: String?): Appearance =
            entries.firstOrNull { it.code == code } ?: default
    }
}
