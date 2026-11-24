package com.humblesolutions.ainotetaker.model

/**
 * One row of an imported electoral roll — a voter nobody has registered yet.
 *
 * KEYED BY EPIC NUMBER rather than a generated id, a deliberate deviation from
 * the PRD. It makes the uniqueness Firestore cannot otherwise express free,
 * turns duplicate detection into a failed create rather than two hundred
 * thousand reads, and makes the auto-fill lookup a direct get instead of a
 * query.
 *
 * THE MOST SENSITIVE DATA THE SYSTEM HOLDS: names, ages, house numbers and
 * addresses of tens of thousands of people who have never heard of this app.
 * Visible only to leaders who register people, in the ward they work in.
 */
data class RoughMember(
    val id: String = "",
    val epicNo: String = "",
    val name: String = "",
    val nameLower: String = "",
    val fatherName: String? = null,
    val gender: Gender? = null,
    val age: Int? = null,
    val dob: Long? = null,
    val phone: String? = null,
    val houseNo: String? = null,
    val address: String? = null,
    val serialNo: String? = null,
    val boothNumber: String? = null,

    val zoneId: String? = null,
    val areaId: String? = null,
    val wardId: String? = null,
    val localityId: String? = null,
    val boothId: String? = null,

    /** Set when this row becomes a real member. Null means still to do. */
    val claimedByUserId: String? = null,
    val claimedAt: Long? = null,
    val importBatchId: String? = null,
) {
    val isClaimed: Boolean get() = !claimedByUserId.isNullOrBlank()

    /** The geography this row already knows, for pre-filling a registration. */
    fun scope(): Scope = Scope(
        zoneId = zoneId, areaId = areaId, wardId = wardId,
        localityId = localityId, boothId = boothId,
    )
}
