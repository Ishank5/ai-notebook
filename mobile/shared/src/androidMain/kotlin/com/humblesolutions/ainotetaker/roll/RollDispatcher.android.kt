package com.humblesolutions.ainotetaker.roll

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun rollDispatcher(): CoroutineDispatcher = Dispatchers.IO
