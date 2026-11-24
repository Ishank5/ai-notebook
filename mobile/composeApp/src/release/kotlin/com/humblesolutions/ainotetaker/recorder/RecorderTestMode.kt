package com.humblesolutions.ainotetaker.recorder

import android.content.Context
import com.humblesolutions.ainotetaker.config.RecorderLimits

/**
 * the release side of fast test mode, which does not exist here.
 * No switch, no shorter times: every recording uses [RecorderLimits.STANDARD].
 * The debug build's version is in `src/debug`.
 */
object RecorderTestMode {

    const val available = false

    @Suppress("UNUSED_PARAMETER")
    fun isOn(context: Context): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun toggle(context: Context): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun limits(context: Context): RecorderLimits = RecorderLimits.STANDARD
}
