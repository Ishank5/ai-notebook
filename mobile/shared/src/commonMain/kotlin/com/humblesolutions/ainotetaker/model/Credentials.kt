package com.humblesolutions.ainotetaker.model

/**
 * One door for everybody: a username and a password.
 *
 * WHY NOT PHONE, WHICH IS WHAT THIS REPLACED. An OTP costs money per sign-in,
 * needs the handset in the worker's hand at the moment they sign in, and fails
 * in exactly the places this app is used — a basement polling station with no
 * signal. A worker who has handed their phone to a colleague for the day can
 * still do their job with a username.
 *
 * THE PHONE NUMBER DID NOT GO AWAY. Every member still has one, still unique
 * across the whole organisation — it is how a leader reaches their team. It is
 * simply no longer a CREDENTIAL. That distinction is the whole change: contact
 * details and login details stopped being the same field.
 *
 * WHY THERE IS A FAKE EMAIL UNDERNEATH. Firebase Auth has no username
 * provider; it has email/password, and it enforces email uniqueness ATOMICALLY
 * at the identity layer. Mapping `first.last` to `first.last@tas.invalid` buys
 * three things that would otherwise have to be built and got right by hand:
 * scrypt password hashing, brute-force rate limiting, and a username
 * uniqueness guarantee that no race can defeat. A Firestore "is this name
 * taken?" check cannot promise the third — two registrations a millisecond
 * apart both read "free".
 *
 * `.invalid` IS RESERVED BY RFC 2606 and can never be delegated to a real mail
 * server, so no password reset, bounce or typo can ever reach a real inbox.
 * The address exists only so Firebase has a primary key; nothing ever sends to
 * it.
 */
object Credentials {

    /** Never resolvable. See the note above on RFC 2606. */
    const val DOMAIN = "tas.invalid"

    const val MIN_USERNAME = 3
    const val MAX_USERNAME = 20

    /**
     * Eight, and no complexity rules.
     *
     * A rule demanding a symbol and a digit at a doorstep produces `Pass@123`
     * on every account in the ward, which is worse than a long ordinary one.
     * Length is the property that actually resists guessing, and it is the one
     * a person can satisfy without writing the result on the back of the
     * handset.
     */
    const val MIN_PASSWORD = 8

    /**
     * Lowercase letters, digits, dot and underscore; must start with a letter.
     *
     * NO UPPERCASE AT ALL, rather than folding case on comparison. If `Ansh`
     * and `ansh` are the same account, then showing one of them back is
     * showing something the person did not type, and somebody will eventually
     * compare two spellings and conclude they are different people. Refusing
     * capitals outright is one rule instead of a subtlety.
     */
    private val ALLOWED = Regex("^[a-z][a-z0-9._]{2,19}$")

    /*
     * Logins are real email addresses on Firebase Auth,
     * not usernames. An address is accepted wherever a username is, and is
     * sent to Firebase as typed (trimmed, lower case) instead of being mapped
     * to `@tas.invalid`. Usernames behave exactly as before.
     */
    const val MAX_EMAIL = 254
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun isValidUsername(username: String): Boolean = ALLOWED.matches(username) || EMAIL.matches(username)

    /**
     * What a typed or pasted username becomes before anything else sees it.
     *
     * ONE SPELLING OF THIS, SHARED, because the two platforms had their own
     * and they disagreed. Android used Kotlin's `trim()`, which strips every
     * kind of whitespace; iOS used `.whitespaces`, which leaves newlines
     * behind. A username pasted out of a message app — where a trailing
     * newline is routine — therefore signed in on one platform and was
     * refused on the other, and the screen could only say "no account with
     * that username".
     *
     * WHITESPACE IS REMOVED ANYWHERE, not only at the ends. A username can
     * never contain a space, so one in the middle is always a paste artefact
     * or a stray thumb, and dropping it is what the person meant. The
     * alternative is a refusal that looks like a forgotten account.
     *
     * CAPPED AT [MAX_USERNAME], because longer cannot exist. Letting the box
     * accept a 30-character paste only defers the same refusal to the server.
     */
    fun sanitiseUsername(raw: String): String =
        // an email address is capped at MAX_EMAIL, not MAX_USERNAME.
        raw.filterNot { it.isWhitespace() }.lowercase().take(if ('@' in raw) MAX_EMAIL else MAX_USERNAME)

    fun isValidPassword(password: String): Boolean = password.length >= MIN_PASSWORD

    /**
     * The Firebase identity behind a username.
     *
     * MUST MATCH THE SERVER EXACTLY — `functions/src/lib/credentials.ts` holds
     * the same two lines, and a difference between them means an account
     * created by the server cannot be signed into by the client. Kept
     * deliberately trivial so there is nothing to diverge.
     */
    fun emailFor(username: String): String =
        // a real email address is used as it is.
        if ('@' in username) username.trim().lowercase() else "${username.trim().lowercase()}@$DOMAIN"

    /** The username behind a Firebase identity, for display. */
    fun usernameFrom(email: String?): String? =
        email?.takeIf { it.endsWith("@$DOMAIN") }?.removeSuffix("@$DOMAIN")

    /**
     * A first guess at a username, from the person's name.
     *
     * OFFERED, NEVER IMPOSED. "Ansh Bajaj" suggests `first.last`, which is
     * right often enough to save typing and wrong often enough — two people of
     * that name in one ward — that the creator must be able to overrule it.
     */
    fun suggestFrom(name: String): String {
        val cleaned = name.trim().lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(" ")
            .filter { it.isNotBlank() }

        if (cleaned.isEmpty()) return ""
        val joined = cleaned.joinToString(".").take(MAX_USERNAME).trimEnd('.', '_')
        // A name that starts with a digit cannot open a username.
        return if (joined.firstOrNull()?.isLetter() == true) joined else "u$joined".take(MAX_USERNAME)
    }
}
