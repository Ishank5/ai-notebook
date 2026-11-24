package com.humblesolutions.ainotetaker.form

import com.humblesolutions.ainotetaker.model.LookupType
import com.humblesolutions.ainotetaker.model.Role

/**
 * What kind of question a field is.
 *
 * The TYPE decides the control, the keyboard, the validation and how the value
 * is written — so a field list is enough to build a whole form, and the two
 * platforms render the same questions without either one owning the answer to
 * "what does this role get asked?".
 */
enum class FieldType {
    TEXT, TEXTAREA, PHONE, EMAIL, NUMBER,
    /** Three boxes, not a picker — see Dob. */
    DOB,
    /**
     * An Aadhaar number: digits only, shown in fours as on the card
     * ("1234 5678 9012"), stored as the twelve digits. Checked only when
     * something is typed — see `Validation.isValidAadhaar`.
     *
     * NO FORM ASKS FOR IT ANY MORE (Ansh, 10 Oct 2026). Kept so the type
     * still has a meaning; a number already on a record is left there.
     */
    AADHAAR,
    GENDER,
    /** One of the client-maintained lists. */
    LOOKUP,
    /**
     * How somebody means to decide their vote — one of four fixed answers
     * (see `VotingStrategy`), tapped rather than picked from a list. Fixed in
     * the app, not a Master Data list: the four are the question itself.
     */
    VOTING_STRATEGY,
    /** The whole geography cascade, as one field. */
    GEO,
    /** Credits the registration to somebody else in the creator's team. */
    ATTRIBUTED_TO,
    /** A captured GPS fix. */
    LOCATION,
    /**
     * A photograph of the person — taken with the camera, or chosen from the
     * phone's gallery (Ansh, 10 Oct 2026).
     *
     * NOT `DOCUMENTS`. An attachment picker asks "which file?"; this asks for
     * a face, and the control is the large circle above the name.
     */
    PHOTO,
    /**
     * Household size, how many of them are adults, and those adults by name.
     *
     * ONE FIELD, NOT THREE, because the second question only exists if the
     * first is answered and the third only exists once the second is. Three
     * schema entries could not express that, and would render two dead
     * controls on a form that is meant to move.
     */
    FAMILY,
    /**
     * Somebody already in the app, chosen rather than typed.
     *
     * A free-text "referred by" is unjoinable the moment two people spell a
     * name differently, which is immediately.
     */
    PERSON_REF,
    /**
     * The name they sign in with. Unique across the whole organisation,
     * enforced at the identity layer rather than by a Firestore check — see
     * [com.humblesolutions.ainotetaker.model.Credentials].
     */
    USERNAME,
    /** The password they sign in with, typed twice. */
    PASSWORD,
    /** File upload — not a question yet. */
    DOCUMENTS,
}

data class FormField(
    val key: String,
    val type: FieldType,
    val labelKey: String? = null,
    val required: Boolean = false,
    val hintKey: String? = null,
    val lookup: LookupType? = null,
    val maxLength: Int? = null,
) {
    /**
     * Every word starts with a capital as it is typed — "Ram Kumar Sharma".
     * Every plain text box on a registration form holds a name, an address or
     * something like it; the EPIC number is the one that does not (its own
     * box writes it in capitals). Phone, number, username, password and the
     * notes box are other types and are left alone.
     *
     * A HINT TO THE KEYBOARD, NOT A REWRITE: the shift key is set for each
     * word, and whoever types can still turn it off.
     */
    val capitalizesWords: Boolean get() = type == FieldType.TEXT && key != "epicNo"
}

data class FormStep(
    val id: String,
    val labelKey: String,
    val fields: List<FormField>,
)

data class FormSchema(val steps: List<FormStep>) {
    val fields: List<FormField> get() = steps.flatMap { it.fields }
}

/**
 * What each registration form asks for, as data.
 *
 * FIVE ROLES, FIVE FIELD LISTS — BUT ONE WIZARD. The forms differ only in which
 * questions they ask and in what order; the machinery around them (steps,
 * validation, drafts, the offline write) is identical, and five hand-built
 * copies of that would drift within a ticket or two.
 *
 * Field lists come from the reference screens in the client brief. Booth Leader
 * has no reference screen — it is modelled on Ward Leader and is PENDING OD-5,
 * so treat its list as a proposal rather than a spec.
 */
object FormSchemas {

