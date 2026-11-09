package jp.linkserver.beastlocator

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import jp.linkserver.goldy.*

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import jp.linkserver.beastlocator.R

/** Host restoration/ledger/explicit-action tests; SDK crypto and its real-update tests are shared. */
class ManagedInstallLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val ledger get() = app.getSharedPreferences("goldy_apk_transfer_state", 0)
    private val installer get() = ApkInstaller.create(app)
    private lateinit var original: Map<String, *>
    private lateinit var originalIds: Set<String>
    private val models = mutableListOf<UpdateDownloadViewModel>()
    private val launches = AtomicInteger()

    @Before fun setup() = runBlocking {
        assertTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        original = ledger.all.toMap()
        originalIds = installer.pendingRequests().map { it.id }.toSet()
        check(ledger.edit().clear().commit())
    }
    @After fun cleanup() = runBlocking {
        models.forEach { model -> instrumentation.runOnMainSync { model.onForegroundChanged(false); model.cancel() } }
        // Callbacks were recorded; no external installer/reader remains in these host fixtures.
        installer.pendingRequests().filter { it.id !in originalIds }.forEach { installer.release(it) }
        restorePreferences(ledger, original)
    }
    private fun waitFor(condition: () -> Boolean) = runBlocking {
        withTimeout(60_000) { while (!condition()) delay(25) }
    }
    private fun model(): UpdateDownloadViewModel {
        lateinit var value: UpdateDownloadViewModel
        instrumentation.runOnMainSync {
            value = transferTestModel(app, selfApkDownloader(app), installer) { launches.incrementAndGet(); Unit }
        }
        models += value
        waitFor { !value.state.value.restoring }
        return value
    }
    private fun prepared(): Pair<UpdateDownloadViewModel, InstallRequest> {
        val value = model()
        val candidate = selfApkCandidate(app)
        instrumentation.runOnMainSync { value.action(candidate, UpdateAction.Download) }
        waitFor { value.state.value.transfer == UpdateTransferState.Ready }
        return value to checkNotNull(value.state.value.preparedRequest)
    }
    @Test fun legacyHandoffRestoresNormalInstallAndReturningDoesNotAutoLaunch() {
        val (_, request) = prepared()
        check(ledger.edit().putStringSet("claimed", setOf(request.id)).putStringSet("handed_off", setOf(request.id)).commit())
        val restored = model()
        assertEquals(UpdateTransferState.Ready, restored.state.value.transfer)
        assertEquals(request.id, restored.state.value.preparedRequest?.id)
        assertFalse(ledger.contains("claimed")); assertFalse(ledger.contains("handed_off"))
        assertEquals(0, launches.get())
        instrumentation.runOnMainSync { restored.action(null, UpdateAction.Install); restored.action(null, UpdateAction.Install) }
        waitFor { restored.state.value.transfer == UpdateTransferState.HandedOff }
        assertEquals(1, launches.get())
        instrumentation.runOnMainSync { restored.onForegroundChanged(false); restored.onForegroundChanged(true) }
        waitFor { !restored.state.value.restoring && restored.state.value.transfer == UpdateTransferState.Ready }
        assertEquals(1, launches.get())
        instrumentation.runOnMainSync { restored.action(null, UpdateAction.Install); restored.action(null, UpdateAction.Install) }
        waitFor { restored.state.value.transfer == UpdateTransferState.HandedOff }
        assertEquals(2, launches.get())
    }
    @Test fun sdkConfirmedInstalledArtifactClearsOnlyItsHostTransferAndKeepsReadableUri() {
        val (_, request) = prepared()
        ledger.edit().putString("unrelated_setting", "preserved").commit()
        // Only seed the SDK outcome fixture; the actual installed APK is still fully verified.
        // Real version1→2 replacement, signature and held-FD checks run once in Goldy.
        val record = File(app.filesDir, "goldy-apks/records/" + request.id + ".json")
        val json = JSONObject(record.readText())
        json.getJSONObject("lifecycle").put("confirmed", true)
        record.writeText(json.toString())
        val restored = model()
        assertTrue(restored.state.value.requests.none { it.id == request.id })
        assertNull(restored.state.value.preparedRequest)
        assertNull(ledger.getString("selected_request", null))
        assertNull(ledger.getString("transfer_snapshot", null))
        assertEquals("preserved", ledger.getString("unrelated_setting", null))
        assertEquals(0, launches.get())
        assertTrue(installerIds().contains(request.id))
        app.contentResolver.openInputStream(request.uri)!!.use { assertTrue(it.read() >= 0) }
        assertTrue(File(app.applicationInfo.sourceDir).isFile)
    }
    @Test fun completedOldRequestDoesNotEraseAnotherAssetsPausedTransfer() {
        val (_, request) = prepared()
        val previous = selfApkCandidate(app)
        val another = previous.copy(release = previous.release.copy(id = 999,
            assets = previous.release.assets.map { it.copy(id = 999) }))
        val snapshot = UpdateTransferSnapshot.encode(another)
        check(ledger.edit().putString("transfer_snapshot", snapshot).putString("transfer_phase", "paused").commit())
        val record = File(app.filesDir, "goldy-apks/records/" + request.id + ".json")
        val json = JSONObject(record.readText())
        json.getJSONObject("lifecycle").put("confirmed", true)
        record.writeText(json.toString())
        val restored = model()
        assertNull(ledger.getString("selected_request", null))
        assertEquals(snapshot, ledger.getString("transfer_snapshot", null))
        assertEquals(transferIdentity(another), transferIdentity(restored.selection.value))
        assertTrue(restored.state.value.waiting)
        assertEquals(0, launches.get())
    }
    private fun installerIds() = runBlocking { installer.pendingRequests().map { it.id }.toSet() }
}
