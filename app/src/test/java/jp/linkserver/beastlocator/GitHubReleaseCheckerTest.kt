package jp.linkserver.beastlocator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseCheckerTest {
    @Test
    fun newerSemanticVersionIsDetected() {
        assertTrue(isNewerRelease("v0.9.7-IntDev_rev0", "0.9.6-IntDev_rev99"))
        assertFalse(isNewerRelease("v0.9.5-Release", "0.9.6-IntDev_rev0"))
    }

    @Test
    fun channelPromotionAtSameVersionIsDetected() {
        assertTrue(isNewerRelease("v0.9.6-Beta", "0.9.6-IntDev_rev8"))
        assertFalse(isNewerRelease("v0.9.6-Release", "0.9.6-Stable"))
        assertFalse(isNewerRelease("v0.9.6-IntDev_rev8", "0.9.6-Beta"))
    }

    @Test
    fun appRevisionSuffixIsComparedNumerically() {
        assertTrue(isNewerRelease("v0.9.6-IntDev_rev12", "0.9.6-IntDev_rev9"))
        assertFalse(isNewerRelease("v0.9.6-IntDev_rev9", "0.9.6-IntDev_rev12"))
        assertFalse(isNewerRelease("v0.9.6-IntDev_rev12", "0.9.6-IntDev_rev12"))
    }

    @Test
    fun onlyHttpsGitHubDownloadHostsAreTrusted() {
        assertTrue(
            isTrustedGitHubDownloadUrl(
                "https://github.com/Link2011-Act2/BeastLocator/releases/download/v1/app.apk"
            )
        )
        assertTrue(
            isTrustedGitHubDownloadUrl(
                "https://release-assets.githubusercontent.com/github-production-release-asset/app.apk"
            )
        )
        assertFalse(isTrustedGitHubDownloadUrl("http://github.com/example/app.apk"))
        assertFalse(isTrustedGitHubDownloadUrl("https://github.com.example.org/app.apk"))
    }

    @Test
    fun showLatestTestingCanSelectAnOlderRelease() {
        val release = AppUpdateInfo(
            tagName = "v0.9.5-Beta",
            channel = ReleaseChannel.BETA,
            releaseNotes = "notes",
            releaseUrl = "https://github.com/Link2011-Act2/BeastLocator/releases/tag/v0.9.5-Beta",
            apkAssetName = "BeastLocator.apk",
            apkDownloadUrl = null,
            isPrerelease = true
        )
        assertNull(selectLatestUpdate(listOf(release), "0.9.6-IntDev_rev0", false))
        assertEquals(
            release,
            selectLatestUpdate(listOf(release), "0.9.6-IntDev_rev0", true)
        )
    }
}
