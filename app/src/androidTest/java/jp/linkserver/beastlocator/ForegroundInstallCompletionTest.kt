package jp.linkserver.beastlocator

import android.app.Application
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import jp.linkserver.goldy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Real APK verification and leases, with controlled completion and recorded Activity handoffs. */
class ForegroundInstallCompletionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val ledger get() = app.getSharedPreferences("goldy_apk_transfer_state", 0)
    private val installer get() = ApkInstaller.create(app)
    private lateinit var original: Map<String, *>
    private lateinit var originalIds: Set<String>
    private val launches = CopyOnWriteArrayList<Intent>()
    private val runtimes = mutableListOf<UpdateTransferRuntime>()
    private val owner = Any()

    private class Execution : UpdateTransferExecution {
        lateinit var ticket: String
        fun enqueued() = ::ticket.isInitialized
        override suspend fun enqueue(ticket: String, size: Long?): Boolean { this.ticket = ticket; return true }
        override suspend fun cancel() = Unit
    }

    @Before fun setup() = runBlocking {
        // This suite may run on a real phone only under a separately installed test application ID.
        assertTrue("Use the isolated verification APK", app.packageName.endsWith(".goldyverification"))
        original = ledger.all.toMap()
        originalIds = installer.pendingRequests().map { it.id }.toSet()
        check(ledger.edit().clear().commit())
        assertEquals(InstallPermission.Allowed, installer.permissionStatus())
    }

    @After fun cleanup() = runBlocking {
        if (!::original.isInitialized) return@runBlocking
        runtimes.forEach { runtime ->
            instrumentation.runOnMainSync { runtime.setForeground(owner, false) }
            runtime.cancel()
        }
        installer.pendingRequests().filter { it.id !in originalIds }.forEach { installer.release(it) }
        restorePreferences(ledger, original)
    }

    private fun createRuntime(execution: Execution = Execution(), onActivity: (Intent) -> Unit = { launches.add(Intent(it)); Unit }): UpdateTransferRuntime {
        lateinit var result: UpdateTransferRuntime
        instrumentation.runOnMainSync { result = UpdateTransferRuntime(app, selfApkDownloader(app), installer, execution, onActivity) }
        runtimes += result
        runBlocking { result.awaitInitialization() }
        instrumentation.runOnMainSync { result.setForeground(owner, true) }
        idle()
        return result
    }

    private fun idle() = instrumentation.waitForIdleSync()
    private fun waitFor(condition: () -> Boolean) = runBlocking {
        withTimeout(90_000) { while (!condition()) delay(20) }
    }
    private fun schedule(runtime: UpdateTransferRuntime, execution: Execution) {
        val candidate = selfApkCandidate(app)
        instrumentation.runOnMainSync {
            runtime.action(candidate, UpdateAction.Download)
            runtime.action(candidate, UpdateAction.Download)
        }
        waitFor { execution.enqueued() }
        // Scheduling commits asynchronously; drain that command before running the Android entry point.
        runBlocking { withContext(Dispatchers.Main) { Unit } }
    }
    private fun complete(runtime: UpdateTransferRuntime, execution: Execution) = runBlocking {
        runtime.runScheduledTransfer(execution.ticket)
    }

    @Test fun foregroundCompletionLaunchesOnceAndRejectsReplayAndDuplicateInstall() {
        val execution = Execution()
        lateinit var runtime: UpdateTransferRuntime
        runtime = createRuntime(execution) { intent ->
            launches.add(Intent(intent))
            runtime.action(null, UpdateAction.Install) // Reentrant tap during handoff must remain serialized.
        }
        schedule(runtime, execution)
        complete(runtime, execution)
        assertEquals(UpdateTransferState.HandedOff, runtime.state.value.transfer)
        assertEquals(1, launches.size)
        complete(runtime, execution) // Job/Worker redelivery of the finished ticket.
        instrumentation.runOnMainSync { repeat(2) { runtime.action(null, UpdateAction.Install) } }
        runBlocking { repeat(3) { runtime.state.first() } } // Recollecting state is inert.
        assertEquals(1, launches.size)
        val request = checkNotNull(runtime.state.value.preparedRequest)
        assertEquals(request.uri, launches.single().data)
        assertTrue(launches.single().flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        app.contentResolver.openInputStream(request.uri)!!.use { assertTrue(it.read() >= 0) }
    }

    @Test fun backgroundCompletionAndRestoredReadyNeverLaunchOnReturn() {
        val execution = Execution()
        val runtime = createRuntime(execution)
        schedule(runtime, execution)
        instrumentation.runOnMainSync { runtime.setForeground(owner, false) }
        complete(runtime, execution)
        assertEquals(UpdateTransferState.Ready, runtime.state.value.transfer)
        instrumentation.runOnMainSync { runtime.setForeground(owner, true) }
        idle()
        assertTrue(launches.isEmpty())
        val restored = createRuntime()
        assertEquals(runtime.state.value.preparedRequest?.id, restored.state.value.preparedRequest?.id)
        assertEquals(UpdateTransferState.Ready, restored.state.value.transfer)
        assertTrue(launches.isEmpty())
        instrumentation.runOnMainSync { restored.action(null, UpdateAction.Install) }
        waitFor { restored.state.value.transfer == UpdateTransferState.HandedOff }
        assertEquals(1, launches.size)
    }

    @Test fun installerCancellationAndOwnerRecreationNeverAutoLaunchAgain() {
        val execution = Execution()
        val runtime = createRuntime(execution)
        schedule(runtime, execution)
        complete(runtime, execution)
        assertEquals(1, launches.size)
        // The recorded installer makes no changes, just as when the user presses Cancel.
        instrumentation.runOnMainSync { runtime.setForeground(owner, false); runtime.setForeground(owner, true) }
        waitFor { runtime.state.value.transfer == UpdateTransferState.Ready }
        idle()
        complete(runtime, execution)
        val recreated = UpdateDownloadViewModel(app, runtime)
        instrumentation.runOnMainSync { recreated.onForegroundChanged(true); recreated.onForegroundChanged(false) }
        idle()
        assertEquals(1, launches.size)
        instrumentation.runOnMainSync { runtime.action(null, UpdateAction.Install) }
        waitFor { runtime.state.value.transfer == UpdateTransferState.HandedOff }
        assertEquals(2, launches.size)
    }

    @Test fun failedAutomaticLaunchKeepsReadableApkForManualRetry() {
        val execution = Execution()
        var fail = true
        val runtime = createRuntime(execution) { intent ->
            if (fail) throw SecurityException("Test Activity launch denied")
            launches.add(Intent(intent))
        }
        schedule(runtime, execution)
        complete(runtime, execution)
        assertEquals(UpdateAction.Install, (runtime.state.value.transfer as UpdateTransferState.Failed).retry)
        val request = checkNotNull(runtime.state.value.preparedRequest)
        app.contentResolver.openInputStream(request.uri)!!.use { assertTrue(it.read() >= 0) }
        instrumentation.runOnMainSync { runtime.setForeground(owner, false); runtime.setForeground(owner, true) }
        waitFor { runtime.state.value.transfer == UpdateTransferState.Ready }
        assertTrue(launches.isEmpty())
        fail = false
        instrumentation.runOnMainSync { runtime.action(null, UpdateAction.Install) }
        waitFor { runtime.state.value.transfer == UpdateTransferState.HandedOff }
        assertEquals(1, launches.size)
    }

    @Test fun interruptedAutomaticLaunchLeavesReadyForManualRetry() {
        val execution = Execution()
        var interrupted = true
        val runtime = createRuntime(execution) { intent ->
            if (interrupted) throw CancellationException("Test execution stopped during handoff")
            launches.add(Intent(intent))
        }
        schedule(runtime, execution)
        try {
            complete(runtime, execution)
            fail("Expected execution cancellation")
        } catch (_: CancellationException) { }
        assertEquals(UpdateTransferState.Ready, runtime.state.value.transfer)
        val request = checkNotNull(runtime.state.value.preparedRequest)
        app.contentResolver.openInputStream(request.uri)!!.use { assertTrue(it.read() >= 0) }
        assertTrue(launches.isEmpty())
        interrupted = false
        instrumentation.runOnMainSync { runtime.action(null, UpdateAction.Install) }
        waitFor { runtime.state.value.transfer == UpdateTransferState.HandedOff }
        assertEquals(1, launches.size)
    }

    @Test fun replacingForegroundOwnerDuringDownloadDoesNotDuplicateHandoff() {
        val execution = Execution()
        val runtime = createRuntime(execution)
        schedule(runtime, execution)
        val recreatedOwner = Any()
        instrumentation.runOnMainSync {
            runtime.setForeground(owner, false)
            runtime.setForeground(recreatedOwner, true)
            runtime.setForeground(recreatedOwner, true)
        }
        complete(runtime, execution)
        assertEquals(UpdateTransferState.HandedOff, runtime.state.value.transfer)
        assertEquals(1, launches.size)
        instrumentation.runOnMainSync { runtime.setForeground(recreatedOwner, false) }
        complete(runtime, execution)
        assertEquals(1, launches.size)
    }
}
