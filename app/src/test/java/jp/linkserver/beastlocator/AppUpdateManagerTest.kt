package jp.linkserver.beastlocator

import jp.linkserver.goldy.*
import org.junit.Assert.*
import org.junit.Test

/** Host configuration tests. Gold's signature/rotation protocol tests are reused at the pinned SHA. */
class AppUpdateManagerTest {
    @Test fun appProfileUsesCommonCorePolicyAndOneApk() {
        assertEquals(GitHubRepository("Link2011-Act2", "BeastLocator"), ApkUpdateProfile.repository)
        assertEquals(UpdatePolicies.beastLocator(), ApkUpdateProfile.policy)
        assertSame(VersionPolicies.Common, ApkUpdateProfile.policy.versions)
        assertSame(ApkSelectionPolicies.Single, ApkUpdateProfile.selection)
        assertTrue(ApkUpdateProfile.policy.feed is ReleaseFeed.ListReleases)
    }
    @Test fun downloadConfigurationKeepsStrictBindingAndSizeLimit() {
        val config = ApkUpdateProfile.downloadConfig("1.0.0-RC1")
        assertSame(ApkSelectionPolicies.Single, config.selection)
        assertSame(ApkVersionBindings.Published, config.versionBinding)
        assertEquals(256L * 1024 * 1024, config.maxBytes)
    }
    @Test fun developerOptionsAreGatedByActualInstalledChannel() {
        for (name in listOf("1.0.0-Release", "1.0.0-RC1", "1.0.0-Beta2", "invalid"))
            assertEquals(DeveloperOptions(), updateOptions(name, true, "0.1.0-IntDev").developer)
    }
    @Test fun intDevPreviewPreservesVersionOverrideWithoutChangingApkBinding() {
        val options = updateOptions("1.0.0-IntDev_rev3", true, " 0.1.0-IntDev ")
        assertTrue(options.developer.ignoreVersionComparison)
        assertEquals("0.1.0-IntDev", options.developer.currentVersionOverride)
        assertSame(ApkVersionBindings.Published, ApkUpdateProfile.downloadConfig("1.0.0-IntDev_rev3").versionBinding)
    }
    @Test fun emptyOverrideDoesNotReplaceInstalledVersion() {
        assertNull(updateOptions("1.0.0-IntDev", false, "  ").developer.currentVersionOverride)
        assertFalse(updateOptions("1.0.0-IntDev", false, null).developer.ignoreVersionComparison)
    }
    @Test fun transferExecutionIsSeparateFromLocationAndSound() {
        assertEquals("beast-apk-update-transfer-v1", ApkUpdateProfile.WORK_NAME)
        assertEquals("beast-apk-updates", ApkUpdateProfile.JOB_NAMESPACE)
        assertNotEquals("destination_channel", ApkUpdateProfile.CHANNEL)
        assertNotEquals("sound_monitor_channel", ApkUpdateProfile.CHANNEL)
        assertTrue(ApkUpdateProfile.NOTIFICATION_ID !in setOf(1001, 1514, 1515))
    }
}
