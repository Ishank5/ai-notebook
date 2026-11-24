package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.config.GeoChain
import com.humblesolutions.ainotetaker.model.GeoLink
import com.humblesolutions.ainotetaker.model.Role
import com.humblesolutions.ainotetaker.model.Scope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cascade, and the depth each role must reach.
 *
 * MIRRORS `scopeCompleteFor()` in firestore.rules. If the two disagree, the
 * form lets somebody fill in a registration the rules then reject with a flat
 * permission-denied and no explanation.
 */
class GeoChainTest {

    @Test
    fun the_chain_is_five_links_and_booth_is_not_a_child_of_locality() {
        assertEquals(
            listOf(GeoLink.ZONE, GeoLink.AREA, GeoLink.WARD, GeoLink.LOCALITY, GeoLink.BOOTH),
            GeoChain.links,
        )
        // 95 of 181 booths serve more than one locality, so they are filtered
        // by membership rather than by parentage.
        assertEquals("localityIds", GeoLink.BOOTH.filterField)
        assertEquals(null, GeoLink.LOCALITY.filterField)
    }

    @Test
    fun required_depth_matches_the_rules() {
        assertEquals(0, GeoChain.requiredDepth(Role.ADMIN))
        assertEquals(1, GeoChain.requiredDepth(Role.ZONE_LEADER))
        assertEquals(3, GeoChain.requiredDepth(Role.WARD_LEADER))
        assertEquals(4, GeoChain.requiredDepth(Role.BOOTH_LEADER))
        assertEquals(4, GeoChain.requiredDepth(Role.BOOTH_MEMBER))
    }

    @Test
    fun booth_is_optional_even_though_booths_now_exist() {
        // 23 booths could not be placed for want of a ward, and the client's
        // roll has no booth column — requiring one would refuse registrations
        // nobody can complete.
        assertTrue(GeoLink.BOOTH !in GeoChain.requiredLinks(Role.BOOTH_MEMBER))
    }

    @Test
    fun a_ward_leader_must_carry_zone_area_and_ward() {
        val errors = GeoChain.validate(Role.WARD_LEADER, Scope(zoneId = "z-1"))
        assertEquals(setOf(GeoLink.AREA, GeoLink.WARD), errors.keys)
    }

    @Test
    fun changing_a_link_clears_everything_below_it() {
        val full = Scope(
            zoneId = "z", areaId = "a", wardId = "w",
            localityId = "l", boothId = "b",
        )
        val moved = GeoChain.set(full, GeoLink.WARD, "w-2")

        // Or the form submits a booth that is not in the chosen ward — which
        // the rules cannot catch, because they check containment against the
        // CREATOR's scope, not internal consistency of the value.
        assertEquals("w-2", moved.wardId)
        assertEquals(null, moved.localityId)
        assertEquals(null, moved.boothId)
        assertEquals("a", moved.areaId)
    }

    @Test
    fun scope_path_is_root_to_leaf_and_skips_what_is_missing() {
        val scope = Scope(zoneId = "z", areaId = "a", wardId = "w", boothId = "b")
        assertEquals(listOf("z", "a", "w", "b"), scope.path())
    }
}
