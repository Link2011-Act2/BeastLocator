package jp.linkserver.beastlocator

import android.content.Context

object ApproachProgressController {
    fun update(
        context: Context,
        store: DestinationStore,
        distanceMeters: Float,
    ) {
        if (!NotificationHelper.isLiveUpdateSupported() ||
            !store.isLiveUpdateEnabled() ||
            store.isDestinationAnswered() ||
            !distanceMeters.isFinite()
        ) {
            clear(context, store)
            return
        }

        val startDistanceMeters = store.getLiveUpdateStartDistanceMeters()
            .coerceIn(MIN_START_DISTANCE_METERS, MAX_START_DISTANCE_METERS)
            .toFloat()
        if (distanceMeters > startDistanceMeters ||
            distanceMeters <= ArrivalConfirmationTracker.ARRIVAL_THRESHOLD_METERS
        ) {
            clear(context, store)
            return
        }

        val arrivalThreshold = ArrivalConfirmationTracker.ARRIVAL_THRESHOLD_METERS
        val anchorDistance = store.getLiveUpdateAnchorDistanceMeters()
            ?.takeIf { it > arrivalThreshold }
            ?: distanceMeters.also(store::setLiveUpdateAnchorDistanceMeters)
        val span = (anchorDistance - arrivalThreshold).coerceAtLeast(1f)
        val progress = (((anchorDistance - distanceMeters) / span) * 100f)
            .toInt()
            .coerceIn(0, 100)
        NotificationHelper.showApproachProgress(context, distanceMeters, progress)
    }

    fun clear(context: Context, store: DestinationStore) {
        NotificationHelper.cancelApproachProgress(context)
        store.clearLiveUpdateAnchorDistanceMeters()
    }

    private const val MIN_START_DISTANCE_METERS = 200
    private const val MAX_START_DISTANCE_METERS = 5_000
}

private fun Float.isFinite(): Boolean = !isNaN() && !isInfinite()
