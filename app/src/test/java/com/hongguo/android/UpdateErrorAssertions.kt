package com.hongguo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/** Coroutine stack recovery may attach the original safe exception as a cause in JVM debug mode. */
internal fun assertStableUpdateError(error: IOException, expectedCode: String) {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    while (current != null) {
        assertTrue("Exception cause chain must not contain cycles", visited.add(current))
        assertTrue("Only sanitized IO failures may remain in the cause chain", current is IOException)
        assertEquals(expectedCode, current.message)
        current = current.cause
    }
}
