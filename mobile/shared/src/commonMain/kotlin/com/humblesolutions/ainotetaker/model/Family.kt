package com.humblesolutions.ainotetaker.model

/**
 * An adult living in the same household as a registered member.
 *
 * WHY ONLY THE ADULTS. The household size is a canvassing number — how many
 * people live here — but the people worth *contacting* are the ones who can
 * vote, and asking a booth worker to type a name and number for a six year old
 * wastes their time at somebody's front door. So the form asks for the total,
 * then how many of those are 18 or over, then only those.
 *
 * THE PHONE IS OPTIONAL AND IS NOT A LOGIN. These people are not users: no Auth
 * account, no claims, no `users/{uid}` document. The number is a way to reach a
 * household, and plenty of adults in one share a single handset — which is
 * exactly why it must NOT go through the one-number-one-person rule that
 * governs a member's own phone.
 */
data class FamilyAdult(
    val name: String = "",
    val phone: String = "",
    /**
     * How this person is related to the member, or null when nobody said.
     *
     * NULL IS "NOT ASKED", NOT "OTHER". Records written before this field
     * existed, and drafts saved on a device that has not been updated, have no
     * relation — reading them as [FamilyRelation.OTHER] would invent an answer
     * somebody never gave. The booth-member form on iOS requires one for every
     * adult it saves; everything older simply carries none.
     */
    val relation: FamilyRelation? = null,
    /**
     * What "Other" actually means, when [relation] is [FamilyRelation.OTHER].
     * Null the rest of the time — this is the answer to the one case the fixed
     * list could not name, not a second description of a relation that already
     * has one.
     */
    val relationOther: String? = null,
    /**
     * WHO THIS NUMBER ALREADY BELONGS TO, when it is somebody in the
     * registrar's own team — their uid and the name the app knows them by.
     *
     * KEPT BESIDE [name], NEVER INSTEAD OF IT. The name the worker typed is
     * what the family said at the door; the registered name is what the app
     * already holds. When the two differ ("Preeti" typed, "Harpreet Kaur"
     * registered) both are worth having — it is how somebody reading the
     * record later tells a nickname from a shared handset. Null when the
     * number matched nobody, or was never checked.
     */
    val registeredUid: String? = null,
    val registeredName: String? = null,
    /**
     * Anything the worker wants to remember about this person — "works nights,
     * call after 8", "votes at the old booth". Free text, optional, up to
     * [NOTES_MAX]. The one thing on the member's own row that IS typed.
     */
    val notes: String? = null,
) {
    /**
     * The member being registered, as one of their own household's adults —
     * filled from the form and kept in step with it, never typed. See
     * `FormValues.familyAdults`.
     */
    val isSelf: Boolean get() = relation == FamilyRelation.ONESELF

    /**
     * The number is known to the app under a different name from the one
     * typed. Case and spacing do not count as different.
     */
    val registeredUnderAnotherName: Boolean
        get() = registeredName != null &&
            registeredName.trim().lowercase() != name.trim().lowercase()

    /*
     * NARROW COPIES FOR SWIFT, which gets no default arguments on a Kotlin
     * constructor — an edit written as a fresh `FamilyAdult(...)` there has to
     * name every property, and the day one is added it silently drops it.
     * These change one thing and keep everything else, [registeredName]
     * included.
     */
    fun withName(name: String): FamilyAdult = copy(name = name)
    fun withPhone(phone: String): FamilyAdult = copy(phone = phone)
    fun withRelation(relation: FamilyRelation?): FamilyAdult = copy(relation = relation)
    fun withRelationOther(relationOther: String?): FamilyAdult = copy(relationOther = relationOther)
    fun withNotes(notes: String?): FamilyAdult = copy(notes = notes)

    companion object {
        /** A few lines, not an essay — it is read on a phone, in a list. */
        const val NOTES_MAX = 200
    }

    /**
     * A RELATION ALONE IS NOT A PERSON. What identifies an adult is the name and
     * the number; "Mother" with neither is a picked dropdown, not somebody a
     * booth worker can knock on the door of. Counting it would also leave a row
     * that Android — which has no relation control — could never empty.
     */
    fun isBlank(): Boolean = name.isBlank() && phone.isBlank()
}

/**
 * How a household adult is related to the member being registered.
 *
 * AN ENUM, STORED BY [id]. The label a worker reads is translated per language
 * (`registration.relation.<id>`), so what is written to Firestore is the stable
 * id and never the words — a record saved in Punjabi reads back correctly in
 * English, and renaming a label later touches no data.
 *
 * GENDERED ON PURPOSE. "Spouse" is the tidy word, but the person at the door
 * says wife or husband, and a list a booth worker has to translate in their head
 * is a list they stop reading. Each pairing therefore has both halves.
 *
 * ORDER IS THE ORDER THEY ARE SHOWN — the people most households have first,
 * the extended family after, [OTHER] last as the way out for anybody not listed.
 */
