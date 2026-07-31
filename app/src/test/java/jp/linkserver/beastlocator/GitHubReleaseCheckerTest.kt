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

    @Test
    fun unsafeApkVariantsAreNeverSelected() {
        val selected = selectSafeApkAsset(
            listOf(
                asset("BeastLocator-universal-debug.apk"),
                asset("BeastLocator-universal-unsigned.apk"),
                asset("BeastLocator-androidTest.apk"),
                asset("BeastLocator-test-release.apk"),
                asset("BeastLocatorTestRelease.apk"),
                asset("BeastLocator-benchmark.apk"),
                asset("BeastLocator-universal-release.apk")
            )
        )

        assertEquals("BeastLocator-universal-release.apk", selected?.name)
    }

    @Test
    fun universalProductReleaseApkIsPreferredDeterministically() {
        val selected = selectSafeApkAsset(
            listOf(
                asset("app-release.apk"),
                asset("BeastLocator-arm64-v8a-release.apk"),
                asset("app-universal-release.apk"),
                asset("BeastLocator-universal.apk"),
                asset("BeastLocator-universal-release.apk")
            )
        )

        assertEquals("BeastLocator-universal-release.apk", selected?.name)
    }

    @Test
    fun equallySafeApksAreRejectedAsAmbiguous() {
        val selected = selectSafeApkAsset(
            listOf(
                asset("BeastLocator-universal-release-one.apk"),
                asset("BeastLocator-universal-release-two.apk")
            )
        )

        assertNull(selected)
    }

    @Test
    fun soleSafeApkCanBeSelectedWithoutNamingConvention() {
        val selected = selectSafeApkAsset(
            listOf(
                asset("app.apk"),
                asset("app-debug.apk")
            )
        )

        assertEquals("app.apk", selected?.name)
    }

    @Test
    fun apkForSupportedAbiIsSelected() {
        val selected = selectSafeApkAsset(
            assets = listOf(
                asset("BeastLocator-arm64-v8a-release.apk"),
                asset("BeastLocator-x86-release.apk")
            ),
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        )

        assertEquals("BeastLocator-arm64-v8a-release.apk", selected?.name)
    }

    @Test
    fun soleApkForUnsupportedAbiIsRejected() {
        val selected = selectSafeApkAsset(
            assets = listOf(asset("BeastLocator-arm64-v8a-release.apk")),
            supportedAbis = listOf("x86_64", "x86")
        )

        assertNull(selected)
    }

    @Test
    fun universalApkIsPreferredOverCompatibleAbiApk() {
        val selected = selectSafeApkAsset(
            assets = listOf(
                asset("BeastLocator-arm64-v8a-release.apk"),
                asset("BeastLocator-universal-release.apk")
            ),
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        )

        assertEquals("BeastLocator-universal-release.apk", selected?.name)
    }

    @Test
    fun firstSupportedAbiIsPreferredWhenSeveralAbiSplitsAreCompatible() {
        val selected = selectSafeApkAsset(
            assets = listOf(
                asset("BeastLocator-armeabi-v7a-release.apk"),
                asset("BeastLocator-arm64-v8a-release.apk")
            ),
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        )

        assertEquals("BeastLocator-arm64-v8a-release.apk", selected?.name)
    }

    @Test
    fun x86_64MarkerIsDistinctFromX86Marker() {
        val selected = selectSafeApkAsset(
            assets = listOf(
                asset("BeastLocator-x86-release.apk"),
                asset("BeastLocator-x86_64-release.apk")
            ),
            supportedAbis = listOf("x86_64", "x86")
        )

        assertEquals("BeastLocator-x86_64-release.apk", selected?.name)
    }

    private fun asset(name: String) = GitHubReleaseChecker.GitHubReleaseAsset(
        name = name,
        browserDownloadUrl = "https://github.com/example/project/releases/download/v1/$name"
    )
}
