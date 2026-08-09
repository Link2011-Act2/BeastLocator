package jp.linkserver.beastlocator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseChannelDetectorTest {
    @Test
    fun intDevWithSuffixIsRecognized() {
        assertEquals(
            ReleaseChannel.INTDEV,
            ReleaseChannelDetector.detect("0.9.6-IntDev_rev0")
        )
    }

    @Test
    fun schedulerAliasesAndPrioritiesArePreserved() {
        assertEquals(ReleaseChannel.INTDEV, ReleaseChannelDetector.detect("1.0.0-Internal42"))
        assertEquals(ReleaseChannel.INTDEV, ReleaseChannelDetector.detect("Release-IntDev_test"))
        assertEquals(ReleaseChannel.BETA, ReleaseChannelDetector.detect("1.0.0-Beta_2"))
        assertEquals(ReleaseChannel.PRE_RELEASE, ReleaseChannelDetector.detect("1.0.0-Alpha3"))
        assertEquals(ReleaseChannel.RC, ReleaseChannelDetector.detect("1.0.0-RC_rev1"))
        assertEquals(ReleaseChannel.RELEASE, ReleaseChannelDetector.detect("1.0.0-Stable"))
        assertEquals(ReleaseChannel.RELEASE, ReleaseChannelDetector.detect("1.0.0-Release"))
        assertEquals(ReleaseChannel.UNKNOWN, ReleaseChannelDetector.detect("1.0.0"))
    }

    @Test
    fun onlyDevelopmentChannelsExposeDevelopmentControls() {
        assertTrue(ReleaseChannel.INTDEV.exposesDebugControlsByDefault)
        assertTrue(ReleaseChannel.BETA.exposesDebugControlsByDefault)
        assertTrue(ReleaseChannel.PRE_RELEASE.exposesDebugControlsByDefault)
        assertTrue(ReleaseChannel.RC.exposesDebugControlsByDefault)
        assertFalse(ReleaseChannel.RELEASE.exposesDebugControlsByDefault)
        assertFalse(ReleaseChannel.UNKNOWN.exposesDebugControlsByDefault)
    }
}
