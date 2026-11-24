package com.humblesolutions.ainotetaker.model

/**
 * A person on the team.
 *
 * Mirrors the `users` document the Firestore rules validate, field for field.
 * Anything the SERVER owns — `level`, `status`, `searchTokens`, `scopePath` —
 * is read-only here: a client that could write them could promote itself.
 */
data class User(
    val uid: String = "",
    val role: Role? = null,
    val status: UserStatus = UserStatus.PENDING_PROVISIONING,

    val parentId: String? = null,
    val createdBy: String? = null,
    /** Credits a registration to somebody else while `createdBy` stays the audit truth. */
    val attributedTo: String? = null,
    /** Root → leaf, EXCLUDING this person. Must equal the creator's own plus the creator. */
    val ancestors: List<String> = emptyList(),

    val name: String = "",
    val nameLower: String = "",
    val fatherName: String? = null,
    /** Always `+91` and ten digits — it is the login identifier. */
    val phone: String = "",
    /** The last four, stored because no index can prefix-match the END of a string. */
    val phoneLast4: String? = null,
    val email: String? = null,
    val gender: Gender? = null,
    val dob: Long? = null,
    val epicNo: String? = null,

    val scope: Scope = Scope(),
    val address: Address? = null,
    val location: GeoPointValue? = null,
    /**
     * When [location] was taken — NOT when this record was written.
     *
     * The server-side attendance trigger reads this to date the dot at the
     * doorstep rather than at the moment the queue drained. A plain number
     * rather than a timestamp because it has to cross a callable, and only
     * JSON-shaped values can.
     */
    val locationCapturedAtMillis: Long? = null,
    /**
     * How [location] was got: the phone's fix at the door, or a pin placed
     * from the address. Rides with the pin, so the attendance trigger can
     * copy it onto the dot — the map has to be able to say which is which.
     */
    val locationSource: LocationSource? = null,

    /**
     * Everything the ROLE'S SCHEMA asks for, by its own field key.
     *
     * A map rather than twenty nullable columns, because these fields differ
     * per role and most are null for any given person: a Booth Member has a
     * caste and a religion, a Zone Leader has an impact area and a category,
     * and neither has the other's. The schema owns the label and the type; this
     * just carries the answer, under exactly the key Firestore stores.
     */
    val details: Map<String, String> = emptyMap(),

    /**
     * Kept typed because the DASHBOARD reads it — the target ring needs a
     * number, and digging it out of a string map on every render is the kind of
     * thing that is fine until it is not.
     */
    val targetNo: Int? = null,

    /** Set when this person was registered from a roll row (P3-4). */
    val roughMemberId: String? = null,
    /**
     * What they sign in with. Unique across the organisation.
     *
     * STORED FOR DISPLAY, NOT FOR AUTHENTICATION. The authority is the
     * Firebase identity; this copy exists so a leader can tell a worker what
     * their username is without an Admin SDK call.
     */
    val username: String = "",

    val photoUrl: String? = null,

    /**
     * The adults of the household, by name and number.
     *
     * NOT USERS. No Auth account, no claims, no document of their own — these
     * are contacts on a member's record, and several of them routinely share
     * one handset, so none of the one-number-one-person rules apply.
     */
    val familyAdults: List<FamilyAdult> = emptyList(),

    /** Who brought them in, snapshotted so it renders for readers who cannot
     *  read that person's own document. */
    val referredBy: PersonRef? = null,

    val createdAt: Long? = null,
    val updatedAt: Long? = null,

    /** Why provisioning failed, when it did — shown rather than swallowed. */
    val provisioningError: String? = null,

    /**
     * Not stored. True while the local write has not reached the server, so
     * the row can honestly say "saved" and still show a pending badge.
     */
    val pending: Boolean = false,
) {
    companion object {
        /**
         * A leader as `listReferrers` describes one — an id, a name and a role,
         * nothing else. For Swift, which cannot skip this class's other fields.
         */
        fun forReferrer(uid: String, name: String, role: Role?): User = User(uid = uid, name = name, role = role)

        /**
         * a CRM login as `GET /api/me` describes it: an id, a
         * name, an email, a role and whether it may be used. Both phones build
         * the signed-in person this way; Swift cannot skip this class's other
         * fields either.
         */
        fun fromCrm(uid: String, name: String, email: String?, role: Role?, status: UserStatus): User =
            User(uid = uid, role = role, status = status, name = name, nameLower = name.lowercase(), email = email)
    }

    val level: Int get() = role?.level ?: 99
    val isActive: Boolean get() = status == UserStatus.ACTIVE

    /** A schema field's answer, or null when it was left blank. */
    fun detail(key: String): String? = details[key]?.takeIf { it.isNotBlank() }
    val initials: String
        get() = name.trim().split(" ").filter { it.isNotBlank() }
            .take(2).joinToString("") { it.first().uppercase() }
            .ifBlank { "?" }
}

enum class UserStatus(val id: String) {
    PENDING_PROVISIONING("pendingProvisioning"),
    ACTIVE("active"),
    SUSPENDED("suspended"),
    PROVISIONING_FAILED("provisioningFailed");

    companion object {
        fun fromId(id: String?): UserStatus =
            entries.firstOrNull { it.id == id } ?: PENDING_PROVISIONING
    }
}

enum class Gender(val id: String) {
    FEMALE("female"), MALE("male"), OTHER("other");

    companion object {
        /**
         * Rolls print a single letter, and the client's export uses M/F. An
         * unrecognised value is null rather than a guess — gender is on the
         * card and a wrong one is worse than a blank.
         */
        fun fromId(raw: String?): Gender? {
            val v = raw?.trim()?.lowercase() ?: return null
            return when {
                v.startsWith("f") -> FEMALE
                v.startsWith("m") -> MALE
                v.startsWith("o") || v.startsWith("t") -> OTHER
                else -> null
            }
        }
    }
}

data class Address(val houseNo: String? = null, val line: String? = null) {
    fun isEmpty() = houseNo.isNullOrBlank() && line.isNullOrBlank()
    fun oneLine(): String = listOfNotNull(
        houseNo?.takeIf { it.isNotBlank() },
        line?.takeIf { it.isNotBlank() },
    ).joinToString(", ")
}

/** A captured location. Kept out of the platform types so `shared` stays pure. */
data class GeoPointValue(val latitude: Double, val longitude: Double, val accuracy: Double? = null)

/**
 * How a registration's pin was got — ONE OF TWO, never both.
 *
 * [GPS] is the phone's own fix, taken at the door. [ADDRESS] is a place found
 * from the address the form already holds and then placed by hand on a map,
 * for when the worker is not standing at the house.
 */
enum class LocationSource(val id: String) {
    GPS("gps"),
    ADDRESS("address"),
    ;

    companion object {
        /** Null for anything else — a record written before this existed has none. */
        fun fromId(id: String?): LocationSource? = entries.firstOrNull { it.id == id }
    }
}
