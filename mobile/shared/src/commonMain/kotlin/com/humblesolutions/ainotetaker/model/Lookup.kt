package com.humblesolutions.ainotetaker.model

import com.humblesolutions.ainotetaker.util.FuzzySearch

/**
 * One entry of a dropdown the client maintains — occupation, religion, caste,
 * home status, designation, category, community.
 *
 * THE LABEL IS TRILINGUAL, not a string. `validLookupItem()` in
 * firestore.rules requires `{ en, hi, pa }` with all three non-empty, and the
 * point is not the rule: an admin who fills in only English leaves a PUNJABI
 * reader with a blank row in a dropdown, weeks later, with no clue what it was
 * meant to say. The same shape as a `GeoUnit` name, for the same reason.
 *
 * Deactivated rather than deleted: the entry vanishes from every picker while
 * the records that already reference it stay readable.
 */
data class LookupItem(
    val id: String = "",
    val label: LocalisedName = LocalisedName(),
    val order: Int = 0,
    val active: Boolean = true,
    /**
     * PARTIES ONLY, FOR NOW — how else an entry is known. Empty on every other
     * list, and on a party a worker added from the field until an admin fills
     * them in (Master Data's "Short name" and "Other names").
     *
     * [shortName] is the initials everybody writes in English letters in every
     * language ("INC"); [common] is what people call it, per language
     * ("Congress" / "कांग्रेस" / "ਕਾਂਗਰਸ"); [aliases] is everything else it
     * answers to — election-symbol names, other spellings, other scripts.
     * All of it is searched (see `FuzzySearch`); only [label] is ever stored
     * on a person's record, by [id].
     */
    val shortName: String = "",
    val common: LocalisedName = LocalisedName(),
    val aliases: List<String> = emptyList(),
    /** Where the party works — "Punjab", "National", "State/Regional" — for the office's eyes. */
    val category: String = "",
) {
    /** The label in whichever language is being read right now. */
    fun labelIn(languageCode: String): String = label.forLanguage(languageCode)

    /**
     * The line under the name in a dropdown: the short name and what people
     * call it, in the reader's language — "INC · Congress" — without saying
     * the same thing twice ("BSP · BSP" is just "BSP"). Empty when the entry
     * has neither.
     */
    fun subtitleIn(languageCode: String): String {
        val title = FuzzySearch.normalize(labelIn(languageCode))
        val seen = mutableSetOf(title)
        return listOf(shortName, common.forLanguage(languageCode))
            .map { it.trim() }
            .filter { it.isNotEmpty() && seen.add(FuzzySearch.normalize(it)) }
            .joinToString(" · ")
    }

    /** Every name this entry can be found by, in every language and spelling. */
    fun searchTerms(): List<String> =
        (listOf(label.en, label.hi, label.pa, shortName, common.en, common.hi, common.pa) + aliases)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}

/**
 * The lookup lists, by the id the backend stores them under.
 *
 * SEVEN, in the order a registration form meets them — the same list and the
 * same order as the web's `LookupBrowser`. An id here is a Firestore document
 * path, so a typo is an empty dropdown rather than a compile error.
 */
enum class LookupType(val id: String) {
    OCCUPATION("occupation"),
    RELIGION("religion"),
    CASTE("caste"),
    HOME_STATUS("homeStatus"),
    DESIGNATION("designation"),
    CATEGORY("category"),
    COMMUNITY("community"),

    /**
     * Political parties — the answer to "which party did you vote for last
     * time" and "which will you vote for this time" on the Locality Member
     * form. One list for both questions, so a party renamed in Master Data is
     * renamed in both answers. Starts empty; the office fills it.
     */
    PARTY("party");

    companion object {
        fun fromId(id: String?): LookupType? = entries.firstOrNull { it.id == id }
    }
}
