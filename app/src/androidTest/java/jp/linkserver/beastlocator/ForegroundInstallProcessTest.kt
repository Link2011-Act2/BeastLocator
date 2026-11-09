package jp.linkserver.beastlocator

import android.app.Application
import android.content.Intent
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import jp.linkserver.goldy.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/** Run each stage in a separate instrumentation process; only the isolated APK is force-stopped. */
class ForegroundInstallProcessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val journal get() = app.getSharedPreferences("foreground_install_process_test", 0)
    private val ledger get() = app.getSharedPreferences("goldy_apk_transfer_state", 0)
    private fun isolated() = assertTrue(app.packageName.endsWith(".goldyverification"))
    private fun waitFor(condition: () -> Boolean) = runBlocking {
        withTimeout(90_000) { while (!condition()) delay(20) }
    }

    @Test fun stage1PrepareInBackground() {
        isolated()
        assertFalse(journal.contains("pid"))
        val backup = JSONObject()
        ledger.all.forEach { (key, value) -> backup.put(key, if (value is Set<*>) JSONArray(value.toList()) else value) }
        check(journal.edit().putString("original", backup.toString()).commit())
        check(ledger.edit().clear().commit())
        var launches = 0
        lateinit var model: UpdateDownloadViewModel
        instrumentation.runOnMainSync {
            model = transferTestModel(app, selfApkDownloader(app), ApkInstaller.create(app)) { launches++ }
        }
        waitFor { !model.state.value.restoring }
        val candidate = selfApkCandidate(app)
        instrumentation.runOnMainSync {
            model.action(candidate, UpdateAction.Download)
            model.onForegroundChanged(false)
        }
        waitFor { model.state.value.transfer == UpdateTransferState.Ready }
        assertEquals(0, launches)
        check(journal.edit().putInt("pid", Process.myPid())
            .putString("request", checkNotNull(model.state.value.preparedRequest).id).commit())
    }

    @Test fun stage2RestartAndReturnStillRequireExplicitInstall() = runBlocking<Unit> {
        isolated()
        assertNotEquals(journal.getInt("pid", 0), Process.myPid())
        val launches = java.util.concurrent.CopyOnWriteArrayList<Intent>()
        lateinit var model: UpdateDownloadViewModel
        instrumentation.runOnMainSync {
            model = transferTestModel(app, selfApkDownloader(app), ApkInstaller.create(app)) { launches.add(Intent(it)); Unit }
        }
        waitFor { !model.state.value.restoring }
        assertEquals(UpdateTransferState.Ready, model.state.value.transfer)
        val request = checkNotNull(model.state.value.preparedRequest)
        assertEquals(journal.getString("request", null), request.id)
        instrumentation.runOnMainSync { model.onForegroundChanged(false); model.onForegroundChanged(true) }
        instrumentation.waitForIdleSync()
        assertTrue(launches.isEmpty())
        app.contentResolver.openInputStream(request.uri)!!.use { assertTrue(it.read() >= 0) }
        instrumentation.runOnMainSync { model.action(null, UpdateAction.Install); model.action(null, UpdateAction.Install) }
        waitFor { model.state.value.transfer == UpdateTransferState.HandedOff }
        assertEquals(1, launches.size)
        // The recorded callback has no external URI reader.
        ApkInstaller.create(app).release(request)
        val backup = JSONObject(checkNotNull(journal.getString("original", null)))
        restorePreferences(ledger, backup.keys().asSequence().associateWith { key -> when (val value = backup.get(key)) {
            is JSONArray -> (0 until value.length()).map { value.getString(it) }.toSet()
            else -> value
        } })
        check(journal.edit().clear().commit())
    }
}
