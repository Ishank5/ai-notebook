package com.humblesolutions.ainotetaker.util

/**
 * The validators, shared so both platforms refuse the same things.
 *
 * These mirror `firestore.rules` deliberately. The registration write goes
 * straight to Firestore with no callable in between, so it queues offline —
 * which also means anything wrong surfaces as a flat permission-denied with no
 * explanation. Catching it here is the only way somebody learns what was wrong.
 */
object Validation {

    /** An Indian mobile number is exactly this many digits, without the country code. */
    const val MOBILE_DIGITS = 10

    /**
     * Every digit in [value], as plain 0-9.
     *
     * ANY SCRIPT'S DIGITS COUNT. A Hindi or Punjabi keyboard can produce ९८७६…
     * or ੯੮੭੬…, and to the person typing them that is a perfectly good phone
     * number. Kept as typed, the mobile check refused a number that looked right
     * on screen — and storing it would have written one no dialler can call. They
     * are converted here, once, so everything downstream only ever sees ASCII.
     */
    fun digitsOf(value: String?): String = buildString {
        for (ch in value.orEmpty()) {
            val digit = ch.digitToIntOrNull() ?: continue
            append('0' + digit)
        }
    }

    /**
     * What a phone box should hold after somebody types or pastes into it.
     *
     * A PASTE IS NOT TEN DIGITS. Contacts and WhatsApp copy "+91 98765 43210",
     * and plenty of people write "098765 43210". Cutting either to its first ten
     * digits keeps "9198765432" — a well-formed number that belongs to a
     * stranger, and it sails through validation. The country code and the
     * trunk zero are peeled off first, and only when there are digits left over
     * to justify it.
     *
     * TYPING NEVER TRIGGERS THE PEELING. The box is capped at ten, so a typed
     * number never grows past the length these rules wait for — a real number
     * that happens to begin "91" is safe from being eaten as it is entered.
     */
    fun mobileFromInput(value: String?): String {
        var d = digitsOf(value)
        if (d.length >= MOBILE_DIGITS + 4 && d.startsWith("0091")) {
            d = d.substring(4)
        } else if (d.length >= MOBILE_DIGITS + 2 && d.startsWith("91")) {
            d = d.substring(2)
        }
        if (d.length > MOBILE_DIGITS && d.startsWith("0")) d = d.substring(1)
        return d.take(MOBILE_DIGITS)
    }

    /**
     * An Indian mobile number: ten digits starting 6-9.
     *
     * Landlines and the older prefixes are refused because the number is the
     * LOGIN IDENTIFIER — an OTP has to arrive on it.
     */
    /** Digits in an Aadhaar number. */
    const val AADHAAR_DIGITS = 12

    /** What the Aadhaar box keeps of a keystroke or a paste: its digits, twelve at most. */
    fun aadhaarFromInput(value: String?): String = digitsOf(value).take(AADHAAR_DIGITS)

    /** "1234 5678 9012" — in fours, as printed on the card. Stored without the spaces. */
    fun formatAadhaar(value: String?): String = aadhaarFromInput(value).chunked(4).joinToString(" ")

    /**
     * An Aadhaar number UIDAI could have issued: twelve digits, not starting
     * with 0 or 1, and the last digit the Verhoeff check digit of the first
     * eleven. The check catches every single mistyped digit and every swap of
     * two neighbours — the two mistakes a number read off a card and typed on
     * a doorstep actually suffers.
     *
     * Says nothing about whether the number belongs to this person, or to
     * anybody: only that it is not a typo.
     */
    fun isValidAadhaar(value: String?): Boolean {
        val d = digitsOf(value)
        if (d.length != AADHAAR_DIGITS || d[0] == '0' || d[0] == '1') return false
        var check = 0
        d.reversed().forEachIndexed { i, ch ->
            check = VERHOEFF_D[check][VERHOEFF_P[i % 8][ch - '0']]
        }
        return check == 0
    }

    /** Verhoeff's tables: the dihedral group D5, and its position permutations. */
    private val VERHOEFF_D = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
        intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
        intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
        intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
        intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
    )
    private val VERHOEFF_P = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
        intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
        intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
        intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
    )

    fun isValidMobile(phone: String?): Boolean {
        val d = digitsOf(phone)
        return d.length == MOBILE_DIGITS && d[0] in '6'..'9'
    }

    /** Storage form. Always `+91` — the app serves one constituency. */
    fun toE164(phone: String?): String = "+91" + digitsOf(phone)

    /** The last four digits, which is what people say and type when asked. */
    fun last4(phone: String?): String? = digitsOf(phone).takeIf { it.length >= 4 }?.takeLast(4)

    fun isValidEmail(email: String?): Boolean {
        val v = email?.trim().orEmpty()
        if (v.isEmpty() || v.count { it == '@' } != 1) return false
        val (local, domain) = v.split("@")
        return local.isNotEmpty() && !local.any { it.isWhitespace() } &&
            domain.contains('.') && !domain.any { it.isWhitespace() } &&
            domain.substringAfterLast('.').length >= 2
    }

    /** An EPIC number as printed on the card: letters then digits, uppercase. */
    fun normaliseEpic(raw: String?): String =
        (raw ?: "").uppercase().filter { it.isLetterOrDigit() }

    fun isPlausibleEpic(raw: String?): Boolean = normaliseEpic(raw).length in 4..64

    fun isValidName(name: String?): Boolean {
        val n = name?.trim().orEmpty()
        return n.isNotEmpty() && n.length <= 120
    }
}

