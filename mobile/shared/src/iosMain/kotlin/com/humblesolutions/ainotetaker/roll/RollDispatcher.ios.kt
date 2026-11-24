package com.humblesolutions.ainotetaker.roll

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * `Dispatchers.Default` rather than `IO`.
 *
 * On Kotlin/Native both are backed by a worker pool and neither is the main
 * queue, which is the only property this needs. `Default` has been there since
 * multithreaded coroutines landed; `IO` arrived later, and pinning the
 * behaviour to the older of the two costs nothing here.
 */
actual fun rollDispatcher(): CoroutineDispatcher = Dispatchers.Default
