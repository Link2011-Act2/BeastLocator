package jp.linkserver.beastlocator

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Build

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import jp.linkserver.goldy.*

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real Android scheduler/notification; only HTTP bytes and installer Activity launch are test fixtures. */
class UpdateBackgroundExecutionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8)
    }
    private fun waitFor(condition: () -> Boolean) = runBlocking { withTimeout(120_000) { while (!condition()) delay(25) } }
    private fun bytes(runtime: UpdateTransferRuntime) = (runtime.state.value.transfer as? UpdateTransferState.Downloading)?.progress?.bytes ?: 0

    @Test fun androidExecutionSurvivesHomeScreenOffAndRecreationThenRequiresExplicitInstall() = runBlocking<Unit> {
        assertTrue("Disposable emulator only", Build.MODEL.contains("sdk", ignoreCase = true) || Build.FINGERPRINT.contains("generic"))
        assertTrue(app.packageManager.canRequestPackageInstalls())
        val ledger = app.getSharedPreferences("goldy_apk_transfer_state", 0)
        val original = ledger.all.toMap()
        val installer = ApkInstaller.create(app)
        val existing = installer.pendingRequests().map { it.id }.toSet()
        val singleton = UpdateTransferRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val oldRuntime = singleton.get(null)
        var scenario: ActivityScenario<UpdateTransferTestActivity>? = null
        lateinit var runtime: UpdateTransferRuntime
        var runtimeReady = false
        var launches = 0
        var bodyFailure: Throwable? = null
        try {
            ledger.edit().clear().commit()
            instrumentation.runOnMainSync {
                runtime = UpdateTransferRuntime(app, selfApkDownloader(app, (40_000L / (File(app.applicationInfo.sourceDir).length() / 32768 + 1)).coerceAtLeast(1)), installer, AndroidUpdateTransferExecution(app)) { launches++ }
                runtimeReady = true
                singleton.set(null, runtime) // Test-only injection for the real JobService/Worker; no production bypass.
            }
            scenario = ActivityScenario.launch(UpdateTransferTestActivity::class.java)
            runtime.awaitInitialization()
            val owner = Any()
            val candidate = selfApkCandidate(app)
            instrumentation.runOnMainSync { runtime.setForeground(owner, true) }
            Thread.sleep(100)
            instrumentation.runOnMainSync { runtime.action(candidate, UpdateAction.Download); runtime.action(candidate, UpdateAction.Download) }
            waitFor { bytes(runtime) > 0 }
            val first = bytes(runtime)
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == UpdateTransferNotifications.ID })
            instrumentation.runOnMainSync { runtime.setForeground(owner, false) }
            shell("input keyevent KEYCODE_HOME")
            waitFor { bytes(runtime) > first + 65536 }
            val home = bytes(runtime)
            shell("am start -W -a android.settings.SETTINGS")
            waitFor { bytes(runtime) > home + 65536 }
            val otherApp = bytes(runtime)
            shell("input keyevent KEYCODE_SLEEP")
            waitFor { bytes(runtime) > otherApp + 65536 }
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            scenario.onActivity { activity ->
                activity.startActivity(Intent(activity, UpdateTransferTestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            scenario.recreate()
            val restored = UpdateDownloadViewModel(app, runtime)
            assertSame(runtime.state, restored.state)
            // An explicit notification cancellation reaches the non-exported receiver.
            val notification = app.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == UpdateTransferNotifications.ID }.notification
            notification.actions.single().actionIntent.send()
            waitFor { runtime.state.value.transfer == UpdateTransferState.Cancelled }
            waitFor { File(app.cacheDir, "goldy-apks").listFiles().orEmpty().none { it.extension == "part" } }
            assertEquals(0, launches)
            instrumentation.runOnMainSync { runtime.setForeground(owner, true); runtime.action(candidate, UpdateAction.Download) }
            waitFor { runtime.state.value.transfer == UpdateTransferState.Ready }
            assertEquals(0, launches) // Background completion can never launch an installer.
            val request = runtime.state.value.requests.single { it.id !in existing }
            app.contentResolver.openInputStream(request.uri)!!.use { assertTrue(it.read() >= 0) }
            instrumentation.runOnMainSync { runtime.action(candidate, UpdateAction.Install); runtime.action(candidate, UpdateAction.Install) }
            waitFor { runtime.state.value.transfer == UpdateTransferState.HandedOff }
            assertEquals(1, launches)
            // Test-only cleanup after the recorded callback; no external reader exists.
            installer.release(request)
            assertTrue(installer.pendingRequests().none { it.id == request.id })
        } catch (error: Throwable) {
            bodyFailure = error
            throw error
        } finally {
            if (runtimeReady) runtime.cancel()
            AndroidUpdateTransferExecution(app).cancel()
            val closeFailure = runCatching { scenario?.close() }.exceptionOrNull()
            singleton.set(null, oldRuntime)
            installer.pendingRequests().filter { it.id !in existing }.forEach { installer.release(it) }
            restorePreferences(ledger, original)
            app.getSystemService(NotificationManager::class.java).cancel(UpdateTransferNotifications.ID)
            shell("input keyevent KEYCODE_WAKEUP")
            if (closeFailure != null) {
                if (bodyFailure != null) bodyFailure.addSuppressed(closeFailure) else throw closeFailure
            }
        }
    }
}
