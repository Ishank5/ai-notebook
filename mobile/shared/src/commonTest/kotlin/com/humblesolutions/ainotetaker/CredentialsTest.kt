package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.model.Credentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one door: a username and a password. */
class CredentialsTest {

    @Test
    fun a_username_is_lowercase_starts_with_a_letter_and_is_three_to_twenty_long() {
        assertTrue(Credentials.isValidUsername("first.last"))
        assertTrue(Credentials.isValidUsername("a_b"))
        assertTrue(Credentials.isValidUsername("worker99"))

        assertFalse(Credentials.isValidUsername("ab"))                    // too short
        assertFalse(Credentials.isValidUsername("a".repeat(21)))          // too long
        assertFalse(Credentials.isValidUsername("9lives"))                // starts with a digit
        assertFalse(Credentials.isValidUsername("has space"))
        assertFalse(Credentials.isValidUsername("has@symbol"))
    }

    @Test
    fun capitals_are_refused_rather_than_folded() {
        /*
         * If `Ansh` and `ansh` were the same account, showing one of them back
         * would be showing something the person did not type — and somebody
         * would eventually compare two spellings and conclude they were two
         * different people. One rule beats a subtlety.
         */
        assertFalse(Credentials.isValidUsername("Ansh"))
    }

    @Test
    fun the_identity_behind_a_username_is_a_domain_that_can_never_receive_mail() {
        // RFC 2606 reserves `.invalid`, so no reset, bounce or typo can reach
        // a real inbox. Nothing ever sends to it; Firebase just needs a key.
        assertEquals("first.last@tas.invalid", Credentials.emailFor("first.last"))
        assertEquals("first.last@tas.invalid", Credentials.emailFor("  Ansh.Bajaj  "))
    }

    @Test
    fun the_mapping_round_trips_and_ignores_addresses_that_are_not_ours() {
        assertEquals("ansh", Credentials.usernameFrom(Credentials.emailFor("ansh")))
        assertNull(Credentials.usernameFrom("someone@gmail.com"))
        assertNull(Credentials.usernameFrom(null))
    }

    @Test
    fun a_password_is_judged_on_length_alone() {
        /*
         * A rule demanding a symbol and a digit at a doorstep produces
         * `Pass@123` on every account in the ward, which is worse than a long
         * ordinary one.
         */
        assertFalse(Credentials.isValidPassword("short12"))
        assertTrue(Credentials.isValidPassword("longenough"))
        assertTrue(Credentials.isValidPassword("all lowercase letters no digits"))
    }

    @Test
    fun a_username_is_suggested_from_a_name_but_stays_a_suggestion() {
        assertEquals("first.last", Credentials.suggestFrom("Ansh Bajaj"))
        assertEquals("kanta.rani", Credentials.suggestFrom("KANTA RANI"))
        // Punctuation and double spaces are not usernames.
        assertEquals("j.p.singh", Credentials.suggestFrom("J. P. Singh"))
    }

    @Test
    fun a_suggestion_is_always_something_the_rules_would_accept() {
        /*
         * A suggestion that fails validation is worse than none: it puts a red
         * error under a box the worker did not type into.
         */
        for (name in listOf("Ansh Bajaj", "KANTA RANI", "J. P. Singh", "A B", "9 Lives", "Ravinderjeet Kaur Sandhu")) {
            val suggestion = Credentials.suggestFrom(name)
            if (suggestion.isNotBlank()) {
                assertTrue(
                    Credentials.isValidUsername(suggestion),
                    "suggestion for \"$name\" was \"$suggestion\", which the rules refuse",
                )
            }
        }
    }

    @Test
    fun a_name_with_nothing_usable_in_it_suggests_nothing() {
        assertEquals("", Credentials.suggestFrom("   "))
        assertEquals("", Credentials.suggestFrom("!!!"))
    }
}
