package jp.linkserver.beastlocator

import android.app.Application
import android.content.Intent
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import jp.linkserver.goldy.*

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Runner changes the install AppOp and force-stops BETWEEN stages, never inside a test. */
class UpdatePermissionRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val ledger get() = app.getSharedPreferences("goldy_apk_transfer_state", 0)
    private val journal get() = app.getSharedPreferences("transfer_permission_test", 0)
    private val launches = mutableListOf<Intent>()
    private fun waitFor(condition: () -> Boolean) = runBlocking { withTimeout(90_000) { while (!condition()) delay(25) } }
    private fun model(): UpdateDownloadViewModel {
        lateinit var model: UpdateDownloadViewModel
        instrumentation.runOnMainSync { model = transferTestModel(app, selfApkDownloader(app), ApkInstaller.create(app)) { launches.add(Intent(it)) } }
        waitFor { !model.state.value.restoring }
        return model
    }
    private fun action(model: UpdateDownloadViewModel, action: UpdateAction) = instrumentation.runOnMainSync { model.action(selfApkCandidate(app), action) }
    private fun newProcess() { assertNotEquals(journal.getInt("pid", 0), Process.myPid()); journal.edit().putInt("pid", Process.myPid()).commit() }

    @Test fun stage1DeniedDoubleSettingsPersistsIntent() {
        assertTrue(android.os.Build.MODEL.contains("sdk", ignoreCase = true) || android.os.Build.FINGERPRINT.contains("generic"))
        assertFalse(journal.contains("original"))
        val backup = JSONObject()
        ledger.all.forEach { (key, value) -> backup.put(key, if (value is Set<*>) JSONArray(value.toList()) else value) }
        journal.edit().putString("original", backup.toString()).putInt("pid", Process.myPid()).commit()
        ledger.edit().clear().commit()
        assertFalse(app.packageManager.canRequestPackageInstalls())
        val model = model()
        action(model, UpdateAction.Download)
        waitFor { model.state.value.transfer == UpdateTransferState.PermissionRequired }
        assertTrue(launches.isEmpty())
        action(model, UpdateAction.OpenInstallPermissionSettings)
        action(model, UpdateAction.OpenInstallPermissionSettings)
        waitFor { launches.size == 1 }
        assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, launches.single().action)
        assertTrue(ledger.getBoolean("permission_waiting", false))
    }
    @Test fun stage2DeniedRestartAndCancelledSettingsRemainSingleAction() {
        newProcess()
        assertFalse(app.packageManager.canRequestPackageInstalls())
        val model = model()
        assertEquals(UpdateTransferState.PermissionRequired, model.state.value.transfer)
        assertTrue(launches.isEmpty())
        action(model, UpdateAction.OpenInstallPermissionSettings)
        waitFor { launches.size == 1 }
        instrumentation.runOnMainSync { model.onForegroundChanged(false); model.onForegroundChanged(true) }
        waitFor { !ledger.getBoolean("permission_settings_open", true) }
        assertEquals(UpdateTransferState.PermissionRequired, model.state.value.transfer)
        assertEquals(1, launches.size)
    }
    @Test fun stage3GrantedRestartOffersDownloadWithoutReopeningSettings() {
        newProcess()
        assertTrue(app.packageManager.canRequestPackageInstalls())
        val model = model()
        waitFor { model.state.value.transfer == UpdateTransferState.Idle }
        action(model, UpdateAction.OpenInstallPermissionSettings) // A stale UI click must not reopen Settings.
        Thread.sleep(100)
        assertTrue(launches.isEmpty())
        action(model, UpdateAction.Download)
        waitFor { model.state.value.transfer == UpdateTransferState.Ready }
        assertTrue(launches.isEmpty())
        val request = model.state.value.requests.single { it.id == ledger.getString("selected_request", null) }
        journal.edit().putString("request", request.id).commit()
    }
    @Test fun stage4PreparedLeaseSurvivesPermissionRevocation() {
        newProcess()
        assertFalse(app.packageManager.canRequestPackageInstalls())
        val model = model()
        assertEquals(UpdateTransferState.PermissionRequired, model.state.value.transfer)
        assertTrue(model.state.value.requests.any { it.id == journal.getString("request", null) })
        action(model, UpdateAction.OpenInstallPermissionSettings)
        waitFor { launches.size == 1 }
        assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, launches.single().action)
    }
    @Test fun stage5GrantedPreparedLeaseOffersOneExplicitInstall() = runBlocking<Unit> {
        newProcess()
        assertTrue(app.packageManager.canRequestPackageInstalls())
        val model = model()
        assertEquals(UpdateTransferState.Ready, model.state.value.transfer)
        assertTrue(launches.isEmpty())
        action(model, UpdateAction.OpenInstallPermissionSettings)
        Thread.sleep(100)
        assertTrue(launches.isEmpty())
        action(model, UpdateAction.Install)
        action(model, UpdateAction.Install)
        waitFor { model.state.value.transfer == UpdateTransferState.HandedOff }
        assertEquals(1, launches.size)
        val request = model.state.value.requests.single { it.id == journal.getString("request", null) }
        app.contentResolver.openInputStream(request.uri)!!.use { assertTrue(it.read() >= 0) }
        // Test-only cleanup: recorded callbacks opened no external installer.
        ApkInstaller.create(app).release(request)
        assertTrue(ApkInstaller.create(app).pendingRequests().none { it.id == request.id })
        val backup = JSONObject(journal.getString("original", "{}")!!)
        restorePreferences(ledger, backup.keys().asSequence().associateWith { key -> when (val value = backup.get(key)) {
            is JSONArray -> (0 until value.length()).map { value.getString(it) }.toSet()
            else -> value
        } })
        journal.edit().clear().commit()
    }
}
