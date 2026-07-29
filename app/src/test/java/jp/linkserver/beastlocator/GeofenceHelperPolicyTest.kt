package jp.linkserver.beastlocator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceHelperPolicyTest {
    @Test
    fun requestId_roundTripsDestinationGeneration() {
        val generations = listOf(0L, 1L, 42L, Long.MAX_VALUE)

        generations.forEach { generation ->
            val requestId = GeofenceHelper.requestIdForGeneration(generation)
            assertEquals(generation, GeofenceHelper.generationFromRequestId(requestId))
        }
    }

    @Test
    fun generationParser_rejectsLegacyMalformedAndNegativeIds() {
        assertNull(GeofenceHelper.generationFromRequestId("destination_geofence"))
        assertNull(GeofenceHelper.generationFromRequestId("destination_geofence:"))
        assertNull(GeofenceHelper.generationFromRequestId("destination_geofence:-1"))
        assertNull(GeofenceHelper.generationFromRequestId("destination_geofence:1:extra"))
        assertNull(GeofenceHelper.generationFromRequestId("other:1"))
    }

    @Test
    fun retryPolicy_isFiniteAndBacksOff() {
        assertEquals(1_000L, GeofenceHelper.retryDelayMillis(0))
        assertEquals(3_000L, GeofenceHelper.retryDelayMillis(1))
        assertEquals(7_000L, GeofenceHelper.retryDelayMillis(2))
        assertNull(GeofenceHelper.retryDelayMillis(3))
        assertNull(GeofenceHelper.retryDelayMillis(Int.MAX_VALUE))
    }

    @Test
    fun exhaustedRetry_canRestartOnlyAfterCooldown() {
        val exhaustedAt = 10_000L
        val cooldown = GeofenceHelper.RETRY_EXHAUSTION_COOLDOWN_MILLIS

        assertTrue(!GeofenceHelper.retryCooldownElapsed(exhaustedAt, exhaustedAt - 1L))
        assertTrue(!GeofenceHelper.retryCooldownElapsed(exhaustedAt, exhaustedAt + cooldown - 1L))
        assertTrue(GeofenceHelper.retryCooldownElapsed(exhaustedAt, exhaustedAt + cooldown))
    }

    @Test
    fun geofenceRadius_isOnlyTheApproachWakeUpBoundary() {
        assertEquals(150f, GeofenceHelper.GEOFENCE_RADIUS_METERS)
        assertTrue(
            GeofenceHelper.GEOFENCE_RADIUS_METERS >
                ArrivalConfirmationTracker.ARRIVAL_THRESHOLD_METERS
        )
    }
}
