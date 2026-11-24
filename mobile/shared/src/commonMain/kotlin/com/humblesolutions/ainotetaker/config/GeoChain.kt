package com.humblesolutions.ainotetaker.config

import com.humblesolutions.ainotetaker.model.GeoLink
import com.humblesolutions.ainotetaker.model.Role
import com.humblesolutions.ainotetaker.model.Scope

/**
 * The geography chain, and how much of it each role must carry.
 *
 * This is the client's half of a contract whose other half is
 * `scopeCompleteFor()` in firestore.rules. If they disagree, the form lets
 * somebody fill in a registration that the rules then reject with a flat
 * permission-denied and no explanation.
 *
 *   zoneLeader   zone
 *   wardLeader   zone → area → ward
 *   boothLeader  → locality
 *   boothMember  → locality
 *
 * BOOTHS ARE LOADED — 181 of them — but stay OPTIONAL. 34 rows of the client's
 * spreadsheet have no ward, so 23 booths could not be placed at all, and the
 * roll the client imports has no booth column, so a member auto-filled from
 * their EPIC number has no booth to inherit. Requiring one would refuse
 * registrations nobody can complete.
 */
object GeoChain {

    /** Root → leaf. The order is the cascade: each link filters the next. */
    val links: List<GeoLink> = GeoLink.entries

    private val requiredDepth: Map<Role, Int> = mapOf(
        Role.SUPER_ADMIN to 0,
        Role.ADMIN to 0,
        Role.ZONE_LEADER to 1,
        Role.WARD_LEADER to 3,
        Role.BOOTH_LEADER to 4,
        Role.YOUTH_LEADER to 4,
        Role.BOOTH_MEMBER to 4,
    )

    fun requiredDepth(role: Role?): Int = role?.let { requiredDepth[it] } ?: 0

    /** The links this role is obliged to fill. */
    fun requiredLinks(role: Role?): List<GeoLink> = links.take(requiredDepth(role))

    /**
     * Validation errors as link → message key.
     *
     * A link the CREATOR is pinned to is never the user's to fill, so it is
     * never their error either — it is merged in from their own scope first.
     */
    fun validate(role: Role?, scope: Scope): Map<GeoLink, String> =
        requiredLinks(role)
            .filter { scope[it].isNullOrBlank() }
            .associateWith { "registration.v.required" }

    /**
     * Clears every link below the one that changed.
     *
     * Choosing a different ward has to invalidate the locality and booth
     * beneath it, or the form submits a booth that is not in the chosen ward —
     * which the rules cannot catch, because they check containment against the
     * CREATOR's scope, not internal consistency of the value.
     */
    fun set(scope: Scope, link: GeoLink, value: String?): Scope {
        var next = scope.with(link, value)
        for (stale in links.drop(links.indexOf(link) + 1)) next = next.with(stale, null)
        return next
    }
}
