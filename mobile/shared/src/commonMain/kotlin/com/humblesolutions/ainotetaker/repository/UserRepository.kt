package com.humblesolutions.ainotetaker.repository

import com.humblesolutions.ainotetaker.model.managesPeople

import com.humblesolutions.ainotetaker.model.Role
import com.humblesolutions.ainotetaker.model.User
import com.humblesolutions.ainotetaker.util.Cancellable

/**
 * The team: reading a branch of it, adding to it, editing it.
 *
 * EVERY LIST QUERY HERE IS SCOPED BY `ancestors`, not filtered afterwards.
 * Firestore requires a list query to be PROVABLY inside what the rules permit;
 * reading widely and filtering in the client is refused outright, not merely
 * wasteful.
 */
interface UserRepository {

    suspend fun getUser(uid: String): User?

    /** Live, because a registration made offline should appear the moment it syncs. */
    fun observeUser(uid: String, onChange: (User?) -> Unit): Cancellable

    /**
     * The same document, but saying WHY it is not there when it is not.
     *
     * A SECOND METHOD RATHER THAN A WIDER [observeUser], because the two
     * callers want opposite things from a failed read. A person detail screen
     * that cannot load somebody shows an empty card and lets you press back —
     * a null is a fine answer there. THE SESSION cannot do that: a null is the
     * difference between "this account does not exist" and "we could not ask",
     * and it used to answer both by signing the person out.
     *
     * Implementations must map a listener error to [UserDoc.Failed] and a
     * missing document to [UserDoc.Absent]. Collapsing them is the bug this
     * exists to make impossible.
     */
    fun observeUserDocument(uid: String, onChange: (UserDoc) -> Unit): Cancellable

    /**
     * Direct reports of [parentId], by name.
     *
     * [viewerUid] IS NOT OPTIONAL, and it is not there for filtering — the
     * rows it admits are exactly the rows `parentId ==` would have returned.
     * Firestore requires a list query to be PROVABLY inside what the rules
     * permit, and `parentId == someone` does not prove the result stays in the
     * caller's subtree even when it does. Without the ancestors clause the
     * whole query is refused, for everyone below Admin.
     */
    fun observeDirectReports(
        parentId: String,
        viewerUid: String,
        limit: Int,
        onChange: (List<User>) -> Unit,
    ): Cancellable

    /** Everyone beneath [uid] at any depth — the whole subtree. */
    suspend fun subtreeOf(uid: String, limit: Int = 500): List<User>

    /**
     * The LEADERS beneath [uid] at any depth — everyone in the subtree who
     * manages people, never a Locality Member or a Youth Leader.
     *
     * NOT [subtreeOf] FILTERED. A Super Admin's subtree is thousands of
     * Locality Members, and a capped read of it in name order would stop
     * before reaching half the leaders. The Firestore versions ask role by
     * role instead, the same `ancestors` + `role` pair the dashboard counts
     * with; this default is only for fakes.
     */
    suspend fun leadersBelow(uid: String, limit: Int = 500): List<User> =
        subtreeOf(uid, limit).filter { it.role.managesPeople() }

    /**
     * Every active leader in the organisation — name, role and id only, from
     * the `listReferrers` function (the rules let a phone see only its own
     * branch). For "Referred by" at every level. Throws when it could not be
     * asked; this default is only for fakes.
     */
    suspend fun organisationLeaders(): List<User> = emptyList()

