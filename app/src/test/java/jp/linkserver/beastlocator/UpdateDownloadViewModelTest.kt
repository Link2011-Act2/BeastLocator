package jp.linkserver.beastlocator

import jp.linkserver.goldy.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class UpdateDownloadViewModelTest {
    @Test fun preparedAndHandedOffStatesDoNotRepresentInstalledSuccess() {
        assertFalse(UpdateDownloadState(transfer=UpdateTransferState.Ready).busy)
        assertFalse(UpdateDownloadState(transfer=UpdateTransferState.HandedOff).busy)
        assertTrue(UpdateDownloadState(transfer=UpdateTransferState.Verifying).busy)
    }
    @Test fun knownProgressUsesDecimalUnitsAndTwoDigitPercentage() {
        assertEquals("12.34 MB / 25.67 MB · 48.07%", DownloadProgressFormatter.format(ApkProgress(12340000,25670000,1),Locale.US))
        assertEquals("999 B",DownloadProgressFormatter.formatBytes(999,Locale.JAPAN))
        assertEquals("1.00 KB",DownloadProgressFormatter.formatBytes(1000,Locale.US))
    }
    @Test fun incompleteLastByteNeverLooksComplete() {
        assertTrue(DownloadProgressFormatter.format(ApkProgress(999999,1000000,1),Locale.JAPAN).endsWith("99.99%"))
        assertTrue(DownloadProgressFormatter.fraction(ApkProgress(Long.MAX_VALUE-1,Long.MAX_VALUE,1))!! < 1f)
    }
    @Test fun invalidTotalHasNoInventedPercentage() {
        assertEquals("1.00 KB",DownloadProgressFormatter.format(ApkProgress(1000,null,1),Locale.US))
        assertEquals("1.00 KB",DownloadProgressFormatter.format(ApkProgress(1000,0,1),Locale.US))
        assertNull(DownloadProgressFormatter.fraction(ApkProgress(10,-1,1)))
    }
    @Test fun percentAndDecimalSeparatorsFollowLocale() {
        assertEquals("12,34 MB / 25,67 MB · 48,07 %", DownloadProgressFormatter.format(ApkProgress(12340000,25670000,1),Locale.FRANCE))
    }
}
