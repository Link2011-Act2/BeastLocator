package jp.linkserver.beastlocator

import java.text.Normalizer

object DestinationInputParser {
    fun parse(latitudeText: String, longitudeText: String): Destination? {
        val latitude = parseLatitude(latitudeText) ?: return null
        val longitude = parseLongitude(longitudeText) ?: return null
        return Destination(latitude, longitude)
    }

    fun parseLatitude(text: String): Double? =
        parseNumber(text)?.takeIf { it in -90.0..90.0 }

    fun parseLongitude(text: String): Double? =
        parseNumber(text)?.takeIf { it in -180.0..180.0 }

    private fun parseNumber(raw: String): Double? {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .trim()
            .replace('\u2212', '-')
            .replace(',', '.')
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
