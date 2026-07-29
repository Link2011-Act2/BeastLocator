package jp.linkserver.beastlocator

import android.location.Location
import android.os.Build
import android.os.SystemClock
import kotlin.math.max

enum class LocationSampleSource {
    CONTINUOUS,
    LAST_KNOWN,
    GEOFENCE,
    PERSISTED
}

data class LocationSample(
    val position: Destination,
    val accuracyMeters: Float,
    val wallTimeMillis: Long,
    val elapsedRealtimeNanos: Long,
    val ageMillis: Long,
    val isMock: Boolean,
    val source: LocationSampleSource
) {
    fun refreshedAge(nowWallTimeMillis: Long = System.currentTimeMillis()): LocationSample =
        copy(ageMillis = max(ageMillis, max(0L, nowWallTimeMillis - wallTimeMillis)))

    fun isUsableForDisplay(): Boolean =
        position.isValidCoordinate() &&
            accuracyMeters.isFinite() &&
            accuracyMeters in 0f..MAX_DISPLAY_ACCURACY_METERS &&
            ageMillis in 0L..MAX_DISPLAY_AGE_MILLIS &&
            !isMock

    fun isEligibleForArrival(): Boolean =
        isUsableForDisplay() &&
            source != LocationSampleSource.LAST_KNOWN &&
            source != LocationSampleSource.PERSISTED &&
            accuracyMeters <= MAX_ARRIVAL_ACCURACY_METERS &&
            ageMillis <= MAX_ARRIVAL_AGE_MILLIS

    companion object {
        const val MAX_DISPLAY_AGE_MILLIS = 2 * 60_000L
        const val MAX_ARRIVAL_AGE_MILLIS = 20_000L
        const val MAX_DISPLAY_ACCURACY_METERS = 1_000f
        const val MAX_ARRIVAL_ACCURACY_METERS = 50f
    }
}

object LocationSampleFactory {
    fun fromAndroidLocation(
        location: Location,
        source: LocationSampleSource,
        nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
        nowWallTimeMillis: Long = System.currentTimeMillis()
    ): LocationSample? {
        val position = Destination(location.latitude, location.longitude)
        if (!position.isValidCoordinate() || !location.hasAccuracy()) return null
        val accuracy = location.accuracy
        if (!accuracy.isFinite() || accuracy < 0f) return null

        val monotonicAgeMillis = location.elapsedRealtimeNanos
            .takeIf { it > 0L && nowElapsedRealtimeNanos >= it }
            ?.let { (nowElapsedRealtimeNanos - it) / 1_000_000L }
        if (monotonicAgeMillis == null && location.time > nowWallTimeMillis + MAX_CLOCK_SKEW_MILLIS) {
            return null
        }
        val wallAgeMillis = if (location.time > 0L) {
            max(0L, nowWallTimeMillis - location.time)
        } else {
            Long.MAX_VALUE
        }
        val ageMillis = monotonicAgeMillis ?: wallAgeMillis

        return LocationSample(
            position = position,
            accuracyMeters = accuracy,
            wallTimeMillis = location.time.takeIf { it > 0L } ?: nowWallTimeMillis,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            ageMillis = ageMillis,
            isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                location.isMock
            } else {
                @Suppress("DEPRECATION")
                location.isFromMockProvider
            },
            source = source
        ).takeIf { it.isUsableForDisplay() }
    }

    fun fromPersisted(
        position: Destination,
        accuracyMeters: Float,
        wallTimeMillis: Long,
        nowWallTimeMillis: Long = System.currentTimeMillis()
    ): LocationSample? {
        if (wallTimeMillis <= 0L || nowWallTimeMillis + MAX_CLOCK_SKEW_MILLIS < wallTimeMillis) {
            return null
        }
        return LocationSample(
            position = position,
            accuracyMeters = accuracyMeters,
            wallTimeMillis = wallTimeMillis,
            elapsedRealtimeNanos = 0L,
            ageMillis = max(0L, nowWallTimeMillis - wallTimeMillis),
            isMock = false,
            source = LocationSampleSource.PERSISTED
        ).takeIf { it.isUsableForDisplay() }
    }

    private const val MAX_CLOCK_SKEW_MILLIS = 10_000L
}

fun Destination.isValidCoordinate(): Boolean =
    lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

private fun Float.isFinite(): Boolean = !isNaN() && !isInfinite()
