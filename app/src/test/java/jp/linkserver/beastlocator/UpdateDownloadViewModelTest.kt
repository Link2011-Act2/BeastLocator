package jp.linkserver.beastlocator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateDownloadViewModelTest {
    @Test
    fun downloadedResultCanOnlyBeClaimedOnce() {
        val result = SingleUseValue("apk")

        assertEquals("apk", result.claim())
        assertNull(result.claim())
    }
}
