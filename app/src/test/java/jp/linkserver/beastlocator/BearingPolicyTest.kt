package jp.linkserver.beastlocator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BearingPolicyTest {
    @Test
    fun holdsBearingNearDestination() {
        assertFalse(BearingPolicy.isReliable(50f, 3f))
        assertTrue(BearingPolicy.isReliable(100f, 10f))
    }

    @Test
    fun holdsBearingWhenAccuracyIsTooLargeForDistance() {
        assertFalse(BearingPolicy.isReliable(1_000f, 500f))
        assertTrue(BearingPolicy.isReliable(1_000f, 100f))
        assertFalse(BearingPolicy.isReliable(Float.NaN, 10f))
    }
}