/**
 * A date of birth from three separate boxes.
 *
 * THREE BOXES, NOT A PICKER. A field worker registering somebody born in 1954
 * would have to scroll a wheel through seventy years; typing `1954` is one
 * gesture. It also matches how the number is read off an EPIC card.
 *
 * Returns [Empty] for all-blank (legitimate — plenty of people do not know) and
 * [Invalid] for partially filled or impossible, which are different mistakes.
 */
sealed interface DobResult {
    data object Empty : DobResult
    data object Invalid : DobResult
    data class Valid(val epochMillis: Long) : DobResult
}

object Dob {

    private val DAYS = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    fun parse(day: String?, month: String?, year: String?, nowMillis: Long): DobResult {
        val parts = listOf(day, month, year)
        if (parts.all { it.isNullOrBlank() }) return DobResult.Empty
        if (parts.any { it.isNullOrBlank() }) return DobResult.Invalid

        val d = day!!.trim().toIntOrNull() ?: return DobResult.Invalid
        val m = month!!.trim().toIntOrNull() ?: return DobResult.Invalid
        val y = year!!.trim().toIntOrNull() ?: return DobResult.Invalid

        if (m !in 1..12 || y < MIN_YEAR) return DobResult.Invalid
        if (d !in 1..daysIn(m, y)) return DobResult.Invalid

        val millis = toEpochMillis(y, m, d)
        // A date of birth in the future is a typo, always.
        return if (millis < nowMillis) DobResult.Valid(millis) else DobResult.Invalid
    }

    /** The earliest year [parse] accepts. */
    const val MIN_YEAR = 1900

    /*
     * WHILE TYPING, THE BOXES ONLY TAKE WHAT COULD STILL BE A REAL DATE.
     *
     * Each returns what the box should hold after a keystroke: the new text
     * when it is acceptable, the text it had before when it is not — so a
     * refused key simply does nothing. Shared, so the two apps can never
     * disagree about which key is refused.
     */

    /** A day: at most two digits, never above 31 and never "00". */
    fun typedDay(old: String, new: String): String {
        val digits = new.filter(Char::isDigit)
        return if (digits.length <= 2 && (digits.length < 2 || digits.toInt() in 1..31)) digits else old
    }

    /** A month: at most two digits, never above 12 and never "00". */
    fun typedMonth(old: String, new: String): String {
        val digits = new.filter(Char::isDigit)
        return if (digits.length <= 2 && (digits.length < 2 || digits.toInt() in 1..12)) digits else old
    }

    /**
     * The day box holds all it can: two digits, or one no day can follow —
     * "4" to "9" are the 4th to the 9th, since there is no 40th. The form
     * moves on to the month the moment this is true, so nobody has to.
     */
    fun dayComplete(day: String): Boolean =
        day.length == 2 || (day.length == 1 && day[0] in '4'..'9')

    /**
     * The month box holds all it can: two digits, or one no month can follow —
     * "2" to "9" are February to September, since there is no 20th month.
     */
    fun monthComplete(month: String): Boolean =
        month.length == 2 || (month.length == 1 && month[0] in '2'..'9')

    /**
     * A year: at most four digits, and only while it can still become a year
     * between [MIN_YEAR] and this one. "3" or "21" are refused at the key —
     * nothing typed after them could make them a year of birth — as is any
     * year after the current one.
     */
    fun typedYear(old: String, new: String, nowMillis: Long): String {
        val digits = new.filter(Char::isDigit)
        if (digits.length > 4) return old
        if (digits.isEmpty()) return digits
        var scale = 1
        repeat(4 - digits.length) { scale *= 10 }
        val lowest = digits.toInt() * scale
        val highest = lowest + scale - 1
        return if (highest >= MIN_YEAR && lowest <= toParts(nowMillis).third) digits else old
    }

    /**
     * All three boxes filled with a real date that has not happened yet — said
     * the moment the last digit lands rather than at Save, since the boxes can
     * only refuse a year after this one, not a later day in this one.
     */
    fun isFuture(day: String?, month: String?, year: String?, nowMillis: Long): Boolean {
        val d = day?.trim()?.toIntOrNull() ?: return false
        val m = month?.trim()?.toIntOrNull() ?: return false
        val y = year?.trim()?.takeIf { it.length == 4 }?.toIntOrNull() ?: return false
        if (m !in 1..12 || d !in 1..daysIn(m, y)) return false
        return toEpochMillis(y, m, d) >= nowMillis
    }

    private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

    private fun daysIn(month: Int, year: Int): Int =
        if (month == 2 && isLeap(year)) 29 else DAYS[month - 1]

    /**
     * Days since the epoch, by counting — rather than pulling in a date library
     * for one conversion, or reaching for a platform type `shared` must not see.
     */
    fun toEpochMillis(year: Int, month: Int, day: Int): Long {
        var days = 0L
        if (year >= 1970) {
            for (y in 1970 until year) days += if (isLeap(y)) 366 else 365
        } else {
            for (y in year until 1970) days -= if (isLeap(y)) 366 else 365
        }
        for (m in 1 until month) days += daysIn(m, year)
        days += (day - 1)
        return days * 86_400_000L
    }

    /** Back to three boxes, for editing somebody already stored. */
    fun toParts(epochMillis: Long): Triple<Int, Int, Int> {
        var days = epochMillis.floorDiv(86_400_000L).toInt()
        var year = 1970
        while (true) {
            val len = if (isLeap(year)) 366 else 365
            if (days >= len) { days -= len; year++ } else if (days < 0) { year--; days += if (isLeap(year)) 366 else 365 } else break
        }
        var month = 1
        while (days >= daysIn(month, year)) { days -= daysIn(month, year); month++ }
        return Triple(days + 1, month, year)
    }
}
