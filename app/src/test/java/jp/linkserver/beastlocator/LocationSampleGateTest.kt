package jp.linkserver.beastlocator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSampleGateTest {
    private val baseWallTimeMillis = System.currentTimeMillis()

    @Test
    fun rejectsSingleTeleportThenAcceptsConsistentNewCluster() {
        val gate = LocationSampleGate()
        assertTrue(gate.accept(sample(35.0, 139.0, 1_000L)))
        assertFalse(gate.accept(sample(36.0, 140.0, 2_000L)))
        assertTrue(gate.accept(sample(36.0001, 140.0001, 3_000L)))
    }

    @Test
    fun rejectsDuplicateAndOutOfOrderTimestamps() {
        val gate = LocationSampleGate()
        assertTrue(gate.accept(sample(35.0, 139.0, 2_000L)))
        assertFalse(gate.accept(sample(35.0, 139.0, 2_000L)))
        assertFalse(gate.accept(sample(35.0, 139.0, 1_000L)))
    }

    @Test
    fun acceptsPlausibleMovementAndLongGapRelocation() {
        val gate = LocationSampleGate()
        assertTrue(gate.accept(sample(35.0, 139.0, 1_000L)))
        assertTrue(gate.accept(sample(35.001, 139.001, 3_000L)))
        assertTrue(gate.accept(sample(36.0, 140.0, 123_000L)))
    }

    private fun sample(lat: Double, lng: Double, timeMillis: Long) = LocationSample(
        position = Destination(lat, lng),
        accuracyMeters = 10f,
        wallTimeMillis = baseWallTimeMillis + timeMillis,
        elapsedRealtimeNanos = timeMillis * 1_000_000L,
        ageMillis = 0L,
        isMock = false,
        source = LocationSampleSource.CONTINUOUS
    )
}
