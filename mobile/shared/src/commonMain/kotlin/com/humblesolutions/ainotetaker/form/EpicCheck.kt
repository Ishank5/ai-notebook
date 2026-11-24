package com.humblesolutions.ainotetaker.form

/**
 * Whether a voter is already one of ours — asked when a worker picks a voter
 * on the registration form, types a whole EPIC, or saves.
 *
 * WHY. Six EPICs had already been registered twice by October 2026: the form
 * quietly let a worker pick a voter somebody else had registered, and a
 * hand-typed EPIC was never checked at all. Now the form refuses the pick and
 * says so, and the worker who filled that voter's form is offered a way to
 * their page.
 *
 * TWO SOURCES, because the form is used where there is no signal:
 *  - the server (`checkEpicRegistered` in functions/src/epicCheck.ts), which
 *    sees every member organisation-wide and knows who filled each form;
 *  - this phone's claim table, which knows the roll rows registered so far
 *    (synced) and those this phone registered itself, with no network at all.
 * [decide] combines them; both platforms' forms call it.
 */
sealed class EpicCheckResult {
    /** Nobody has this EPIC — as far as the server could tell. */
    data object Free : EpicCheckResult()

    /**
     * Somebody has it.
     *
     * @property byMe whether the asker filled that form: true, false, or null
     *   when it cannot be told (offline, and only the claim table knew).
     * @property memberUid the member, ONLY when [byMe] is true and the server
     *   said so — what the "See their details" button opens.
     * @property name the member's name, likewise only for the person who
     *   registered them.
     */
    data class Registered(
        val byMe: Boolean?,
        val memberUid: String?,
        val name: String?,
    ) : EpicCheckResult()

    /** Nothing could be determined: offline, not deployed, refused, timed out. */
    data object Unavailable : EpicCheckResult()
}

/** What the phone's own claim table says about an EPIC. See `RollSource.localClaim`. */
data class LocalClaim(
    /** For a synced row, the MEMBER's uid; for one this phone wrote, the registrar's. */
    val claimedBy: String?,
    /** Written by this phone at its own registration, not yet confirmed by a sync. */
    val byThisDevice: Boolean,
)

object EpicCheck {

    /**
     * How long the form waits for the server before going on with what the
     * phone knows. Long enough for a slow connection, short enough that a
     * worker at a door is not left holding a spinner.
     */
    const val SERVER_WAIT_MS = 6_000L

    /** Long enough to be a whole EPIC (3 letters + 7 digits) — when typing is checked. */
    const val WHOLE = 10

    /**
     * The verdict, from the server's answer (or [EpicCheckResult.Unavailable])
     * and the claim table's (or null).
     *
     * THE SERVER WINS when it answered: it sees everyone, and a claim row it
     * does not back up is one left by a member who no longer exists — except a
     * registration THIS phone made and has not yet seen confirmed, which the
     * server may simply not have caught up with (or is still queued offline).
     *
     * WITHOUT THE SERVER, the claim table is the only witness: a row there is
     * a registration, by this worker when this phone wrote it, and by somebody
     * unknown otherwise. No row means unknown, never free.
     */
    fun decide(server: EpicCheckResult, local: LocalClaim?, myUid: String): EpicCheckResult {
        val mineHere = local != null && local.byThisDevice && local.claimedBy == myUid
        return when (server) {
            is EpicCheckResult.Registered -> server
            EpicCheckResult.Free ->
                if (mineHere) EpicCheckResult.Registered(byMe = true, memberUid = null, name = null) else EpicCheckResult.Free
            EpicCheckResult.Unavailable -> when {
                local == null -> EpicCheckResult.Unavailable
                mineHere -> EpicCheckResult.Registered(byMe = true, memberUid = null, name = null)
                else -> EpicCheckResult.Registered(byMe = null, memberUid = null, name = null)
            }
        }
    }

    /**
     * The server's reply, as `checkEpicRegistered` returns it. Anything that is
     * not a well-formed answer is [EpicCheckResult.Unavailable] — never Free.
     */
    fun parse(data: Map<String, Any?>?): EpicCheckResult {
        data ?: return EpicCheckResult.Unavailable
        return when (flag(data["registered"])) {
            false -> EpicCheckResult.Free
            true -> {
                val byMe = flag(data["byMe"]) == true
                EpicCheckResult.Registered(
                    byMe = byMe,
                    memberUid = (data["memberUid"] as? String)?.takeIf { byMe && it.isNotBlank() },
                    name = (data["name"] as? String)?.takeIf { byMe && it.isNotBlank() },
                )
            }
            else -> EpicCheckResult.Unavailable
        }
    }

    /**
     * A yes/no, however the platform delivered it. Android hands Firebase's
     * booleans over as Boolean; iOS as NSNumber, which Kotlin sees as neither
     * `true` nor `false` but whose text is "1" or "0".
     */
    internal fun flag(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        null -> null
        else -> when (v.toString()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

    /**
     * How long a "free" answer is trusted within one form before the server is
     * asked again. A "registered" answer is kept for the whole form: a
     * registration does not go away while somebody is filling a card.
     */
    const val FREE_TRUSTED_MS = 60_000L

    /** Whether an EPIC is worth asking about yet: long enough to be a real one. */
    fun worthChecking(epicNo: String?): Boolean =
        com.humblesolutions.ainotetaker.util.Validation.normaliseEpic(epicNo).length >= WHOLE
}

/**
 * What one form has already been told, by EPIC — so a screen that redraws, a
 * worker who comes back to the form, or the same voter tapped twice never
 * sends the check round again, with its spinner and its dialog.
 *
 * THE LOOP THIS EXISTS FOR. Every repeat of the same question used to ask the
 * server again, hold the screen again, and refuse again. Now a repeat is
 * answered from here at once; the save alone asks afresh, because somebody
 * may have registered the voter since.
 */
class EpicVerdicts(private val now: () -> Long) {
    private val known = mutableMapOf<String, Pair<EpicCheckResult, Long>>()

    /** The remembered answer for [epic], or null when it has to be asked. */
    fun recall(epic: String): EpicCheckResult? {
        val (verdict, at) = known[epic] ?: return null
        return when (verdict) {
            is EpicCheckResult.Registered -> verdict
            EpicCheckResult.Free -> verdict.takeIf { now() - at < EpicCheck.FREE_TRUSTED_MS }
            EpicCheckResult.Unavailable -> null
        }
    }

    /** Keeps a real answer; "could not tell" is never kept, so it is asked again. */
    fun keep(epic: String, verdict: EpicCheckResult) {
        if (verdict != EpicCheckResult.Unavailable) known[epic] = verdict to now()
    }

    fun clear() = known.clear()
}
