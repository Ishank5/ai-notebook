package com.humblesolutions.ainotetaker.model

/**
 * A event the Super Admin fixed, and the people asked to come.
 *
 * INVITE-ONLY, AND THAT IS ENFORCED IN THE RULES, not just hidden in the UI.
 * The document carries [invitedUids] and the read rule is
 * `myUid() in resource.data.invitedUids` — so a event a person was not asked
 * to cannot be read by them at all, not merely filtered out of their feed. The
 * feed query is the same clause (`invitedUids array-contains me`), which is
 * what makes the query legal rather than refused.
 *
 * THE CREATOR IS ALWAYS AN INVITEE. Otherwise the one person who fixed the
 * event is the one person whose feed query cannot return it, and the app
 * would need a second query and a second rule clause to show somebody their own
 * work. [EventDraft.invitedWithCreator] is the single place that holds.
 *
 * THE HOST IS SNAPSHOTTED, never referenced. The `users` read rule permits only
 * one's own branch, so a booth member in ward 3 cannot read a booth member in
 * ward 7 — and the invitee list deliberately crosses branches. A live lookup of
 * the host would hand most of the room a permission denial and an empty card.
 * Name, role, phone and pin are copied on to the document at creation; only
 * [scope] stays as ids, because `geoUnits` is readable by everybody and carries
 * all three languages, so each reader resolves the place in their own.
 */
data class Event(
    val id: String = "",

    val title: String = "",
    /** Epoch millis. The platform builds it — only the platform has a timezone. */
    val startAt: Long = 0L,
    val notes: String = "",

    val venueKind: VenueKind = VenueKind.CUSTOM,

    // --- when venueKind == MEMBER: the host, copied not referenced ----------
    val hostId: String? = null,
    val hostName: String? = null,
    val hostRole: Role? = null,
    val hostPhone: String? = null,
    val location: GeoPointValue? = null,
    /** Ids only — each reader resolves the ward and locality in their language. */
    val scope: Scope = Scope(),

    // --- when venueKind == CUSTOM: somewhere that is nobody's doorstep ------
    val venueName: String? = null,
    val venueAddress: String? = null,
    /*
     * A place searched on Google, kept so the venue can be reopened or
     * re-resolved later. The PIN ITSELF GOES IN [location], NOT IN A SECOND
     * FIELD: mapsQuery, hasCoarsePin and every card that opens directions
     * already read that one, and a parallel venueLocation would mean each of
     * them growing a branch that somebody eventually forgets to write.
     */
    val venuePlaceId: String? = null,

    /*
     * WHO TO ASK, AND EXACTLY WHERE (Ansh, 10 Oct 2026) — stored together as
     * one `details` key, because the rules cap an event at twenty keys.
     */
    /** Who it came through — free text, as the office says it. Empty when not said. */
    val reference: String = "",
    /** Numbers to call about it, each with a name when one was given. */
    val contacts: List<EventContact> = emptyList(),
    /** The house, floor, landmark — what a searched address does not say. */
    val exactAddress: String = "",

    /** Everyone asked, including the creator. Never empty on a stored event. */
    val invitedUids: List<String> = emptyList(),

    /** How much it matters — Normal unless somebody said otherwise. */
    val importance: EventImportance = EventImportance.NORMAL,
    /**
     * Photos of the invitation — up to [INVITATION_PHOTOS_MAX], each a
     * download URL in Cloud Storage, in the order they were added (Ansh,
     * 10 Oct 2026: more than one). Uploaded while the wizard is still open, so
     * they are already URLs by the time the event is saved. An event saved
     * before this has its one photo here too (see the readers).
     */
    val invitationUrls: List<String> = emptyList(),
    /** A spoken note — a download URL in Cloud Storage, or null when there is none. */
    val voiceNoteUrl: String? = null,
    /** Length of [voiceNoteUrl], for the player to show before it loads. */
    val voiceNoteSeconds: Int? = null,

    val status: EventStatus = EventStatus.SCHEDULED,
    val cancelledAt: Long? = null,
    val cancelledBy: String? = null,

    val createdBy: String = "",
    val createdByName: String = "",
    val createdAt: Long? = null,

    /**
     * Not stored. True while the local write has not reached the server, so a
     * card can say "saved" honestly and still show a pending badge.
     */
    val pending: Boolean = false,
) {

    /**
     * What this event is RIGHT NOW — derived, never stored.
     *
     * The document holds two states only. Storing "past" would need something
     * to flip it at the moment it became true: a scheduled function, and until
     * it ran a event that finished an hour ago would still be advertised at
     * the top of everybody's home screen. Deriving it is correct on every
     * render for free.
     *
     * Cancelled wins over the clock. A cancelled event whose time has passed
     * is cancelled, not past — why it did not happen is the point.
     */
    fun displayStatus(nowMillis: Long): EventDisplay = when {
        status == EventStatus.CANCELLED -> EventDisplay.CANCELLED
        nowMillis < startAt -> EventDisplay.UPCOMING
        nowMillis <= startAt + ACTIVE_WINDOW_MS -> EventDisplay.ACTIVE
        else -> EventDisplay.PAST
    }

    /**
     * `lat,lng` for a maps link, or null when there is no pin to open.
     *
     * Works for both venue kinds because [location] holds whichever pin
     * applies — the host's doorstep, or the place picked off the map.
     */
    val mapsQuery: String? get() = location?.let { "${it.latitude},${it.longitude}" }

    /**
     * Over 100 m came off a cell tower rather than a satellite.
     *
     * Somebody about to cross town needs to know that BEFORE they set off, not
     * when they are standing in the wrong street.
     */
    val hasCoarsePin: Boolean get() = (location?.accuracy ?: 0.0) > COARSE_PIN_METRES

    /**
     * Where it is, in one line: the place searched, else the exact address
     * typed. Empty for an event at a member's place, which names the host.
     */
    val placeLine: String get() = venueName?.trim()?.takeIf { it.isNotEmpty() } ?: exactAddress.trim()

    /** How many were asked. The creator counts — they are going too. */
    val invitedCount: Int get() = invitedUids.size

    fun invites(uid: String?): Boolean = uid != null && uid in invitedUids

    companion object {
        /**
         * How long a event reads as "happening now" after it starts.
         *
         * ASSUMPTION, DELIBERATELY VISIBLE: the form asks for a start and no
         * end, because an end time is a second thing to get wrong for a
         * gathering at somebody's house. Two hours is what one of these runs
         * to. If that is wrong it is this one line; if an explicit end is
         * wanted later it is one more field and one branch in [displayStatus].
         */
        const val ACTIVE_WINDOW_MS: Long = 2 * 60 * 60 * 1000

        const val COARSE_PIN_METRES: Double = 100.0

        const val TITLE_MAX = 120
        const val NOTES_MAX = 500
        const val VENUE_NAME_MAX = 120
        const val VENUE_ADDRESS_MAX = 300
        const val REFERENCE_MAX = 120
        const val EXACT_ADDRESS_MAX = 300
        /** Numbers to call — enough for the organisers, few enough to read. */
        const val CONTACTS_MAX = 5
        const val CONTACT_NAME_MAX = 60

        /**
         * AT A MEMBER'S PLACE IS SWITCHED OFF (Ansh, 10 Oct 2026): every event
         * is placed by searching an address. The code for it stays — this one
         * switch brings the tab back.
         */
        const val MEMBER_VENUE_OFFERED = false

        /**
         * A ceiling on the guest list.
         *
         * `array-contains` has no size limit worth worrying about, but a
         * document does, and an unbounded list is how one event quietly
         * becomes a 1 MB document that every invitee downloads. Two hundred is
         * far past any gathering at a house and far short of the limit.
         */
        const val INVITE_MAX = 200

        /** The longest voice note the wizard records. Two minutes. */
        const val VOICE_NOTE_MAX_SECONDS = 120

        /**
         * An invitation photo smaller than this goes up as taken, so the print
         * on it stays sharp; anything else is compressed below it first. The
         * storage rules refuse an image of this size or more.
         */
        const val INVITATION_MAX_BYTES: Long = 10L * 1024 * 1024

        /** Photos of the invitation one event may carry. */
        const val INVITATION_PHOTOS_MAX = 5
    }
}

