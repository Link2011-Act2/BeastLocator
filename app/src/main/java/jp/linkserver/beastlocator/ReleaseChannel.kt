package jp.linkserver.beastlocator

import java.util.Locale

enum class ReleaseChannel(val canonicalName: String) {
    INTDEV("IntDev"),
    BETA("Beta"),
    PRE_RELEASE("PreRelease"),
    RELEASE("Release"),
    UNKNOWN("Unknown");

    val exposesDebugControlsByDefault: Boolean
        get() = this == INTDEV || this == BETA || this == PRE_RELEASE
}

object ReleaseChannelDetector {
    /** Keep channel detection identical to schedulernittc. */
    fun detect(value: String): ReleaseChannel {
        val lower = value.lowercase(Locale.US)
        return when {
            "intdev" in lower || "internal" in lower -> ReleaseChannel.INTDEV
            "beta" in lower -> ReleaseChannel.BETA
            "alpha" in lower || "rc" in lower -> ReleaseChannel.PRE_RELEASE
            "stable" in lower || "release" in lower -> ReleaseChannel.RELEASE
            else -> ReleaseChannel.UNKNOWN
        }
    }
}
