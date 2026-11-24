package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.i18n.Language
import com.humblesolutions.ainotetaker.i18n.NotetakerStrings
import com.humblesolutions.ainotetaker.i18n.RecorderStrings
import com.humblesolutions.ainotetaker.i18n.Strings
import com.humblesolutions.ainotetaker.usecase.RecorderErrors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The recorder in every language, read past the English fallback — see
 * [FormStringsParityTest] for why a missing string would otherwise ship.
 */
class RecorderStringsTest {

    private val placeholder = Regex("""\{\{\s*(\w+)\s*}}""")

    @Test
    fun every_language_has_the_same_keys() {
        val english = RecorderStrings.en.keys
        assertEquals(emptySet(), english - RecorderStrings.hi.keys, "missing Hindi")
        assertEquals(emptySet(), english - RecorderStrings.pa.keys, "missing Punjabi")
        assertEquals(emptySet(), RecorderStrings.hi.keys - english, "Hindi-only keys")
        assertEquals(emptySet(), RecorderStrings.pa.keys - english, "Punjabi-only keys")
    }

    @Test
    fun translations_keep_their_placeholders() {
        for ((key, english) in RecorderStrings.en) {
            val expected = placeholder.findAll(english).map { it.groupValues[1] }.toSet()
            for (table in listOf(RecorderStrings.hi, RecorderStrings.pa)) {
                val actual = placeholder.findAll(table.getValue(key)).map { it.groupValues[1] }.toSet()
                assertEquals(expected, actual, "placeholders differ for $key")
            }
        }
    }

    /** the name, and the strings for the planned length. */
    @Test
    fun the_feature_is_called_AI_Notetaker_and_its_strings_have_no_em_dashes() {
        assertEquals("AI Notebook", Strings.get("recorder.title", Language.ENGLISH))
        assertEquals("AI Notebook", Strings.get("common.nav.notetaker", Language.ENGLISH))
        assertEquals("AI Notebook is recording", Strings.get("recorder.notif.recording", Language.ENGLISH))
        assertEquals("AI Notebook", Strings.get("recorder.title", Language.HINDI), "falls back to English")
        for ((key, text) in NotetakerStrings.en) assertFalse('\u2014' in text, "em dash in $key")
        assertEquals(
            "Recording stops in 5 minutes. 3 hours is the longest it can run.",
            Strings.get("recorder.warn.final", Language.ENGLISH, mapOf("left" to "5 minutes", "cap" to "3 hours")),
        )
    }

    @Test
    fun every_error_the_recorder_can_report_reads_as_a_sentence() {
        // A key with no string reaches the screen as `recorder.err.noSpace`.
        for (key in RecorderErrors.ALL) {
            for (language in Language.entries) {
                assertTrue(Strings.get(key, language) != key, "no $language string for $key")
            }
        }
    }
}