    /**
     * Everybody [viewerUid] registered themselves, live, by name.
     *
     * `createdBy` IS THE AUDIT TRUTH, not `attributedTo`. A leader who credits
     * a registration to somebody else still took it, and the person credited
     * may sit below them — outside the new record's `ancestors`, so unable to
     * read it at all.
     *
     * CARRIES `ancestors array-contains viewerUid` FOR THE SAME REASON
     * [observeDirectReports] does. It narrows nothing — a new record's
     * ancestors are always its creator's plus the creator — but it is what
     * proves the query inside the rules; `createdBy ==` alone is refused for
     * everybody below Admin.
     *
     * LIVE, so somebody who has just registered a family comes back to My Team
     * and finds them on it.
     *
     * NULL MEANS IT COULD NOT BE READ, and is not the same as an empty list. A
     * refused query, or an index still building after a deploy, would
     * otherwise tell somebody who has registered forty families that they
     * have registered nobody — the one message that sends them to register
     * everybody again.
     */
    fun observeCreatedBy(
        viewerUid: String,
        limit: Int,
        onChange: (List<User>?) -> Unit,
    ): Cancellable

    /**
     * The people posted to one locality.
     *
     * [viewerUid] IS WHAT MAKES THE QUERY LEGAL, exactly as in
     * [observeDirectReports] — the rules admit a user document when
     * `ancestors` contains the caller, so that clause must be IN the query and
     * cannot be swapped for the locality one. Firestore permits a single array
     * field per query, so the locality is matched as a plain equality on
     * `scope.localityId` rather than through `scopePath`.
     *
     * THE CONSEQUENCE IS VISIBLE AND MUST BE SAID ON SCREEN: a leader sees
     * only their OWN people in a locality, never everybody there. Two leaders
     * opening the same locality see different lists, which is the privacy
     * boundary working, not a bug — but a screen that does not explain it
     * looks broken. Admin and Super Admin are admitted by the other half of
     * the rule and do see everyone.
     */
    suspend fun peopleIn(
        localityId: String,
        viewerUid: String,
        limit: Int = 200,
    ): List<User>

    /** Headcount per role beneath [uid], for the dashboard tiles. */
    suspend fun countsByRole(uid: String): Map<Role, Int>

    /**
     * Create a registration, account and all.
     *
     * NEEDS SIGNAL, AND THAT IS A REAL CHANGE. This used to be a direct
     * Firestore write that queued offline for hours (decision D10). It cannot
     * be any more: a registration now carries a PASSWORD, and a document
     * written by a client is a document sitting in Firestore — so a queued
     * write would mean a plaintext password at rest until the queue drained.
     *
     * The call goes to `createTeamMember`, which creates the identity and the
     * document together and returns only when both exist. The form still
     * drafts offline, so nothing typed at a doorstep is lost; only the moment
     * of creation is online-only.
     *
     * The password is a separate argument on purpose: it must never be a
     * property of [User], because [User] is the thing that gets serialised.
     */
    suspend fun createUser(user: User, password: String): String

    suspend fun updateUser(uid: String, changes: Map<String, Any?>)

    /**
     * Somebody already registered on this number, inside the caller's own
     * branch. A COURTESY, NOT AN ENFORCEMENT — the rules only permit reading
     * one's own subtree, so a clash in another ward is invisible here and is
     * caught by the provisioning trigger instead.
     */
    suspend fun findDuplicateByPhone(phone: String, callerUid: String): User?

    /** Name prefix, phone prefix, last four digits, or an exact EPIC — see SearchMode. */
    suspend fun searchTeam(callerUid: String, term: String, limit: Int = 50): List<User>
}

/**
 * What the account document listener actually saw.
 *
 * THREE ANSWERS, NOT TWO. Firestore reports "no such document" and "I could
 * not read it" through one nullable snapshot, and every caller that flattened
 * those to a null inherited the ambiguity. Naming the third answer is what
 * lets the session keep somebody signed in through a tunnel.
 */
sealed interface UserDoc {
    /** The document is there and parsed. */
    data class Present(val user: User) : UserDoc

    /** The read succeeded and there is no such document. */
    data object Absent : UserDoc

    /**
     * The read did not succeed — offline with a cold cache, a rules refusal
     * while the token still has no claims, a listener the backend dropped.
     * [reason] is for a log and a diagnostics line, never for a screen: it is
     * Firebase's English, addressed to a developer.
     */
    data class Failed(val reason: String? = null) : UserDoc
}
