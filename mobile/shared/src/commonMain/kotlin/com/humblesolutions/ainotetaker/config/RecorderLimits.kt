package com.humblesolutions.ainotetaker.config

/**
 * the planned length's numbers, as one value the phones pass in.
 *
 * A VALUE, NOT CONSTANTS READ IN PLACE, so a debug build can hand the recorder
 * shorter times to try the warning, Keep recording and the stop on a real phone
 * in minutes. Release builds only ever pass [STANDARD]; the shorter numbers live
 * in each phone's debug code, not here.
 *
 * Every time is audio time, as `RecordingClock` counts it: a pause does not use
 * up the plan, the grace window or the cap.
 */
data class RecorderLimits(
    /** The lengths offered before Record, shortest first. */
    val choicesMs: List<Long> = RecorderConfig.PLAN_CHOICES_MS,
    /** What Instant start, and a length row nobody touched, use. */
    val defaultMs: Long = RecorderConfig.DEFAULT_PLAN_MS,
    /** Nothing records past this. */
    val capMs: Long = RecorderConfig.MAX_DURATION_MS,
    /** How far Keep recording moves the planned end, never past [capMs]. */
    val extendByMs: Long = RecorderConfig.EXTEND_BY_MS,
    /** How long it carries on after the warning, and how early the warning comes before [capMs]. */
    val graceMs: Long = RecorderConfig.GRACE_MS,
) {
    init {
        require(capMs > graceMs && graceMs > 0) { "the cap must be longer than the grace window" }
        require(choicesMs.isNotEmpty() && choicesMs.all { it in 1..capMs }) { "every choice must fit under the cap" }
        require(defaultMs in choicesMs) { "the default must be one of the choices" }
    }

    /** One of the choices, or the default for anything else (a stale value, a missing extra). */
    fun planOrDefault(planMs: Long?): Long = planMs?.takeIf { it in choicesMs } ?: defaultMs

    companion object {
        /** 1 h 30 m by default, 3 h at most, Keep recording adds 1 h 30 m, a 5-minute grace window. */
        val STANDARD = RecorderLimits()
    }
}
