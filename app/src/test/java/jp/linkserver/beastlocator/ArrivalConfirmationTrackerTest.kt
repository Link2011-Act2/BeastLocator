package jp.linkserver.beastlocator

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrivalConfirmationTrackerTest {
    private val destination = Destination(35.0, 139.0)

    @Test
    fun requiresTwoFreshAccurateSamples() {
        val tracker = ArrivalConfirmationTracker()
        assertEquals(
            ArrivalObservation.CANDIDATE,
            tracker.observe(sample(1_000L), 40f, 1L, false)
        )
        assertEquals(
            ArrivalObservation.CONFIRMED,
            tracker.observe(sample(5_000L), 35f, 1L, false)
        )
    }

    @Test
    fun rejectsStaleOrLowAccuracySample() {
        val tracker = ArrivalConfirmationTracker()
        assertEquals(
            ArrivalObservation.REJECTED,
            tracker.observe(sample(1_000L, ageMillis = 30_000L), 20f, 1L, false)
        )
        assertEquals(
            ArrivalObservation.REJECTED,
            tracker.observe(sample(2_000L, accuracyMeters = 100f), 20f, 1L, false)
        )
    }

    @Test
    fun destinationGenerationBreaksCandidateSequence() {
        val tracker = ArrivalConfirmationTracker()
        assertEquals(ArrivalObservation.CANDIDATE, tracker.observe(sample(1_000L), 20f, 1L, false))
        assertEquals(ArrivalObservation.CANDIDATE, tracker.observe(sample(2_000L), 20f, 2L, false))
    }

    @Test
    fun rearmRequiresLeavingHysteresisRadius() {
        val tracker = ArrivalConfirmationTracker()
        assertEquals(ArrivalObservation.OUTSIDE, tracker.observe(sample(1_000L), 20f, 1L, true))
        assertEquals(false, tracker.shouldClearRearm(74.9f))
        assertEquals(true, tracker.shouldClearRearm(75f))
    }

    private fun sample(
        wallTimeMillis: Long,
        ageMillis: Long = 0L,
        accuracyMeters: Float = 10f
    ) = LocationSample(
        position = destination,
        accuracyMeters = accuracyMeters,
        wallTimeMillis = wallTimeMillis,
        elapsedRealtimeNanos = wallTimeMillis * 1_000_000L,
        ageMillis = ageMillis,
        isMock = false,
        source = LocationSampleSource.CONTINUOUS
    )
}