    /**
     * Every account needs a login, and every form therefore asks for one.
     *
     * LAST IN THE STEP, ON PURPOSE. A worker describes the person first — who
     * they are, where they live, the number to reach them on — and only then
     * creates their account. Asking for a username above the name inverts
     * that: it makes the form about the system rather than about the person
     * standing in front of them.
     */
    private val credentials = listOf(
        FormField("username", FieldType.USERNAME, "registration.f.username", required = true, hintKey = "registration.f.usernameHint"),
        FormField("password", FieldType.PASSWORD, "registration.f.password", required = true, hintKey = "registration.f.passwordHint"),
    )

    private fun identity(extra: List<FormField> = emptyList()) = FormStep(
        id = "identity",
        labelKey = "registration.step.identity",
        fields = listOf(
            FormField("name", FieldType.TEXT, "registration.f.name", required = true),
            FormField("fatherName", FieldType.TEXT, "registration.f.fatherName"),
            FormField("phone", FieldType.PHONE, "registration.f.phone", required = true, hintKey = "registration.f.phoneHint"),
        ) + extra + credentials,
    )

    /** Every form asks for the chain; only the required depth differs. */
    private val assignment = FormStep(
        id = "assignment",
        labelKey = "registration.step.assignment",
        fields = listOf(FormField("scope", FieldType.GEO)),
    )

    private val attributedTo = FormField(
        "attributedTo", FieldType.ATTRIBUTED_TO, "registration.f.attributedTo",
        hintKey = "registration.f.attributedToHint",
    )

    /**
     * An Admin is an OFFICE role, and the form is three questions on purpose.
     *
     * They hold no geography, so there is no cascade to fill in and no
     * assignment step at all. Adding one "for consistency" would mean asking
     * somebody who works across the whole organisation which single ward they
     * belong to.
     *
     * Email is REQUIRED here and optional everywhere else, because for this
     * role it is not a contact detail: office roles sign in by email, and the
     * provisioning trigger refuses an Admin without one. Better to ask on the
     * form than to let the registration fail two seconds after it is submitted.
     */
    private val admin = FormSchema(
        listOf(
            FormStep(
                id = "identity",
                labelKey = "registration.step.identity",
                fields = listOf(
                    FormField("name", FieldType.TEXT, "registration.f.name", required = true),
                    FormField("phone", FieldType.PHONE, "registration.f.phone", required = true, hintKey = "registration.f.phoneAdminHint"),
                ) + credentials,
            ),
        ),
    )

    private val zoneLeader = FormSchema(
        listOf(
            identity(
                listOf(
                    FormField("gender", FieldType.GENDER, "registration.f.gender"),
                    FormField("dob", FieldType.DOB, "registration.f.dob"),
                    FormField("epicNo", FieldType.TEXT, "registration.f.epicNo"),
                ),
            ),
            assignment,
            FormStep(
                "organisation", "registration.step.organisation",
                listOf(
                    FormField("designationId", FieldType.LOOKUP, "registration.f.designation", lookup = LookupType.DESIGNATION, hintKey = "registration.f.designationHint"),
                    FormField("categoryId", FieldType.LOOKUP, "registration.f.category", lookup = LookupType.CATEGORY),
                    FormField("occupationId", FieldType.LOOKUP, "registration.f.occupation", lookup = LookupType.OCCUPATION),
                    FormField("homeStatusId", FieldType.LOOKUP, "registration.f.homeStatus", lookup = LookupType.HOME_STATUS),
                    attributedTo,
                ),
            ),
            FormStep(
                "influence", "registration.step.influence",
                listOf(
                    FormField("associatedWith", FieldType.TEXT, "registration.f.associatedWith"),
                    FormField("community", FieldType.TEXT, "registration.f.community"),
                    FormField("impactArea", FieldType.TEXT, "registration.f.impactArea"),
                    FormField("targetNo", FieldType.NUMBER, "registration.f.targetNo", hintKey = "registration.f.targetNoHint"),
                ),
            ),
        ),
    )

