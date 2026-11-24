package com.humblesolutions.ainotetaker.config

import com.humblesolutions.ainotetaker.model.GeoLink
import com.humblesolutions.ainotetaker.model.GeoUnit
import com.humblesolutions.ainotetaker.model.Role
import com.humblesolutions.ainotetaker.model.Scope

/**
 * Where a Team Leader works — an AREA, and the ward that is the same place
 * under its other name — asked the way the Locality Member card asks.
 *
 * A TEAM LEADER IS PLACED IN AN AREA, NOT A LOCALITY. Their record holds zone,
 * area and ward (`GeoChain.requiredDepth` is 3), and everybody below them is
 * found through that ward. Saving a locality on them would narrow them to one
 * street, and a booth means nothing for a leader of a whole area.
 *
 * SO A LOCALITY IS A WAY TO FIND THE AREA, never an answer kept. A worker
 * often knows a street in somebody's area before the area's own name: they
 * pick the street, its area and ward fill themselves (`ancestors` on the
 * locality spells them out), and the street itself is not stored. Or they pick
 * the area directly.
 *
 * Every area in this data has exactly one ward ("Ward No. 3" IS "Ranjit
 * Avenue"), so a chosen area settles its ward; an area with several would
 * leave the ward to be picked.
 */
object AreaFirst {

    /** The roles whose card asks for an area this way. */
    fun applies(role: Role?): Boolean = role == Role.WARD_LEADER

    /**
     * The zone, area and ward a locality lies in, or null when the locality
     * is outside what the registrar is pinned to ([locked]) — a zone leader
     * cannot place a Team Leader in another zone, and the server would refuse
     * it. The locality and booth are never part of the answer.
     */
    fun fromLocality(locality: GeoUnit, locked: Scope): Scope? {
        val zone = locality.ancestors.getOrNull(0)
        val area = locality.ancestors.getOrNull(1)
        val ward = locality.ancestors.getOrNull(2)
        if (zone.isNullOrBlank() || area.isNullOrBlank() || ward.isNullOrBlank()) return null
        val scope = Scope(zoneId = zone, areaId = area, wardId = ward)
        return scope.takeIf { agrees(it, locked) }
    }

    /**
     * An area chosen: its zone, itself, and its ward when it has only one.
     * Null when it is outside [locked].
     */
    fun fromArea(area: GeoUnit, wards: List<GeoUnit>, locked: Scope): Scope? {
        val zone = area.ancestors.firstOrNull() ?: area.parentId
        if (zone.isNullOrBlank()) return null
        val ward = wards.filter { it.parentId == area.id }.singleOrNull()?.id
        val scope = Scope(zoneId = zone, areaId = area.id, wardId = ward)
        return scope.takeIf { agrees(it, locked) }
    }

    /** The links that are the answer — the rest of a scope is dropped. */
    val links: List<GeoLink> = listOf(GeoLink.ZONE, GeoLink.AREA, GeoLink.WARD)

    /** Only zone, area and ward survive; a stray locality or booth is cleared. */
    fun trimmed(scope: Scope): Scope = Scope(zoneId = scope.zoneId, areaId = scope.areaId, wardId = scope.wardId)

    /** Every link the registrar is pinned to is the same in [scope]. */
    private fun agrees(scope: Scope, locked: Scope): Boolean =
        GeoChain.links.all { link -> locked[link].let { it == null || it == scope[link] } }
}
