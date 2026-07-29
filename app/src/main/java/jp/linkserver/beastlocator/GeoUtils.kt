package jp.linkserver.beastlocator

import android.location.Location
import android.location.Geocoder
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {
    private const val LAND_ONLY_MAX_RETRIES = 12
    private val WATER_KEYWORDS = listOf("sea", "ocean", "bay", "gulf", "channel", "海", "洋", "湾", "灘")

    fun randomDestination(center: Location, radiusKm: Float): Destination {
        val distanceM = sqrt(Math.random()) * radiusKm * 1000.0
        val bearingRad = Math.toRadians(Math.random() * 360.0)
        val earthRadius = 6_371_000.0

        val lat1 = Math.toRadians(center.latitude)
        val lon1 = Math.toRadians(center.longitude)
        val angularDistance = distanceM / earthRadius

        val lat2 = asin(
            sin(lat1) * cos(angularDistance) +
                cos(lat1) * sin(angularDistance) * cos(bearingRad)
        )
        val lon2 = lon1 + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )

        return Destination(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    fun randomDestinationWithLandFilter(
        context: android.content.Context,
        center: Location,
        radiusKm: Float,
        landOnlyEnabled: Boolean
    ): Destination {
        if (!landOnlyEnabled) return randomDestination(center, radiusKm)

        var fallback = randomDestination(center, radiusKm)
        repeat(LAND_ONLY_MAX_RETRIES) {
            val candidate = randomDestination(center, radiusKm)
            fallback = candidate
            if (isLikelyLand(context, candidate)) {
                return candidate
            }
        }
        return fallback
    }

    fun distanceMeters(from: Destination, to: Destination): Float {
        val l1 = Location("from").apply {
            latitude = from.lat
            longitude = from.lng
        }
        val l2 = Location("to").apply {
            latitude = to.lat
            longitude = to.lng
        }
        return l1.distanceTo(l2)
    }

    fun bearingDegrees(from: Destination, to: Destination): Float {
        val l1 = Location("from").apply {
            latitude = from.lat
            longitude = from.lng
        }
        val l2 = Location("to").apply {
            latitude = to.lat
            longitude = to.lng
        }
        var value = l1.bearingTo(l2)
        if (value < 0f) value += 360f
        return value
    }

    fun formatDistance(distanceMeters: Float): String {
        return if (distanceMeters >= 1000f) {
            String.format(Locale.getDefault(), "%.2f km", distanceMeters / 1000f)
        } else {
            "${distanceMeters.toInt()} m"
        }
    }

    fun cardinalFromBearing(bearing: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((bearing + 22.5f) % 360f) / 45f).toInt()
        return dirs[idx]
    }

    private fun isLikelyLand(context: android.content.Context, destination: Destination): Boolean {
        if (!Geocoder.isPresent()) return true
        return try {
            val geocoder = Geocoder(context, Locale.JAPAN)
            @Suppress("DEPRECATION")
            val address = geocoder.getFromLocation(destination.lat, destination.lng, 1)
                ?.firstOrNull()
                ?: return false

            val addressText = buildString {
                append(address.getAddressLine(0) ?: "")
                append(' ')
                append(address.featureName ?: "")
                append(' ')
                append(address.locality ?: "")
                append(' ')
                append(address.subLocality ?: "")
                append(' ')
                append(address.adminArea ?: "")
                append(' ')
                append(address.countryName ?: "")
            }.lowercase(Locale.ROOT)

            if (WATER_KEYWORDS.any { addressText.contains(it) }) {
                return false
            }
            address.countryName?.isNotBlank() == true ||
                address.adminArea?.isNotBlank() == true ||
                address.locality?.isNotBlank() == true
        } catch (_: Exception) {
            true
        }
    }
}

