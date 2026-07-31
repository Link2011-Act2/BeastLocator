package jp.linkserver.beastlocator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundLocationPermissionGuidePolicyTest {
    @Test
    fun `shows only when active monitoring needs background permission`() {
        assertTrue(
            BackgroundLocationPermissionGuidePolicy.shouldShow(
                isAtLeastAndroidQ = true,
                hasFineLocation = true,
                hasBackgroundLocation = false,
                isBackgroundMonitoringActive = true,
                wasGuideShownRecently = false
            )
        )
    }

    @Test
    fun `does not show on pre Q devices`() {
        assertFalse(eligible(isAtLeastAndroidQ = false))
    }

    @Test
    fun `does not show without fine location`() {
        assertFalse(eligible(hasFineLocation = false))
    }

    @Test
    fun `does not show when background location is already granted`() {
        assertFalse(eligible(hasBackgroundLocation = true))
    }

    @Test
    fun `does not show when monitoring is inactive or guide is cooling down`() {
        assertFalse(eligible(isBackgroundMonitoringActive = false))
        assertFalse(eligible(wasGuideShownRecently = true))
    }

    private fun eligible(
        isAtLeastAndroidQ: Boolean = true,
        hasFineLocation: Boolean = true,
        hasBackgroundLocation: Boolean = false,
        isBackgroundMonitoringActive: Boolean = true,
        wasGuideShownRecently: Boolean = false
    ): Boolean = BackgroundLocationPermissionGuidePolicy.shouldShow(
        isAtLeastAndroidQ = isAtLeastAndroidQ,
        hasFineLocation = hasFineLocation,
        hasBackgroundLocation = hasBackgroundLocation,
        isBackgroundMonitoringActive = isBackgroundMonitoringActive,
        wasGuideShownRecently = wasGuideShownRecently
    )
}
