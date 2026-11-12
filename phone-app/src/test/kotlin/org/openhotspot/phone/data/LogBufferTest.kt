package org.openhotspot.phone.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.openhotspot.common.phone.LogLevel

class LogBufferTest {
    @Test fun keepsOnlyTheNewestEntriesInOrder() {
        var t = 0L
        val log = LogBuffer(capacity = 3, now = { t++ }, mirrorToLogcat = false)
        for (i in 1..5) log.add(LogLevel.INFO, "m$i")
        assertEquals(listOf("m3", "m4", "m5"), log.entries.value.map { it.message })
        assertEquals(listOf(2L, 3L, 4L), log.entries.value.map { it.timeMs })
        assertTrue(log.entries.value.map { it.id }.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun clearEmptiesTheBuffer() {
        val log = LogBuffer(mirrorToLogcat = false)
        log.log(LogLevel.WARN, "x")
        log.clear()
        assertTrue(log.entries.value.isEmpty())
    }
}
