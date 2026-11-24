package com.humblesolutions.ainotetaker.util

enum class SearchMode { NAME, PHONE, LAST4, EPIC }

/**
 * What did somebody just type?
 *
 * ONE CLASSIFICATION, shared by the team search and the roll search, because
 * the alternative is two that drift: a string that counts as an EPIC number on
 * one screen and a name on another returns different results from the same
 * keystrokes, with nothing on screen to explain why.
 *
 * | Typed            | Mode    | Why                                          |
 * |------------------|---------|----------------------------------------------|
 * | exactly 4 digits | `LAST4` | what people say when asked for a number      |
 * | other digits     | `PHONE` | a prefix from +91                            |
 * | letters + digits | `EPIC`  | an EPIC number is both, printed uppercase    |
 * | letters          | `NAME`  | the ordinary case                            |
 *
 * `LAST4` exists as a mode because no index can prefix-match the END of a
 * string — hence the stored `phoneLast4` field it queries.
 */
data class SearchTerm(
    val mode: SearchMode,
    val query: String,
    val digits: String,
    val upper: String,
    /** Punctuation stripped, matching how the import keys a rough member. */
    val epicId: String,
    val ready: Boolean,
) {
    companion object {
        /** Two letters before anything runs: one is most of a ward. */
        const val MIN_QUERY = 2

        /**
         * U+F8FF as an ESCAPE, never pasted.
         *
         * The literal character is invisible in an editor, and one careless
         * copy would silently turn every prefix search into an exact match. It
         * sorts after any ordinary character, so `[q, q + SENTINEL)` is the
         * half-open range of everything starting with `q`.
         */
        const val SENTINEL = "\uF8FF"

        fun classify(term: String?): SearchTerm {
            val trimmed = (term ?: "").trim()
            val query = trimmed.lowercase()
            val digits = query.filter { it.isDigit() }
            val numeric = digits.isNotEmpty() && digits.length == query.length

            val mode = when {
                numeric && digits.length == 4 -> SearchMode.LAST4
                numeric -> SearchMode.PHONE
                query.any { it.isDigit() } -> SearchMode.EPIC
                else -> SearchMode.NAME
            }

            return SearchTerm(
                mode = mode,
                query = query,
                digits = digits,
                upper = trimmed.uppercase(),
                epicId = trimmed.uppercase().filter { it.isLetterOrDigit() },
                ready = query.length >= MIN_QUERY,
            )
        }
    }
}
