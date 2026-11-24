package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.util.SearchMode
import com.humblesolutions.ainotetaker.util.SearchTerm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchTermTest {

    @Test
    fun four_digits_is_the_last_four_of_a_phone() {
        // What people actually say when asked for a number.
        assertEquals(SearchMode.LAST4, SearchTerm.classify("0007").mode)
    }

    @Test
    fun other_digit_runs_are_a_phone_prefix() {
        assertEquals(SearchMode.PHONE, SearchTerm.classify("98765").mode)
        assertEquals(SearchMode.PHONE, SearchTerm.classify("987").mode)
    }

    @Test
    fun letters_and_digits_together_are_an_epic_number() {
        val parsed = SearchTerm.classify("slh-800002")
        assertEquals(SearchMode.EPIC, parsed.mode)
        // Printed uppercase on the card, stored that way, and keyed with the
        // punctuation stripped.
        assertEquals("SLH-800002", parsed.upper)
        assertEquals("SLH800002", parsed.epicId)
    }

    @Test
    fun plain_letters_are_a_name() {
        assertEquals(SearchMode.NAME, SearchTerm.classify("Gurdev").mode)
        assertEquals("gurdev", SearchTerm.classify("Gurdev").query)
    }

    @Test
    fun one_letter_is_not_enough_to_run_anything() {
        assertFalse(SearchTerm.classify("g").ready)
        assertTrue(SearchTerm.classify("gu").ready)
        assertFalse(SearchTerm.classify("  ").ready)
    }

    /**
     * THE SENTINEL MUST BE AN ESCAPE, NOT A PASTED CHARACTER.
     *
     * U+F8FF is invisible in an editor. One careless copy replaces it with an
     * empty string, every prefix range collapses to an exact match, and search
     * silently returns nothing for everything — with no error anywhere.
     */
    @Test
    fun the_prefix_sentinel_is_the_real_character() {
        assertEquals(1, SearchTerm.SENTINEL.length)
        assertEquals('\uF8FF', SearchTerm.SENTINEL[0])
        assertTrue("gurdev" < "gurdev${SearchTerm.SENTINEL}")
    }
}
