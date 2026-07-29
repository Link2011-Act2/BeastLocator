package jp.linkserver.beastlocator

enum class ArrivalObservation {
    REJECTED,
    OUTSIDE,
    CANDIDATE,
    CONFIRMED
}

class ArrivalConfirmationTracker(
    private val arrivalThresholdMeters: Float = ARRIVAL_THRESHOLD_METERS,
    private val exitThresholdMeters: Float = ARRIVAL_EXIT_THRESHOLD_METERS,
    private val requiredSamples: Int = REQUIRED_ARRIVAL_SAMPLES,
    private val maximumSampleGapMillis: Long = MAX_ARRIVAL_SAMPLE_GAP_MILLIS
) {
    private var candidateGeneration: Long? = null
    private var candidateCount = 0
    private var lastCandidateWallTimeMillis = 0L

    @Synchronized
    fun observe(
        sample: LocationSample,
        distanceMeters: Float,
        destinationGeneration: Long,
        arrivalRearmRequired: Boolean
    ): ArrivalObservation {
        if (!distanceMeters.isFinite() || !sample.isEligibleForArrival()) {
            resetCandidate()
            return ArrivalObservation.REJECTED
        }
        if (arrivalRearmRequired) {
            resetCandidate()
            return ArrivalObservation.OUTSIDE
        }
        if (distanceMeters > arrivalThresholdMeters) {
            resetCandidate()
            return ArrivalObservation.OUTSIDE
        }

        val continuesCandidate = candidateGeneration == destinationGeneration &&
            lastCandidateWallTimeMillis > 0L &&
            sample.wallTimeMillis > lastCandidateWallTimeMillis &&
            sample.wallTimeMillis - lastCandidateWallTimeMillis <= maximumSampleGapMillis
        candidateCount = if (continuesCandidate) candidateCount + 1 else 1
        candidateGeneration = destinationGeneration
        lastCandidateWallTimeMillis = sample.wallTimeMillis

        if (candidateCount < requiredSamples) return ArrivalObservation.CANDIDATE
        resetCandidate()
        return ArrivalObservation.CONFIRMED
    }

    @Synchronized
    fun shouldClearRearm(distanceMeters: Float): Boolean =
        distanceMeters.isFinite() && distanceMeters >= exitThresholdMeters

    @Synchronized
    fun reset() {
        resetCandidate()
    }

    private fun resetCandidate() {
        candidateGeneration = null
        candidateCount = 0
        lastCandidateWallTimeMillis = 0L
    }

    companion object {
        const val ARRIVAL_THRESHOLD_METERS = 50f
        const val ARRIVAL_EXIT_THRESHOLD_METERS = 75f
        const val REQUIRED_ARRIVAL_SAMPLES = 2
        const val MAX_ARRIVAL_SAMPLE_GAP_MILLIS = 15_000L
    }
}

object SharedArrivalConfirmation {
    private val tracker = ArrivalConfirmationTracker()

    fun observe(
        sample: LocationSample,
        distanceMeters: Float,
        destinationGeneration: Long,
        arrivalRearmRequired: Boolean
    ): ArrivalObservation = tracker.observe(
        sample,
        distanceMeters,
        destinationGeneration,
        arrivalRearmRequired
    )

    fun shouldClearRearm(distanceMeters: Float): Boolean = tracker.shouldClearRearm(distanceMeters)

    fun reset() = tracker.reset()
}

private fun Float.isFinite(): Boolean = !isNaN() && !isInfinite()
