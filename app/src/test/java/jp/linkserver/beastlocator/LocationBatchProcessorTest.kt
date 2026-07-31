package jp.linkserver.beastlocator

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationBatchProcessorTest {
    @Test
    fun processesEveryItemFromOldestMonotonicTimestampToNewest() {
        val processed = mutableListOf<String>()
        val items = listOf(
            Fix("newest", elapsedNanos = 3_000L, wallMillis = 30L),
            Fix("oldest", elapsedNanos = 1_000L, wallMillis = 10L),
            Fix("middle", elapsedNanos = 2_000L, wallMillis = 20L)
        )

        LocationBatchProcessor.processOldestFirst(
            items,
            Fix::elapsedNanos,
            Fix::wallMillis
        ) {
            processed += it.name
            true
        }

        assertEquals(listOf("oldest", "middle", "newest"), processed)
    }

    @Test
    fun fallsBackToWallTimeWhenAnyMonotonicTimestampIsMissing() {
        val items = listOf(
            Fix("newest", elapsedNanos = 1_000L, wallMillis = 30L),
            Fix("oldest", elapsedNanos = 0L, wallMillis = 10L),
            Fix("middle", elapsedNanos = 500L, wallMillis = 20L)
        )

        val ordered = LocationBatchProcessor.oldestFirst(
            items,
            Fix::elapsedNanos,
            Fix::wallMillis
        )

        assertEquals(listOf("oldest", "middle", "newest"), ordered.map(Fix::name))
    }

    @Test
    fun stopsWithoutProcessingNewerItemsWhenConsumerReturnsFalse() {
        val processed = mutableListOf<String>()
        val items = listOf(
            Fix("after-arrival", elapsedNanos = 3_000L, wallMillis = 30L),
            Fix("before-arrival", elapsedNanos = 1_000L, wallMillis = 10L),
            Fix("arrival", elapsedNanos = 2_000L, wallMillis = 20L)
        )

        LocationBatchProcessor.processOldestFirst(
            items,
            Fix::elapsedNanos,
            Fix::wallMillis
        ) {
            processed += it.name
            it.name != "arrival"
        }

        assertEquals(listOf("before-arrival", "arrival"), processed)
    }

    @Test
    fun retainsIntermediateArrivalCandidatesAndStopsAfterConfirmation() {
        val processed = mutableListOf<String>()
        val tracker = ArrivalConfirmationTracker()
        val baseWallTimeMillis = System.currentTimeMillis()
        val items = listOf(
            ArrivalFix("after-arrival", 16_000_000_000L, 120f),
            ArrivalFix("second-candidate", 11_000_000_000L, 30f),
            ArrivalFix("outside", 1_000_000_000L, 200f),
            ArrivalFix("first-candidate", 6_000_000_000L, 40f)
        )

        LocationBatchProcessor.processOldestFirst(
            items,
            ArrivalFix::elapsedNanos,
            { baseWallTimeMillis + it.elapsedNanos / 1_000_000L }
        ) { fix ->
            processed += fix.name
            val observation = tracker.observe(
                sample = LocationSample(
                    position = Destination(35.0, 139.0),
                    accuracyMeters = 10f,
                    wallTimeMillis = baseWallTimeMillis + fix.elapsedNanos / 1_000_000L,
                    elapsedRealtimeNanos = fix.elapsedNanos,
                    ageMillis = 0L,
                    isMock = false,
                    source = LocationSampleSource.CONTINUOUS
                ),
                distanceMeters = fix.distanceMeters,
                destinationGeneration = 1L,
                arrivalRearmRequired = false
            )
            observation != ArrivalObservation.CONFIRMED
        }

        assertEquals(
            listOf("outside", "first-candidate", "second-candidate"),
            processed
        )
    }

    private data class Fix(
        val name: String,
        val elapsedNanos: Long,
        val wallMillis: Long
    )

    private data class ArrivalFix(
        val name: String,
        val elapsedNanos: Long,
        val distanceMeters: Float
    )
}
