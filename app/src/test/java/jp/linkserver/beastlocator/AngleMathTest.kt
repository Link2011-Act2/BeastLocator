package jp.linkserver.beastlocator

import org.junit.Assert.assertEquals
import org.junit.Test

class AngleMathTest {
    @Test
    fun wrapsAcrossNorthUsingShortestPath() {
        assertEquals(2f, AngleMath.shortestDelta(359f, 1f), 0.0001f)
        assertEquals(-2f, AngleMath.shortestDelta(1f, 359f), 0.0001f)
    }

    @Test
    fun normalizesNegativeAndLargeAngles() {
        assertEquals(359f, AngleMath.normalize360(-1f), 0.0001f)
        assertEquals(1f, AngleMath.normalize360(721f), 0.0001f)
    }
}