enum class FamilyRelation(val id: String) {
    /**
     * The member themselves — the first adult of their own household. Never
     * offered in the list (see [pickable]): it is set by the form, on the one
     * row it fills in from the member's own details. Stored as "self".
     *
     * NOT NAMED `SELF`, and that is load-bearing. Kotlin hands each entry to
     * iOS as a class property under its own name, and `SELF` became `+self` —
     * which overrides the method every Objective-C class answers with itself.
     * The runtime asks a class for `self` while loading it; this one answered
     * by loading itself again, round and round, until the thread's stack ran
     * out: an EXC_BAD_ACCESS the first time any iPhone read a family row.
     * See `no_entry_is_named_after_something_every_objective_c_class_has`.
     */
    ONESELF("self"),
    WIFE("wife"),
    HUSBAND("husband"),
    FATHER("father"),
    MOTHER("mother"),
    SON("son"),
    DAUGHTER("daughter"),
    BROTHER("brother"),
    SISTER("sister"),
    DAUGHTER_IN_LAW("daughterInLaw"),
    SON_IN_LAW("sonInLaw"),
    FATHER_IN_LAW("fatherInLaw"),
    MOTHER_IN_LAW("motherInLaw"),
    BROTHER_IN_LAW("brotherInLaw"),
    SISTER_IN_LAW("sisterInLaw"),
    GRANDFATHER("grandfather"),
    GRANDMOTHER("grandmother"),
    GRANDSON("grandson"),
    GRANDDAUGHTER("granddaughter"),
    UNCLE("uncle"),
    AUNT("aunt"),
    NEPHEW("nephew"),
    NIECE("niece"),
    COUSIN("cousin"),
    OTHER("other");

    /** The string-table key for this relation's label. */
    val labelKey: String get() = "$LABEL_PREFIX$id"

    companion object {
        const val LABEL_PREFIX = "registration.relation."

        /** What a worker can pick for somebody else in the household — every relation but [ONESELF]. */
        val pickable: List<FamilyRelation> get() = entries.filter { it != ONESELF }

        /**
         * Null for anything unrecognised — a value from a newer app version, or
         * a hand-edited document. Guessing would put a wrong relation on a real
         * family's record; a blank is honest and the worker can pick again.
         */
        fun fromId(raw: String?): FamilyRelation? {
            val id = raw?.trim().orEmpty()
            if (id.isEmpty()) return null
            return entries.firstOrNull { it.id == id }
        }
    }
}

/**
 * Somebody already in the app, credited for bringing a member in.
 *
 * SNAPSHOTTED, NOT REFERENCED — the same decision as a meeting's host, for the
 * same reason. The users rule lets you read yourself, your descendants and
 * your own management chain; a referrer can be outside all three by the time
 * anybody opens the record. Storing the uid alone would render as a blank row
 * to precisely the people most likely to be reading it.
 *
 * The uid is kept as well, so a later feature can still join on it where the
 * reader is allowed to.
 */
data class PersonRef(
    val uid: String = "",
    val name: String = "",
    val phone: String = "",
) {
    /**
     * Nobody at all. A name with no [uid] is NOT blank: somebody the app does
     * not have, typed in as "Not found? Use …" — kept as written.
     */
    fun isBlank(): Boolean = uid.isBlank() && name.isBlank()

    /** "Ansh Bajaj · 98765 43210" — what the picker and the record both show. */
    fun display(): String =
        listOf(name, phone).filter { it.isNotBlank() }.joinToString("  ·  ")
}

/**
 * The household's counts, as they are typed.
 */
object FamilyCounts {
    /**
     * The "how many are 18 or older" box after a keystroke — the new text when
     * it can stand, the old text when it cannot, so a refused key does nothing.
     *
     * NEVER MORE THAN THE HOUSEHOLD. With [familySize] typed, the adults can
     * be at most that many — a household of 4 takes 0 to 4, since everybody
     * in it may be grown up. Blank family size: only the length is held, and
     * the save's own check catches the rest once it is typed.
     */
    fun typedAdults(old: String, new: String, familySize: Int?): String {
        val digits = new.filter(Char::isDigit)
        if (digits.length > 2) return old
        if (digits.isEmpty() || familySize == null) return digits
        return if (digits.toInt() <= familySize) digits else old
    }

    /** More adults than the rule allows — shown under the box the moment it is true. */
    fun adultsTooMany(familySize: Int?, adults: Int?): Boolean =
        familySize != null && adults != null && adults > familySize
}
