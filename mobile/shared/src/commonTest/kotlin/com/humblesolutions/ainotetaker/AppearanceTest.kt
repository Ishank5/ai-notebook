package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.i18n.Language
import com.humblesolutions.ainotetaker.i18n.Strings
import com.humblesolutions.ainotetaker.model.Appearance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Light, dark, or whatever the phone is set to — the web's Appearance control. */
class AppearanceTest {

    @Test
    fun the_default_follows_the_phone() {
        assertEquals(Appearance.SYSTEM, Appearance.default)
        assertTrue(Appearance.SYSTEM.isDark(systemIsDark = true))
        assertTrue(!Appearance.SYSTEM.isDark(systemIsDark = false))
    }

    @Test
    fun an_override_ignores_the_phone_in_both_directions() {
        assertTrue(!Appearance.LIGHT.isDark(systemIsDark = true))
        assertTrue(Appearance.DARK.isDark(systemIsDark = false))
    }

    @Test
    fun an_unreadable_stored_value_falls_back_rather_than_crashing() {
        assertEquals(Appearance.DARK, Appearance.fromCode("dark"))
        assertEquals(Appearance.SYSTEM, Appearance.fromCode(null))
        assertEquals(Appearance.SYSTEM, Appearance.fromCode("sepia"))
    }

    @Test
    fun each_choice_is_named_in_all_three_languages() {
        for (appearance in Appearance.entries) {
            for (language in Language.entries) {
                val label = Strings.get(appearance.labelKey, language)
                assertTrue(label.isNotBlank(), "${appearance.labelKey} missing in ${language.code}")
                assertTrue(!label.startsWith("profile."), "${appearance.labelKey} unresolved")
            }
        }
    }
}