    private val wardLeader = FormSchema(
        listOf(
            identity(
                listOf(
                    FormField("dob", FieldType.DOB, "registration.f.dob"),
                ),
            ),
            assignment,
            FormStep(
                "organisation", "registration.step.organisation",
                listOf(
                    FormField("occupationId", FieldType.LOOKUP, "registration.f.occupation", lookup = LookupType.OCCUPATION),
                    FormField("homeStatusId", FieldType.LOOKUP, "registration.f.homeStatus", lookup = LookupType.HOME_STATUS),
                    FormField("designationId", FieldType.LOOKUP, "registration.f.designation", lookup = LookupType.DESIGNATION, hintKey = "registration.f.designationHint"),
                    attributedTo,
                ),
            ),
            FormStep(
                "influence", "registration.step.influence",
                listOf(
                    FormField("community", FieldType.TEXT, "registration.f.community"),
                    FormField("impactAreaCount", FieldType.NUMBER, "registration.f.impactAreaCount"),
                    FormField("targetNo", FieldType.NUMBER, "registration.f.targetNo", hintKey = "registration.f.targetNoHint"),
                ),
            ),
        ),
    )

    /** PENDING OD-5 — modelled on Ward Leader, bound to a booth. */
    private val boothLeader = FormSchema(
        listOf(
            identity(
                listOf(
                    FormField("gender", FieldType.GENDER, "registration.f.gender"),
                    FormField("dob", FieldType.DOB, "registration.f.dob"),
                    FormField("epicNo", FieldType.TEXT, "registration.f.epicNo"),
                ),
            ),
            assignment,
            FormStep(
                "organisation", "registration.step.organisation",
                listOf(
                    FormField("occupationId", FieldType.LOOKUP, "registration.f.occupation", lookup = LookupType.OCCUPATION),
                    FormField("homeStatusId", FieldType.LOOKUP, "registration.f.homeStatus", lookup = LookupType.HOME_STATUS),
                    FormField("designationId", FieldType.LOOKUP, "registration.f.designation", lookup = LookupType.DESIGNATION, hintKey = "registration.f.designationHint"),
                    attributedTo,
                ),
            ),
            FormStep(
                "influence", "registration.step.influence",
                listOf(
                    FormField("community", FieldType.TEXT, "registration.f.community"),
                    FormField("impactArea", FieldType.TEXT, "registration.f.impactArea"),
                    FormField("targetNo", FieldType.NUMBER, "registration.f.targetNo", hintKey = "registration.f.targetNoHint"),
                ),
            ),
        ),
    )

