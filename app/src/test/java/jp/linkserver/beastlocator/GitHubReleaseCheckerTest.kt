package jp.linkserver.beastlocator

import jp.linkserver.goldy.*
import org.junit.Assert.*
import org.junit.Test

/** Migration expectations: selection uses the official app profile; no host ABI or version parser. */
class GitHubReleaseCheckerTest {
    private fun release(tag: String, vararg names: String) = GitHubRelease(100, tag,
        url="https://github.com/Link2011-Act2/BeastLocator/releases/tag/$tag",
        assets=names.mapIndexed { index, name -> GitHubAsset(index + 1L, name,
            "https://github.com/Link2011-Act2/BeastLocator/releases/download/$tag/$name", null, null) })
    private fun outcome(current: String, vararg tags: String, preview: Boolean = false) = ReleaseSelector.select(
        ReleaseCatalog(tags.map(::release)), InstalledVersion(current, 1), ApkUpdateProfile.policy,
        updateOptions(current, preview, null))
    @Test fun newerSemanticVersionIsDetected() {
        assertTrue(outcome("0.9.6-IntDev_rev99", "v0.9.7-IntDev_rev0") is CheckOutcome.Available)
        assertEquals(CheckOutcome.UpToDate, outcome("0.9.6-IntDev_rev0", "v0.9.5-Release"))
    }
    @Test fun channelPromotionAtSameVersionIsDetected() {
        assertTrue(outcome("0.9.6-IntDev_rev8", "v0.9.6-Beta") is CheckOutcome.Available)
        assertEquals(CheckOutcome.UpToDate, outcome("0.9.6-Beta", "v0.9.6-IntDev_rev8"))
    }
    @Test fun appRevisionSuffixIsComparedNumerically() {
        assertTrue(outcome("0.9.6-IntDev_rev9", "v0.9.6-IntDev_rev12") is CheckOutcome.Available)
        assertEquals(CheckOutcome.UpToDate, outcome("0.9.6-IntDev_rev12", "v0.9.6-IntDev_rev9"))
    }
    @Test fun publishedReleaseWithoutApkRemainsReadableButCannotDownload() {
        assertTrue(outcome("0.9.0-Release", "v1.0.0-Release") is CheckOutcome.Available)
        assertNull(ApkUpdateProfile.selection.select(release("v1.0.0-Release")))
    }
    @Test fun showLatestTestingCanSelectAnOlderReleaseOnlyOnIntDev() {
        assertTrue(outcome("2.0.0-IntDev", "v1.0.0-Release", preview=true) is CheckOutcome.Available)
        assertEquals(CheckOutcome.UpToDate, outcome("2.0.0-Release", "v1.0.0-Release", preview=true))
    }
    @Test fun multipleApksIncludingDebugAreRejectedAsAmbiguous() {
        assertNull(ApkUpdateProfile.selection.select(release("v1.0.0", "debug.apk", "release.apk")))
    }
    @Test fun universalAndAbiApksRequireAnExplicitFuturePolicy() {
        assertNull(ApkUpdateProfile.selection.select(release("v1.0.0", "universal.apk", "arm64.apk")))
    }
    @Test fun equallyNamedApksAreRejectedAsAmbiguous() {
        assertNull(ApkUpdateProfile.selection.select(release("v1.0.0", "a.apk", "b.apk")))
    }
    @Test fun soleApkCanBeSelectedWithoutNamingConvention() {
        assertEquals("app.apk", ApkUpdateProfile.selection.select(release("v1.0.0", "app.apk"))?.name)
    }
    @Test fun soleApkWithAbiNameUsesSinglePolicyAndStillRequiresVerification() {
        assertEquals("arm64.apk", ApkUpdateProfile.selection.select(release("v1.0.0", "arm64.apk"))?.name)
    }
    @Test fun nonApkAssetsAreNotSelected() {
        assertNull(ApkUpdateProfile.selection.select(release("v1.0.0", "notes.txt", "source.zip")))
    }
    @Test fun nonApkSidecarsDoNotMakeOneApkAmbiguous() {
        assertEquals("app.apk", ApkUpdateProfile.selection.select(release("v1.0.0", "app.apk", "app.apk.sha256"))?.name)
    }
    @Test fun multipleSupportedAbisAreNotGuessedByHost() {
        assertNull(ApkUpdateProfile.selection.select(release("v1.0.0", "arm64.apk", "armeabi.apk")))
    }
    @Test fun x86AndX64AssetsAreNotGuessedByHost() {
        assertNull(ApkUpdateProfile.selection.select(release("v1.0.0", "x86.apk", "x86_64.apk")))
    }
}
