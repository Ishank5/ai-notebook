package com.humblesolutions.ainotetaker.util

/**
 * Finding a party however it is typed — the official name, the initials,
 * what people call it, a nickname, in any of the three scripts, and with the
 * spelling a worker actually manages at a doorstep: "kangres", "bahujan
 * samaaj", "akaali dal", "a.a.p".
 *
 * FOUR THINGS, CHEAPEST FIRST, and a match earned by an earlier one always
 * ranks above a later one:
 *  1. TIDYING — case, dots, brackets and spaces do not count: "A.A.P",
 *     "aap " and "CPI (M)" read as "aap" and "cpim".
 *  2. SOUNDING ALIKE — the ways Indian names are spelt in English letters are
 *     folded together: bh/b, dh/d, sh/s…, w/v, ph/f, c/k, z/j, ee/i, oo/u,
 *     aa/a, y/i, and doubled letters. "Samajvadi" is "Samajwadi".
 *     Latin letters only: in Devanagari and Gurmukhi these are real letters.
 *  3. EXACT, THEN START, THEN INSIDE — as `OptionSearch` ranks every other
 *     dropdown in the app.
 *  4. A NEAR MISS — one wrong letter in a word of 5 to 8, two in a longer one.
 *     NEVER FOR 4 LETTERS OR FEWER: one letter off "SP" is "SS", and the
 *     initials are exactly what a worker is most likely to type right.
 *
 * SHARED, so Android and iOS put the same party first for the same letters.
 * Ties keep the list's own order — Punjab's parties first.
 *
 * EVERY NAME IS PREPARED ONCE AND KEPT. Tidying and folding a name is the
 * costly part, and a keystroke used to redo it for every name of every party —
 * some six hundred, each through several patterns — which is what made typing
 * in the party field lag. Now a keystroke prepares only what was typed.
 */
object FuzzySearch {

    private val SPACES = Regex("\\s+")
    private val HARD_C = Regex("c(?!h)")
    private val ASPIRATE = Regex("([kgtdpbcsj])h")
    private val DOUBLED = Regex("(.)\\1+")

    /** A name tidied and folded: its words, and the words run together. */
    private class Key(val words: List<String>, val joined: String)

    /** Past this many names, start again — a guard, the lists are far smaller. */
    private const val KEPT_MAX = 5000
    private val keys = HashMap<String, Key>()
    private val tidied = HashMap<String, String>()

    private fun keyOf(text: String): Key =
        keys[text] ?: run {
            if (keys.size > KEPT_MAX) keys.clear()
            val words = normalize(text).split(' ').map { sound(it) }.filter { it.isNotEmpty() }
            Key(words, words.joinToString("")).also { keys[text] = it }
        }

    private const val EXACT = 0
    private const val STARTS = 1
    private const val WORD_START = 2
    private const val CONTAINS = 3
    private const val NEAR = 4
    private const val NO_MATCH = Int.MAX_VALUE

    /** Lower case, "&" as "and", and only letters, digits and vowel signs — the rest is a space. */
    fun normalize(text: String): String =
        tidied[text] ?: tidy(text).also {
            if (tidied.size > KEPT_MAX) tidied.clear()
            tidied[text] = it
        }

    private fun tidy(text: String): String {
        val out = StringBuilder()
        for (ch in text.lowercase().replace("&", " and ")) {
            val keep = ch.isLetterOrDigit() ||
                ch.category == CharCategory.NON_SPACING_MARK ||
                ch.category == CharCategory.COMBINING_SPACING_MARK
            out.append(if (keep) ch else ' ')
        }
        return out.toString().trim().replace(SPACES, " ")
    }

    private fun isLatin(text: String) = text.all { it.code < 0x250 }

    /** Sound-alike folding for Latin spellings — see (2) above. */
    private fun sound(text: String): String {
        if (!isLatin(text)) return text
        var s = text
            .replace("ph", "f").replace("w", "v").replace("z", "j")
            .replace("q", "k").replace("x", "ks").replace("ck", "k")
        s = s.replace(HARD_C, "k")
        s = s.replace(ASPIRATE, "$1")
        s = s.replace("ee", "i").replace("oo", "u").replace("aa", "a").replace("y", "i")
        return s.replace(DOUBLED, "$1")
    }

    /** How well [term] answers [query] — lower is better, `Int.MAX_VALUE` is no match. */
    fun score(term: String, query: String): Int {
        val q = keyOf(query).joined
        if (q.isEmpty()) return EXACT
        val t = keyOf(term)
        val joined = t.joined
        if (joined.isEmpty()) return NO_MATCH
        return when {
            joined == q -> EXACT
            joined.startsWith(q) -> STARTS
            t.words.any { it.startsWith(q) } -> WORD_START
            joined.contains(q) -> CONTAINS
            q.length >= 5 -> {
                val allowed = if (q.length <= 8) 1 else 2
                val candidates = t.words + joined +
                    ((q.length - allowed)..(q.length + allowed))
                        .filter { it in 1..joined.length }
                        .map { joined.take(it) }
                val best = candidates.minOf { distance(q, it, allowed) }
                if (best <= allowed) NEAR + best else NO_MATCH
            }
            else -> NO_MATCH
        }
    }

    /**
     * The rows that answer [query], best first, as positions into [rows] —
     * one row per option, holding every name it can be found by. Positions
     * rather than the options themselves, so Swift can use it with its own
     * row type (see `OptionSearch.rankIndices`).
     */
    fun rankIndices(rows: List<List<String>>, query: String): List<Int> {
        if (normalize(query).isEmpty()) return rows.indices.toList()
        return rows
            .mapIndexedNotNull { index, terms ->
                terms.minOfOrNull { score(it, query) }?.takeIf { it != NO_MATCH }?.let { index to it }
            }
            // Stable: equal scores keep the list's own order.
            .sortedBy { it.second }
            .map { it.first }
    }

    /** Whether [typed] names one of [terms] exactly, once tidied and folded. */
    fun namesExactly(terms: List<String>, typed: String): Boolean {
        val q = keyOf(typed).joined
        return q.isNotEmpty() && terms.any { keyOf(it).joined == q }
    }

    /** Edit distance with swapped neighbours counting once; gives up past [cap]. */
    private fun distance(a: String, b: String, cap: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > cap) return cap + 1
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
                d[i][j] = v
            }
        }
        return d[a.length][b.length]
    }
}