    /**
     * The only form that does not start with a name.
     *
     * EPIC FIRST, AND THAT IS THE WHOLE DESIGN. Every other role is somebody
     * the registrar knows and types out; a booth member is a voter who is
     * already on the imported roll, carrying their name, father's name,
     * gender, age, house number AND their entire geography. Asking for the
     * number first turns twenty-one fields into one — and asking for it
     * SECOND wastes that, because by then the name has been typed by hand.
     *
     * The lookup is a direct read keyed by EPIC number, so unlike the roll's
     * prefix typeahead it needs no ward chosen first. That is what lets it sit
     * at the top of step one rather than after the address.
     */
    private val boothMember = FormSchema(
        listOf(
            FormStep(
                id = "identity",
                labelKey = "registration.step.identity",
                fields = listOf(
                    FormField(
                        "epicNo", FieldType.TEXT, "registration.f.epicNo",
                        hintKey = "registration.f.epicHint", maxLength = 20,
                    ),
                    // REQUIRED on this card: the attendance dot the admin sees is
                    // this photograph. Without it a visit cannot be attributed.
                    FormField("photo", FieldType.PHOTO, "registration.f.photo", required = true),
                    // 120 is the ceiling the server puts on a name (`hasCoreFields`
                    // in firestore.rules); the others are what a line on a card holds.
                    FormField("name", FieldType.TEXT, "registration.f.name", required = true, maxLength = PERSON_NAME_MAX),
                    FormField("fatherName", FieldType.TEXT, "registration.f.fatherName", maxLength = PERSON_NAME_MAX),
                    /*
                     * A WOMAN'S HUSBAND, under her father — shown only once
                     * "Female" is chosen, and then one of the two is needed.
                     * See `IdentityCard`.
                     */
                    FormField("husbandName", FieldType.TEXT, "registration.f.husbandName", maxLength = PERSON_NAME_MAX),
                    // No "they sign in with this number" under it: this
                    // person never signs in (Rishi, 26 Sep 2026).
                    FormField("phone", FieldType.PHONE, "registration.f.phone", required = true),
                    FormField("dob", FieldType.DOB, "registration.f.dob"),
                    // First on a card filled by hand — see `IdentityCard.arrange`.
                    FormField("gender", FieldType.GENDER, "registration.f.gender"),
                ),
            ),
            FormStep(
                "address", "registration.step.address",
                listOf(
                    FormField("houseNo", FieldType.TEXT, "registration.f.houseNo", maxLength = HOUSE_NO_MAX),
                    FormField("addressLine", FieldType.TEXT, "registration.f.address", maxLength = ADDRESS_MAX),
                    // No Aadhaar: no form asks for it any more (Ansh, 10 Oct
                    // 2026). A number already stored stays on the record.
                    FormField("scope", FieldType.GEO),
                ),
            ),
            FormStep(
                "profile", "registration.step.profile",
                listOf(
                    FormField("casteId", FieldType.LOOKUP, "registration.f.caste", lookup = LookupType.CASTE),
                    FormField("religionId", FieldType.LOOKUP, "registration.f.religion", lookup = LookupType.RELIGION),
                    FormField("occupationId", FieldType.LOOKUP, "registration.f.occupation", lookup = LookupType.OCCUPATION),
                    /*
                     * WHAT "OTHER" MEANS, when the occupation list has no
                     * match. Not shown as an ordinary field — the iOS card
                     * renders it only once "Other" is actually picked — but
                     * declared here so it is a real field the details map
                     * carries through to the saved record, the same way any
                     * other typed answer is.
                     */
                    FormField("occupationOther", FieldType.TEXT, "registration.f.occupationOther", maxLength = PERSON_NAME_MAX),
                    FormField("familyMemberCount", FieldType.FAMILY, "registration.f.familyMembers", maxLength = 2),
                ),
            ),
            FormStep(
                "influence", "registration.step.influence",
                listOf(
                    FormField("reference", FieldType.PERSON_REF, "registration.f.reference"),
                    FormField("influencedBy", FieldType.TEXT, "registration.f.influencedBy", maxLength = PERSON_NAME_MAX),
                    FormField("otherDetails", FieldType.TEXTAREA, "registration.f.otherDetails", maxLength = NOTES_MAX),
                    attributedTo,
                    /*
                     * HOW THEY VOTE — all three optional, and none hides
                     * another: somebody sure of their strategy may still not
                     * say a party, and the reverse. Both party questions read
                     * the one PARTY list the office keeps in Master Data.
                     */
                    FormField("partyLastTimeId", FieldType.LOOKUP, "registration.f.partyLastTime", lookup = LookupType.PARTY),
                    FormField("partyThisTimeId", FieldType.LOOKUP, "registration.f.partyThisTime", lookup = LookupType.PARTY),
                    FormField("votingStrategy", FieldType.VOTING_STRATEGY, "registration.f.votingStrategy"),
                ),
            ),
            FormStep(
                "location", "registration.step.location",
                listOf(
                    // REQUIRED on this card: attendance is pinned to this exact fix,
                    // so a registration without one cannot be placed on the map.
                    FormField("location", FieldType.LOCATION, "registration.f.location", required = true),
                    FormField("documents", FieldType.DOCUMENTS),
                ),
            ),
        ),
    )

