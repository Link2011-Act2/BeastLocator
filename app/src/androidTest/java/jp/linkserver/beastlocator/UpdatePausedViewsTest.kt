package jp.linkserver.beastlocator

import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.color.MaterialColors
import jp.linkserver.goldy.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Views rendering; transfer execution is covered by the existing background tests. */
class UpdatePausedViewsTest {
    @Test fun pausedProgressUsesErrorThenRestoresNormalTintAndUnknownIsStatic() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val candidate=UpdateCandidate(GitHubRelease(702,"v999.0.0-Release",
            url="https://github.com/Link2011-Act2/BeastLocator/releases/tag/v999.0.0-Release",
            assets=listOf(GitHubAsset(1,"update.apk","https://github.com/Link2011-Act2/BeastLocator/releases/download/v999.0.0-Release/update.apk",30_000_000,null))))
        ActivityScenario.launch<UpdateActivity>(UpdateActivity.createIntent(context,candidate)).use { scenario ->
            scenario.onActivity { activity ->
                val progress=activity.findViewById<ProgressBar>(R.id.updateProgressBar)
                val normal=progress.progressTintList
                val state=UpdateDownloadState(tag=candidate.release.tag,restoring=false,
                    releaseIdentity=transferIdentity(candidate),waiting=true,retainedProgress=ApkProgress(15_000_000,30_000_000,1),
                    transfer=UpdateTransferState.Failed("network"))
                activity.render(state)
                assertFalse(progress.isIndeterminate)
                assertEquals(500,progress.progress)
                assertEquals(MaterialColors.getColor(progress,android.R.attr.colorError),progress.progressTintList!!.defaultColor)
                assertEquals(activity.getString(R.string.goldy_transfer_restart),activity.findViewById<Button>(R.id.updateDownloadButton).text)
                assertEquals(View.VISIBLE,activity.findViewById<Button>(R.id.updateCancelPausedButton).visibility)
                assertTrue(activity.findViewById<TextView>(R.id.updateStatusText).text.contains("50.00%"))
                activity.render(state.copy(retainedProgress=ApkProgress(15_000_000,null,1)))
                assertFalse(progress.isIndeterminate)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,progress.importantForAccessibility)
                assertFalse(activity.findViewById<TextView>(R.id.updateStatusText).text.contains("%"))
                activity.render(state.copy(waiting=false,transfer=UpdateTransferState.Downloading(ApkProgress(16_000_000,30_000_000,1))))
                assertEquals(normal,progress.progressTintList)
                assertEquals(View.GONE,activity.findViewById<Button>(R.id.updateCancelPausedButton).visibility)
            }
        }
    }
}
