package com.humblesolutions.ainotetaker.i18n

/**
 * Looking a string up, in the language somebody chose.
 *
 * FALLING BACK TO ENGLISH IS DELIBERATE and never to the key. A screen showing
 * `team.emptyTitle` to a field worker is worse than one showing English: the
 * English is at least a sentence, and a missing Punjabi string is a translation
 * task, not a crash.
 */
object Strings {

    /**
     * TWO TABLES, MERGED. [StringTables] is generated from the web prototype by
     * `scripts/strings-from-web.py` and is overwritten wholesale every time
     * that runs; [EventStrings] holds strings for features built here, which
     * have no source in the web repo to be generated from. Keeping them apart
     * means a regeneration cannot silently delete them.
     *
     * Hand-written wins on a clash, so a key can be corrected here without
     * waiting on the other repo.
     */
    private fun table(language: Language): Map<String, String> = when (language) {
        Language.ENGLISH -> english
        Language.HINDI -> hindi
        Language.PUNJABI -> punjabi
    }

    /*
     * MERGED ONCE PER LANGUAGE, NOT ONCE PER LOOKUP. This used to add the five
     * tables together inside `table()`, so every `tr()` built a fresh map of
     * about a thousand strings — four copies deep — and threw it away. Every
     * label on every screen goes through here, on the main thread: the search
     * results alone asked twice per row, fifty rows, several redraws a
     * keystroke, and the text field froze after each letter while the phone
     * copied maps.
     *
     * Lazy so that a phone set to English never builds the other two.
     */
    // the tables of removed features (events, feature access,
    // hierarchy, personal access, live monitoring, reels, app updates) are gone
    // from these sums; the order of what is left is unchanged.
    private val hindi: Map<String, String> by lazy {
        StringTables.hi + FormStrings.hi +
            RecorderStrings.hi
    }
    private val punjabi: Map<String, String> by lazy {
        StringTables.pa + FormStrings.pa +
            RecorderStrings.pa
    }

    /**
     * The English table, which is also the fallback for a missing translation.
     */
    // NotetakerStrings last, so the AI Notebook name and the planned length win.
    private val english: Map<String, String> =
        StringTables.en + FormStrings.en +
            RecorderStrings.en + MeetingStrings.en + NotetakerStrings.en

    /**
     * One string, with `{{name}}` placeholders filled in.
     *
     * Plurals follow i18next's convention, which the source JSON already uses:
     * a key with a `count` argument looks for `key_one` or `key_other` first.
     * English and Hindi both use "one / other"; Punjabi does too, so a single
     * rule covers all three rather than a plural-rules library for one case.
     */
    fun get(
        key: String,
        language: Language = Language.default,
        args: Map<String, Any?> = emptyMap(),
    ): String {
        val t = table(language)
        val count = args["count"] as? Int

        val resolved = when {
            count == null -> t[key]
            else -> t["${key}_" + if (count == 1) "one" else "other"] ?: t[key]
        } ?: english[key]
            ?: english["${key}_other"]
            ?: return key

        return interpolate(resolved, args)
    }

    private fun interpolate(template: String, args: Map<String, Any?>): String {
        if (args.isEmpty() || !template.contains("{{")) return template
        var out = template
        for ((name, value) in args) {
            out = out.replace("{{$name}}", value?.toString() ?: "")
        }
        return out
    }

    /** True when a key exists at all — used by tests, not by screens. */
    fun has(key: String): Boolean =
        english.containsKey(key) || english.containsKey("${key}_other")
}
