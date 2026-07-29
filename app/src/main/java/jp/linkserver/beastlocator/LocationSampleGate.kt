package jp.linkserver.beastlocator

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rejects non-monotonic and physically implausible one-off location jumps.
 * A new cluster is accepted after two mutually consistent samples so a legitimate relocation
 * does not remain blocked behind an old persisted fix.
 */
class LocationSampleGate(
    private val maximumSpeedMetersPerSecond: Double = MAXIMUM_SPEED_METERS_PER_SECOND,
    private val unconditionalGapMillis: Long = UNCONDITIONAL_GAP_MILLIS,
    private val outlierConfirmationGapMillis: Long = OUTLIER_CONFIRMATION_GAP_MILLIS
) {
    private var acceptedSample: LocationSample? = null
    private var pendingOutlier: LocationSample? = null

    @Synchronized
    fun reset(seed: LocationSample? = null) {
        acceptedSample = seed?.refreshedAge()?.takeIf { it.isUsableForDisplay() }
        pendingOutlier = null
    }

    @Synchronized
    fun accept(sample: LocationSample): Boolean {
        val current = sample.refreshedAge()
        if (!current.isUsableForDisplay()) return false
        val previous = acceptedSample
        if (previous == null) {
            acceptAsCurrent(current)
            return true
        }

        val elapsedMillis = elapsedMillis(previous, current)
        if (elapsedMillis <= 0L) return false
        val distanceMeters = distanceMeters(previous.position, current.position)
        if (!distanceMeters.isFinite()) return false

        val accuracyAllowance = previous.accuracyMeters.toDouble() +
            current.accuracyMeters.toDouble() + BASE_POSITION_ALLOWANCE_METERS
        val physicallyPlausibleDistance = accuracyAllowance +
            maximumSpeedMetersPerSecond * (elapsedMillis / 1_000.0)
        if (elapsedMillis >= unconditionalGapMillis ||
            distanceMeters <= physicallyPlausibleDistance
        ) {
            acceptAsCurrent(current)
            return true
        }

        val candidate = pendingOutlier
        if (candidate != null) {
            val candidateGap = elapsedMillis(candidate, current)
            val clusterRadius = max(
                MINIMUM_OUTLIER_CLUSTER_RADIUS_METERS,
                candidate.accuracyMeters.toDouble() +
                    current.accuracyMeters.toDouble() + BASE_POSITION_ALLOWANCE_METERS
            )
            if (candidateGap in 1..outlierConfirmationGapMillis &&
                distanceMeters(candidate.position, current.position) <= clusterRadius
            ) {
                acceptAsCurrent(current)
                return true
            }
        }

        pendingOutlier = current
        return false
    }

    private fun acceptAsCurrent(sample: LocationSample) {
        acceptedSample = sample
        pendingOutlier = null
    }

    private fun elapsedMillis(from: LocationSample, to: LocationSample): Long {
        if (from.elapsedRealtimeNanos > 0L && to.elapsedRealtimeNanos > 0L) {
            if (to.elapsedRealtimeNanos <= from.elapsedRealtimeNanos) return -1L
            return (to.elapsedRealtimeNanos - from.elapsedRealtimeNanos) / 1_000_000L
        }
        return to.wallTimeMillis - from.wallTimeMillis
    }

    private fun distanceMeters(from: Destination, to: Destination): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val deltaLat = lat2 - lat1
        val deltaLng = Math.toRadians(to.lng - from.lng)
        val a = sin(deltaLat / 2.0) * sin(deltaLat / 2.0) +
            cos(lat1) * cos(lat2) * sin(deltaLng / 2.0) * sin(deltaLng / 2.0)
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val MAXIMUM_SPEED_METERS_PER_SECOND = 350.0
        private const val UNCONDITIONAL_GAP_MILLIS = 2 * 60_000L
        private const val OUTLIER_CONFIRMATION_GAP_MILLIS = 15_000L
        private const val BASE_POSITION_ALLOWANCE_METERS = 25.0
        private const val MINIMUM_OUTLIER_CLUSTER_RADIUS_METERS = 100.0
    }
}
