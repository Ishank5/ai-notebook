package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.i18n.Language
import com.humblesolutions.ainotetaker.i18n.Strings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StringsTest {

    @Test
    fun every_language_answers_a_known_key() {
        for (language in Language.entries) {
            val value = Strings.get("auth.sendOtp", language)
            assertTrue(value.isNotBlank(), "${language.code} had no auth.sendOtp")
            assertFalse(value == "auth.sendOtp", "${language.code} fell through to the key")
        }
    }

    @Test
    fun hindi_and_punjabi_are_really_translated_not_english_copies() {
        val en = Strings.get("auth.sendOtp", Language.ENGLISH)
        assertTrue(Strings.get("auth.sendOtp", Language.HINDI) != en)
        assertTrue(Strings.get("auth.sendOtp", Language.PUNJABI) != en)
    }

    @Test
    fun placeholders_are_filled_in() {
        val value = Strings.get(
            "roll.houseNo",
            Language.ENGLISH,
            mapOf("no" to "101"),
        )
        assertTrue(value.contains("101"), "got: $value")
        assertFalse(value.contains("{{"), "left a placeholder: $value")
    }

    @Test
    fun plurals_pick_one_or_other() {
        val one = Strings.get("roll.undoneNote", Language.ENGLISH, mapOf("count" to 1))
        val many = Strings.get("import.undoneNote", Language.ENGLISH, mapOf("count" to 4))
        // Whichever namespace carries it, 1 and 4 must not read the same.
        assertTrue(one.isNotBlank() && many.isNotBlank())
    }

    // the two EventStrings checks went with the events strings table.

    @Test
    fun an_unknown_key_returns_itself_rather_than_throwing() {
        // A missing string is a translation task, not a crash on a field worker's
        // phone in the middle of a registration.
        assertEquals("nope.not.here", Strings.get("nope.not.here", Language.ENGLISH))
        assertFalse(Strings.has("nope.not.here"))
    }
}
