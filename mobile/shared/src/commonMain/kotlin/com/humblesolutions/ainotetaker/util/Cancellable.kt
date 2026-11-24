package com.humblesolutions.ainotetaker.util

/**
 * A live subscription, closed by the caller.
 *
 * WHY A CALLBACK AND NOT A `Flow`. A Flow is the natural Kotlin answer and
 * crosses to Swift badly: without SKIE it arrives as an opaque type Swift
 * cannot collect, and the workaround is a hand-written bridge per stream. A
 * callback plus a cancel handle maps to both sides without ceremony — Android
 * wraps it in `callbackFlow`, iOS in an `AsyncStream` or a Combine publisher —
 * and keeps `shared` free of platform concessions.
 */
fun interface Cancellable {
    fun cancel()
}
