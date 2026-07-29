package jp.linkserver.beastlocator

import android.content.Context
import android.os.SystemClock

object DistanceEventProcessor {
    private var generation: Long? = null
    private var previousDistanceMeters: Float? = null
    private var lowestObservedIntervalBucket: Int? = null
    private var hasPlayed114514 = false
    private var lastIntervalSoundElapsedRealtime = 0L

    @Synchronized
    fun process(
        context: Context,
        store: DestinationStore,
        distanceMeters: Float,
        soundPlayer: ((Int) -> Unit)? = null
    ) {
        if (!distanceMeters.isFinite() || distanceMeters < 0f || store.isDestinationAnswered()) {
            reset(store.getDestinationGeneration())
            return
        }
        val currentGeneration = store.getDestinationGeneration()
        if (generation != currentGeneration) reset(currentGeneration)
        val playSound = soundPlayer ?: { rawResId: Int ->
            SoundEffectPlayer.play(context.applicationContext, rawResId)
        }

        val previous = previousDistanceMeters
        if (store.isDistance114514SoundEnabled() &&
            !hasPlayed114514 &&
            previous != null &&
            previous > DISTANCE_114514_ENTER_THRESHOLD_METERS &&
            distanceMeters <= DISTANCE_114514_ENTER_THRESHOLD_METERS
        ) {
            hasPlayed114514 = true
            playSound(R.raw.distance_114514km)
        }

        if (store.isDistanceIntervalSoundEnabled()) {
            val interval = store.getDistanceIntervalSoundMeters().coerceIn(100, 5_000).toFloat()
            val bucket = (distanceMeters / interval).toInt()
            val lowest = lowestObservedIntervalBucket
            val now = SystemClock.elapsedRealtime()
            if (lowest != null &&
                bucket < lowest &&
                now - lastIntervalSoundElapsedRealtime >= MIN_INTERVAL_SOUND_GAP_MILLIS
            ) {
                playSound(R.raw.distance_interval_kankaku)
                lastIntervalSoundElapsedRealtime = now
            }
            lowestObservedIntervalBucket = if (lowest == null) bucket else minOf(lowest, bucket)
        } else {
            lowestObservedIntervalBucket = null
        }
        previousDistanceMeters = distanceMeters
    }

    @Synchronized
    fun reset(destinationGeneration: Long? = null) {
        generation = destinationGeneration
        previousDistanceMeters = null
        lowestObservedIntervalBucket = null
        hasPlayed114514 = false
        lastIntervalSoundElapsedRealtime = 0L
    }

    private const val DISTANCE_114514_METERS = 114_514f
    private const val DISTANCE_MATCH_TOLERANCE_METERS = 80f
    private const val DISTANCE_114514_ENTER_THRESHOLD_METERS =
        DISTANCE_114514_METERS + DISTANCE_MATCH_TOLERANCE_METERS
    private const val MIN_INTERVAL_SOUND_GAP_MILLIS = 30_000L
}

private fun Float.isFinite(): Boolean = !isNaN() && !isInfinite()