    /**
     * A Youth Leader — see `Role.YOUTH_LEADER`. The Locality Member card's
     * questions, less what a Youth Leader is not asked (occupation, the
     * household, influence, voting, location), plus the one thing that is
     * theirs: their strength, on a tab of its own because it is the point of
     * the form — the number an office sorts and totals by.
     *
     * The voter list comes first, as on the Locality Member card, but a Youth
     * Leader need not be on it: that screen can be skipped, and the Voter ID
     * box then appears on Details to be typed.
     */
    private val youthLeader = FormSchema(
        listOf(
            FormStep(
                id = "identity",
                labelKey = "registration.step.identity",
                fields = listOf(
                    FormField("photo", FieldType.PHOTO, "registration.f.photo", required = true),
                    FormField("name", FieldType.TEXT, "registration.f.name", required = true, maxLength = PERSON_NAME_MAX),
                    // No "they sign in with this number" under it: this
                    // person never signs in (Rishi, 26 Sep 2026).
                    FormField("phone", FieldType.PHONE, "registration.f.phone", required = true),
                    FormField(
                        "epicNo", FieldType.TEXT, "registration.f.epicNo",
                        hintKey = "registration.f.epicHint", maxLength = 20,
                    ),
                    FormField("fatherName", FieldType.TEXT, "registration.f.fatherName", maxLength = PERSON_NAME_MAX),
                    // As on the Locality Member card: a woman's husband, under
                    // her father, and one of the two is needed — see `IdentityCard`.
                    FormField("husbandName", FieldType.TEXT, "registration.f.husbandName", maxLength = PERSON_NAME_MAX),
                    FormField("dob", FieldType.DOB, "registration.f.dob"),
                    FormField("gender", FieldType.GENDER, "registration.f.gender"),
                ),
            ),
            FormStep(
                "address", "registration.step.address",
                listOf(
                    FormField("houseNo", FieldType.TEXT, "registration.f.houseNo", maxLength = HOUSE_NO_MAX),
                    FormField("addressLine", FieldType.TEXT, "registration.f.address", maxLength = ADDRESS_MAX),
                    FormField("scope", FieldType.GEO),
                ),
            ),
            FormStep(
                "strength", "registration.step.strength",
                listOf(
                    FormField(
                        STRENGTH, FieldType.NUMBER, "registration.f.strength",
                        required = true, hintKey = "registration.f.strengthHint", maxLength = STRENGTH_DIGITS,
                    ),
                ),
            ),
            FormStep(
                "profile", "registration.step.profile",
                listOf(
                    FormField("casteId", FieldType.LOOKUP, "registration.f.caste", lookup = LookupType.CASTE),
                    FormField("religionId", FieldType.LOOKUP, "registration.f.religion", lookup = LookupType.RELIGION),
                ),
            ),
        ),
    )

    /** A Youth Leader's strength: how many people they vouch they can bring out. */
    const val STRENGTH = "strength"

    /** Up to 9,999 people — a whole number, never a phone number typed in the wrong box. */
    const val STRENGTH_DIGITS = 4

    fun of(role: Role?): FormSchema? = when (role) {
        Role.ADMIN -> admin
        Role.ZONE_LEADER -> zoneLeader
        Role.WARD_LEADER -> wardLeader
        Role.BOOTH_LEADER -> boothLeader
        Role.YOUTH_LEADER -> youthLeader
        Role.BOOTH_MEMBER -> boothMember
        else -> null
    }

    /**
     * How big the form is, so the hub can set expectations before somebody
     * starts.
     *
     * DERIVED, NOT HAND-COUNTED: a number typed into the hub goes stale the
     * first time a field is added, and "22 fields" that turns out to be 25 is
     * worse than no number at all. Counts inputs a person actually faces — the
     * cascade is five of them whatever the role's required depth, a date of
     * birth is one question even though it is three boxes, and the upload is
     * not a question yet.
     */
    fun shapeOf(role: Role?): Pair<Int, Int> {
        val schema = of(role) ?: return 0 to 0
        val fields = schema.fields.sumOf { field ->
            when (field.type) {
                FieldType.DOCUMENTS -> 0
                // Two questions: how many live here, and how many are adults.
                // The per-adult rows are not asked until those are answered.
                FieldType.FAMILY -> 2
                FieldType.USERNAME, FieldType.PASSWORD -> 1
                /*
                 * The cascade is ONE question on the booth member form, not
                 * five: locality is asked, and everything above and below it
                 * is derived. Other roles still step through it link by link.
                 */
                FieldType.GEO ->
                    // A Team Leader is asked the area, nothing above or below it.
                    if (role?.onCard == true || com.humblesolutions.ainotetaker.config.AreaFirst.applies(role)) 1
                    else com.humblesolutions.ainotetaker.config.GeoChain.links.size
                else -> 1
            }
        }
        return schema.steps.size to fields
    }

    /** Written as numbers rather than strings, so the rules' type checks pass. */
    val numericKeys = setOf(
        "targetNo", "impactAreaCount", "familyMemberCount", "familyAdultCount", STRENGTH,
    )

    /** The household's adult count, asked once the total is known. */
    const val ADULT_COUNT = "familyAdultCount"

    /** How many adults a form will collect details for, however big the family. */
    const val MAX_ADULTS = 20

    /** A person's name. The server refuses more than this, whichever field it came from. */
    const val PERSON_NAME_MAX = 120

    /** A house number is a short token — "12/B", "Plot 4, Gali 3" — not a paragraph. */
    const val HOUSE_NO_MAX = 40

    /** The rest of an address: street, landmark, village. */
    const val ADDRESS_MAX = 200

    /** The free-text notes box. Long enough for a sentence or two, not for a diary. */
    const val NOTES_MAX = 500
}
