package com.humblesolutions.ainotetaker.roll

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Where a roll query runs.
 *
 * NEVER THE MAIN THREAD. A prefix scan of 200k rows is fast, not free, and the
 * browse screen issues one per keystroke — on the main thread that is a dropped
 * frame every time somebody types.
 *
 * An expect/actual rather than `Dispatchers.IO` in common code, because that
 * dispatcher's availability on Kotlin/Native has moved between coroutines
 * versions and a compile error on one target is a poor reason to restructure a
 * repository.
 */
expect fun rollDispatcher(): CoroutineDispatcher
