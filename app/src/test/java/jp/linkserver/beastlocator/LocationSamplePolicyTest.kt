package jp.linkserver.beastlocator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSamplePolicyTest {
    @Test
    fun displayAndArrivalUseDifferentFreshnessAndAccuracyLimits() {
        val displayOnly = sample(ageMillis = 60_000L, accuracyMeters = 500f)
        assertTrue(displayOnly.isUsableForDisplay())
        assertFalse(displayOnly.isEligibleForArrival())

        val arrival = sample(ageMillis = 5_000L, accuracyMeters = 20f)
        assertTrue(arrival.isEligibleForArrival())
    }

    @Test
    fun mockAndInvalidCoordinatesAreRejected() {
        assertFalse(sample(isMock = true).isUsableForDisplay())
        assertFalse(sample(position = Destination(Double.NaN, 139.0)).isUsableForDisplay())
    }

    @Test
    fun explicitlyAcceptedDevelopmentMockCanDriveDisplayAndArrival() {
        val acceptedMock = sample(
            isMock = true,
            mockAcceptedForDevelopment = true
        )

        assertTrue(acceptedMock.isUsableForDisplay())
        assertTrue(acceptedMock.isEligibleForArrival())
    }

    @Test
    fun refreshedAgeCannotMakeASampleYounger() {
        val initial = sample(wallTimeMillis = 1_000L, ageMillis = 500L)
        assertTrue(initial.refreshedAge(5_000L).ageMillis == 4_000L)
        assertTrue(initial.refreshedAge(1_100L).ageMillis == 500L)
    }

    @Test
    fun cachedSourcesCannotConfirmArrival() {
        assertFalse(sample(source = LocationSampleSource.LAST_KNOWN).isEligibleForArrival())
        assertFalse(sample(source = LocationSampleSource.PERSISTED).isEligibleForArrival())
        assertTrue(sample(source = LocationSampleSource.CONTINUOUS).isEligibleForArrival())
        assertTrue(sample(source = LocationSampleSource.GEOFENCE).isEligibleForArrival())
    }

    private fun sample(
        position: Destination = Destination(35.0, 139.0),
        wallTimeMillis: Long = 10_000L,
        ageMillis: Long = 0L,
        accuracyMeters: Float = 10f,
        isMock: Boolean = false,
        mockAcceptedForDevelopment: Boolean = false,
        source: LocationSampleSource = LocationSampleSource.CONTINUOUS
    ) = LocationSample(
        position = position,
        accuracyMeters = accuracyMeters,
        wallTimeMillis = wallTimeMillis,
        elapsedRealtimeNanos = 1L,
        ageMillis = ageMillis,
        isMock = isMock,
        source = source,
        mockAcceptedForDevelopment = mockAcceptedForDevelopment
    )
}
