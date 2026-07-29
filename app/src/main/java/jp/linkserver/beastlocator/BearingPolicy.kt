package jp.linkserver.beastlocator

object BearingPolicy {
    fun isReliable(distanceMeters: Float, accuracyMeters: Float): Boolean {
        if (!distanceMeters.isFinite() || !accuracyMeters.isFinite()) return false
        if (distanceMeters < MIN_RELIABLE_DISTANCE_METERS) return false
        return accuracyMeters >= 0f &&
            accuracyMeters <= distanceMeters * MAX_ACCURACY_TO_DISTANCE_RATIO
    }

    const val MIN_RELIABLE_DISTANCE_METERS = 75f
    const val MAX_ACCURACY_TO_DISTANCE_RATIO = 0.35f
}

private fun Float.isFinite(): Boolean = !isNaN() && !isInfinite()
