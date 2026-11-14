package com.hongguo.android

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecommendationHistoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun reloadedHistoryIsBoundedAndOwnersStaySeparate() {
        val directory = temporary.newFolder()
        val history = RecommendationHistory(directory)
        history.remember("account:a", (1..550).map(Int::toString))
        history.remember("anonymous", listOf("900"))
        val restored = RecommendationHistory(directory)
        assertEquals((11..550).map(Int::toString), restored.recent("account:a"))
        assertEquals(listOf("900"), restored.recent("anonymous"))
        assertTrue(restored.recent("account:b").isEmpty())
        val stored = File(directory, "recommendations-v1.json").readText()
        assertFalse(stored.contains("account:a"))
        assertFalse(stored.contains("anonymous"))
    }

    @Test fun unreadableHistoryIsNotOverwritten() {
        val directory = temporary.newFolder()
        val file = File(directory, "recommendations-v1.json")
        file.writeText("{damaged")
        val history = RecommendationHistory(directory)
        assertTrue(history.recent("anonymous").isEmpty())
        history.remember("anonymous", listOf("1"))
        assertEquals("{damaged", file.readText())
    }
}
