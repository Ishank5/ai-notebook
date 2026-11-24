package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.util.Dob
import com.humblesolutions.ainotetaker.util.DobResult
import com.humblesolutions.ainotetaker.util.Validation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ValidationTest {

    /** 2026-09-20, so "in the future" means something fixed in a test. */
    private val now = Dob.toEpochMillis(2026, 9, 20)

    @Test
    fun a_mobile_is_ten_digits_starting_six_to_nine() {
        assertTrue(Validation.isValidMobile("9876543210"))
        assertTrue(Validation.isValidMobile("98765 43210"))
        assertTrue(Validation.isValidMobile("+91 98765 43210".removePrefix("+91")))
        // Landlines and old prefixes: the number is the LOGIN identifier, and
        // an OTP has to arrive on it.
        assertFalse(Validation.isValidMobile("5876543210"))
        assertFalse(Validation.isValidMobile("987654321"))
        assertFalse(Validation.isValidMobile(""))
    }

    @Test
    fun storage_form_is_always_e164() {
        assertEquals("+919876543210", Validation.toE164("98765 43210"))
    }

    @Test
    fun digits_from_any_script_become_plain_digits() {
        // Hindi and Punjabi keyboards can produce their own numerals. Left as
        // typed, "९८७६५४३२१०" looked like ten digits and was refused as invalid.
        assertEquals("9876543210", Validation.digitsOf("९८७६५४३२१०"))
        assertEquals("9876543210", Validation.digitsOf("੯੮੭੬੫੪੩੨੧੦"))
        assertEquals("9876543210", Validation.digitsOf("９８７６５４３２１０"))
        assertTrue(Validation.isValidMobile("९८७६५४३२१०"))
        assertEquals("+919876543210", Validation.toE164("९८७६५ ४३२१०"))
        // Letters, spaces and punctuation are still dropped.
        assertEquals("9876543210", Validation.digitsOf("(98765) 43210 ext."))
        assertEquals("", Validation.digitsOf(null))
    }

    @Test
    fun a_pasted_number_loses_its_country_code_and_trunk_zero() {
        // What contacts, WhatsApp and a handwritten note actually hand over.
        assertEquals("9876543210", Validation.mobileFromInput("+91 98765 43210"))
        assertEquals("9876543210", Validation.mobileFromInput("+91-9876543210"))
        assertEquals("9876543210", Validation.mobileFromInput("919876543210"))
        assertEquals("9876543210", Validation.mobileFromInput("0091 98765 43210"))
        assertEquals("9876543210", Validation.mobileFromInput("098765 43210"))
        assertEquals("9876543210", Validation.mobileFromInput("+91 098765 43210"))
        assertEquals("9876543210", Validation.mobileFromInput("(+९१) ९८७६५ ४३२१०"))
    }

    @Test
    fun typing_a_number_that_begins_91_is_never_eaten_as_a_country_code() {
        // A real mobile can start 91. Every keystroke of it must survive, and a
        // stray eleventh digit must be dropped rather than shift the number.
        var typed = ""
        for (digit in "9198765432") {
            typed = Validation.mobileFromInput(typed + digit)
            assertEquals("9198765432".take(typed.length), typed)
        }
        assertEquals("9198765432", typed)
        assertEquals("9198765432", Validation.mobileFromInput(typed + "1"))
    }

    @Test
    fun a_short_or_blank_box_is_left_alone() {
        assertEquals("", Validation.mobileFromInput(""))
        assertEquals("", Validation.mobileFromInput(null))
        assertEquals("9", Validation.mobileFromInput("9"))
        assertEquals("98765", Validation.mobileFromInput("98765"))
        // A lone leading zero is somebody mid-way through "098765…", not a
        // number to strip yet.
        assertEquals("0", Validation.mobileFromInput("0"))
    }

    @Test
    fun last_four_is_what_people_actually_say() {
        assertEquals("3210", Validation.last4("+919876543210"))
        assertEquals(null, Validation.last4("321"))
    }

    @Test
    fun an_epic_number_is_normalised_the_way_the_import_keys_it() {
        assertEquals("SLH1234567", Validation.normaliseEpic("slh-1234567"))
        assertTrue(Validation.isPlausibleEpic("SLH1234567"))
        assertFalse(Validation.isPlausibleEpic("AB"))
    }

    @Test
    fun email_rejects_the_shapes_that_actually_get_typed() {
        assertTrue(Validation.isValidEmail("admin@tas.local"))
        assertFalse(Validation.isValidEmail("admin@tas"))
        assertFalse(Validation.isValidEmail("admin tas@x.com"))
        assertFalse(Validation.isValidEmail("@tas.local"))
        assertFalse(Validation.isValidEmail("a@b@c.com"))
    }

    @Test
    fun all_three_date_boxes_blank_is_legitimate() {
        // Plenty of people do not know their date of birth.
        assertIs<DobResult.Empty>(Dob.parse("", "", "", now))
    }

    @Test
    fun a_partly_filled_date_is_a_different_mistake_from_a_blank_one() {
        assertIs<DobResult.Invalid>(Dob.parse("03", "", "1980", now))
    }

    @Test
    fun impossible_dates_are_refused_including_the_leap_year_edge() {
        assertIs<DobResult.Invalid>(Dob.parse("31", "02", "1980", now))
        assertIs<DobResult.Invalid>(Dob.parse("29", "02", "1981", now))
        assertIs<DobResult.Valid>(Dob.parse("29", "02", "1980", now))
    }

    @Test
    fun a_birthday_in_the_future_is_a_typo_always() {
        assertIs<DobResult.Invalid>(Dob.parse("01", "01", "2030", now))
    }

    @Test
    fun dates_round_trip_through_the_three_boxes() {
        // The Indian way round — 03/01/1980 is the third of January, which is
        // what every roll prints and what a naive parser reads as March.
        val parsed = Dob.parse("03", "01", "1980", now)
        assertIs<DobResult.Valid>(parsed)
        assertEquals(Triple(3, 1, 1980), Dob.toParts(parsed.epochMillis))
    }

    @Test
    fun dates_before_the_epoch_round_trip_too() {
        val parsed = Dob.parse("15", "08", "1947", now)
        assertIs<DobResult.Valid>(parsed)
        assertEquals(Triple(15, 8, 1947), Dob.toParts(parsed.epochMillis))
    }

    @Test
    fun the_day_box_refuses_anything_that_cannot_be_a_day() {
        assertEquals("3", Dob.typedDay("", "3"))
        assertEquals("31", Dob.typedDay("3", "31"))
        assertEquals("3", Dob.typedDay("3", "32"))
        assertEquals("0", Dob.typedDay("0", "00"))
        assertEquals("09", Dob.typedDay("0", "09"))
        assertEquals("31", Dob.typedDay("31", "311"))
        assertEquals("", Dob.typedDay("3", ""))
    }

    @Test
    fun the_month_box_refuses_anything_above_twelve() {
        assertEquals("12", Dob.typedMonth("1", "12"))
        assertEquals("1", Dob.typedMonth("1", "13"))
        assertEquals("0", Dob.typedMonth("0", "00"))
    }

    @Test
    fun the_year_box_takes_four_digits_and_nothing_after_this_year() {
        assertEquals("1", Dob.typedYear("", "1", now))
        assertEquals("19", Dob.typedYear("1", "19", now))
        assertEquals("1", Dob.typedYear("1", "18", now)) // 18xx is before 1900
        assertEquals("", Dob.typedYear("", "3", now))    // 3xxx can only be the future
        assertEquals("2", Dob.typedYear("2", "21", now)) // 21xx likewise
        assertEquals("2026", Dob.typedYear("202", "2026", now))
        assertEquals("202", Dob.typedYear("202", "2027", now))
        assertEquals("1980", Dob.typedYear("1980", "19801", now))
    }

    @Test
    fun a_complete_date_later_this_year_is_flagged_as_it_lands() {
        assertTrue(Dob.isFuture("21", "09", "2026", now))
        assertTrue(Dob.isFuture("01", "12", "2026", now))
        assertFalse(Dob.isFuture("19", "09", "2026", now))
        assertFalse(Dob.isFuture("21", "09", "202", now)) // not finished yet
        assertFalse(Dob.isFuture("", "", "", now))
    }

    @Test
    fun a_phone_number_never_holds_more_than_ten_digits() {
        assertEquals("9876543210", Validation.mobileFromInput("98765432109"))
        assertEquals("9876543210", Validation.mobileFromInput("+91 98765 43210"))
    }

    @Test
    fun a_day_is_complete_at_two_digits_or_at_one_no_day_can_follow() {
        assertFalse(Dob.dayComplete(""))
        // 1, 2 and 3 can still become 10–31.
        assertFalse(Dob.dayComplete("1"))
        assertFalse(Dob.dayComplete("2"))
        assertFalse(Dob.dayComplete("3"))
        assertFalse(Dob.dayComplete("0"))
        // There is no 40th: 4 to 9 are already whole days.
        assertTrue(Dob.dayComplete("4"))
        assertTrue(Dob.dayComplete("9"))
        assertTrue(Dob.dayComplete("07"))
        assertTrue(Dob.dayComplete("31"))
    }

    @Test
    fun a_month_is_complete_at_two_digits_or_at_one_no_month_can_follow() {
        assertFalse(Dob.monthComplete(""))
        // 0 and 1 can still become 01–12.
        assertFalse(Dob.monthComplete("0"))
        assertFalse(Dob.monthComplete("1"))
        // There is no 20th month: 2 to 9 are already whole months.
        assertTrue(Dob.monthComplete("2"))
        assertTrue(Dob.monthComplete("9"))
        assertTrue(Dob.monthComplete("12"))
    }
}
