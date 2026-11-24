package com.humblesolutions.ainotetaker.model

/**
 * Where in the geography somebody sits.
 *
 * Every link is nullable because the chain STOPS at different depths for
 * different roles — a Zone Leader has only a zone, a Booth Member has a
 * locality — and because the roll's own rows carry a partial chain too.
 *
 * `subLocalityId` is still here and is never populated. It left the cascade
 * when booths arrived (the client's geography has no sub-localities), but the
 * Firestore rules still accept one, so dropping the field from the model would
 * make a future re-introduction a migration instead of a line of code.
 */
data class Scope(
    val zoneId: String? = null,
    val areaId: String? = null,
    val wardId: String? = null,
    val localityId: String? = null,
    val subLocalityId: String? = null,
    val boothId: String? = null,
) {
    /** Ordered ids, root to leaf — the `scopePath` the server derives too. */
    fun path(): List<String> =
        listOfNotNull(zoneId, areaId, wardId, localityId, subLocalityId, boothId)

    fun isEmpty(): Boolean = path().isEmpty()

    operator fun get(link: GeoLink): String? = when (link) {
        GeoLink.ZONE -> zoneId
        GeoLink.AREA -> areaId
        GeoLink.WARD -> wardId
        GeoLink.LOCALITY -> localityId
        GeoLink.BOOTH -> boothId
    }

    fun with(link: GeoLink, value: String?): Scope = when (link) {
        GeoLink.ZONE -> copy(zoneId = value)
        GeoLink.AREA -> copy(areaId = value)
        GeoLink.WARD -> copy(wardId = value)
        GeoLink.LOCALITY -> copy(localityId = value)
        GeoLink.BOOTH -> copy(boothId = value)
    }
}
