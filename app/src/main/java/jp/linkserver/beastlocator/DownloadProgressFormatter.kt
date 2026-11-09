package jp.linkserver.beastlocator

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import jp.linkserver.goldy.ApkProgress

/** Presentation only. Decimal KB/MB; no shared mutable NumberFormat or transfer assumptions. */
internal object DownloadProgressFormatter {
    fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val value = bytes.coerceAtLeast(0)
        val unit = when { value >= 1_000_000 -> 1_000_000; value >= 1_000 -> 1_000; else -> 1 }
        val digits = if (unit == 1) 0 else 2
        val amount = BigDecimal.valueOf(value).divide(BigDecimal.valueOf(unit.toLong()), digits, RoundingMode.HALF_UP)
        return number(locale, digits).format(amount) + when (unit) { 1_000_000 -> " MB"; 1_000 -> " KB"; else -> " B" }
    }

    fun format(progress: ApkProgress, locale: Locale = Locale.getDefault()): String {
        val bytes = progress.bytes.coerceAtLeast(0)
        val transferred = formatBytes(bytes, locale)
        val total = progress.total?.takeIf { it > 0 } ?: return transferred
        var percent = BigDecimal.valueOf(bytes).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP)
        // Rounding must never announce completion before the final byte arrives.
        percent = if (bytes < total) percent.min(BigDecimal("99.99")) else BigDecimal("100.00")
        val localizedPercent = NumberFormat.getPercentInstance(locale).apply {
            minimumFractionDigits = 2; maximumFractionDigits = 2
            isGroupingUsed = false; roundingMode = RoundingMode.HALF_UP
        }.format(percent.movePointLeft(2))
        return "$transferred / ${formatBytes(total, locale)} · $localizedPercent"
    }

    /** null means unknown/invalid total and therefore an indeterminate bar. */
    fun fraction(progress: ApkProgress): Float? {
        val total = progress.total?.takeIf { it > 0 } ?: return null
        val bytes = progress.bytes.coerceAtLeast(0)
        val fraction = (bytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
        return if (bytes < total) minOf(fraction, Math.nextDown(1f)) else 1f
    }

    private fun number(locale: Locale, digits: Int) = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = digits; maximumFractionDigits = digits
        isGroupingUsed = false; roundingMode = RoundingMode.HALF_UP
    }
}
