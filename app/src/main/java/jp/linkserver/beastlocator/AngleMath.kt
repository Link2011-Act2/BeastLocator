package jp.linkserver.beastlocator

object AngleMath {
    fun normalize360(value: Float): Float {
        if (!value.isFinite()) return 0f
        val mod = value % 360f
        return if (mod < 0f) mod + 360f else mod
    }

    fun shortestDelta(fromDegrees: Float, toDegrees: Float): Float {
        if (!fromDegrees.isFinite() || !toDegrees.isFinite()) return 0f
        var delta = (toDegrees - fromDegrees) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }
}

private fun Float.isFinite(): Boolean = !isNaN() && !isInfinite()

