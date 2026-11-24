package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.model.Role
import com.humblesolutions.ainotetaker.model.creatableRoles
import com.humblesolutions.ainotetaker.model.isAdminOrAbove
import com.humblesolutions.ainotetaker.model.managesPeople
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hierarchy, which the Firestore rules enforce independently.
 *
 * These numbers are a contract with the backend — `level` is compared against a
 * custom claim — so a change that looks cosmetic here locks people out of their
 * own data. Hence asserting the literal values rather than only the ordering.
 */
class RolesTest {

    @Test
    fun levels_are_the_contract_with_the_rules() {
        assertEquals(1, Role.SUPER_ADMIN.level)
        assertEquals(2, Role.ADMIN.level)
        assertEquals(3, Role.ZONE_LEADER.level)
        assertEquals(4, Role.WARD_LEADER.level)
        assertEquals(5, Role.BOOTH_LEADER.level)
        assertEquals(6, Role.BOOTH_MEMBER.level)
    }

    @Test
    fun admin_or_above_is_exactly_the_two_office_roles() {
        assertTrue(Role.SUPER_ADMIN.isAdminOrAbove())
        assertTrue(Role.ADMIN.isAdminOrAbove())
        assertFalse(Role.ZONE_LEADER.isAdminOrAbove())
        assertFalse(null.isAdminOrAbove())
    }

    @Test
    fun a_booth_member_manages_nobody() {
        assertFalse(Role.BOOTH_MEMBER.managesPeople())
        assertTrue(Role.BOOTH_LEADER.managesPeople())
    }

    @Test
    fun anybody_may_register_strictly_below_themselves() {
        // An Admin reaches a Booth Member directly, without stepping through.
        assertEquals(
            listOf(Role.ZONE_LEADER, Role.WARD_LEADER, Role.BOOTH_LEADER, Role.YOUTH_LEADER, Role.BOOTH_MEMBER),
            Role.ADMIN.creatableRoles(),
        )
        // Youth Leader first: same level, listed just above Locality Members.
        // Whether a person is OFFERED one is Feature access — see YouthLeaderTest.
        assertEquals(listOf(Role.YOUTH_LEADER, Role.BOOTH_MEMBER), Role.BOOTH_LEADER.creatableRoles())
    }

    @Test
    fun nobody_creates_a_super_admin_and_a_leaf_creates_nobody() {
        assertTrue(Role.entries.none { Role.SUPER_ADMIN in it.creatableRoles() })
        assertEquals(emptyList(), Role.BOOTH_MEMBER.creatableRoles())
        assertEquals(emptyList(), Role.YOUTH_LEADER.creatableRoles())
    }

    @Test
    fun every_role_signs_in_the_same_way() {
        /*
         * THE ASSERTION IS THAT THERE IS NOTHING TO ASSERT. Roles used to
         * carry an `auth` property splitting office roles (email) from field
         * roles (phone OTP), and a lot of code branched on it. One door means
         * the property is gone, and this test stands in its place so that
         * re-introducing a per-role sign-in method is a deliberate act rather
         * than something that quietly reappears.
         */
        // eight with the CRM's Contributor, below everyone.
        assertEquals(8, Role.entries.size)
        // A Youth Leader shares the Locality Member's level — see `Role.YOUTH_LEADER`.
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 6, 7), Role.entries.map { it.level })
    }

    // the CRM's own role names, as GET /api/me reports them.
    @Test
    fun crm_roles_map_to_what_the_app_shows() {
        assertEquals(Role.SUPER_ADMIN, Role.fromCrmRole("super_admin"))
        assertEquals(Role.ADMIN, Role.fromCrmRole("admin"))
        assertEquals(Role.CONTRIBUTOR, Role.fromCrmRole("contributor"))
        assertEquals(null, Role.fromCrmRole("superAdmin"))
        assertEquals(null, Role.fromCrmRole(null))
        assertFalse(Role.CONTRIBUTOR.isAdminOrAbove())
        assertFalse(Role.CONTRIBUTOR.managesPeople())
        assertTrue(Role.CONTRIBUTOR.signsIn)
    }
}