/**
 * Somebody to call about an event. [phone] is stored as +91 and ten digits;
 * [name] may be empty.
 */
data class EventContact(val name: String = "", val phone: String = "") {
    fun isBlank(): Boolean = name.isBlank() && phone.isBlank()
}

/**
 * How much an event matters, as the person fixing it sees it.
 *
 * A LEVEL, NOT A FLAG. Three steps is what the office asked for, and an enum
 * leaves room for a fourth without a second field. Unknown ids read as NORMAL,
 * so an older client's document still renders.
 */
enum class EventImportance(val id: String) {
    NORMAL("normal"),
    IMPORTANT("important"),
    VERY_IMPORTANT("veryImportant");

    val labelKey: String get() = "events.importance.$id"

    companion object {
        fun fromId(id: String?): EventImportance = entries.firstOrNull { it.id == id } ?: NORMAL
    }
}

enum class VenueKind(val id: String) {
    /** At somebody's place — their stored pin is the address. */
    MEMBER("member"),

    /** Somewhere typed by hand: a hall, an office, a ground. */
    CUSTOM("custom");

    companion object {
        /** An unknown value reads as CUSTOM: a typed venue needs no pin to render. */
        fun fromId(id: String?): VenueKind = entries.firstOrNull { it.id == id } ?: CUSTOM
    }
}

enum class EventStatus(val id: String) {
    SCHEDULED("scheduled"),
    CANCELLED("cancelled");

    companion object {
        fun fromId(id: String?): EventStatus =
            entries.firstOrNull { it.id == id } ?: SCHEDULED
    }
}

/** What a reader sees. Derived from [Event.displayStatus], never stored. */
enum class EventDisplay(val id: String) {
    UPCOMING("upcoming"),
    ACTIVE("active"),
    PAST("past"),
    CANCELLED("cancelled");

    /** Worth the top of the home screen: happening now, or still to come. */
    val isCurrent: Boolean get() = this == ACTIVE || this == UPCOMING
}
