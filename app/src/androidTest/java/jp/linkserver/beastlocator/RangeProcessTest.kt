package jp.linkserver.beastlocator

import android.app.Application
import android.os.Process

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import jp.linkserver.goldy.*

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Properties

/** Host server outlives the force-stopped app. Actual self APK, Gold inspector, OS jobs and leases. */
class RangeProcessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val ledger get() = app.getSharedPreferences("goldy_apk_transfer_state",0)
    private val journal get() = app.getSharedPreferences("goldy_range_process_test",0)
    private fun waitFor(condition: () -> Boolean) = runBlocking { withTimeout(120_000) { while(!condition()) delay(25) } }
    private fun runtime(launch: () -> Unit): UpdateTransferRuntime {
        lateinit var runtime: UpdateTransferRuntime
        instrumentation.runOnMainSync {
            runtime = UpdateTransferRuntime(app, rangeApkDownloader(app), ApkInstaller.create(app), AndroidUpdateTransferExecution(app)) { launch() }
            UpdateTransferRuntime::class.java.getDeclaredField("instance").apply { isAccessible=true }.set(null,runtime)
        }
        waitFor { !runtime.state.value.restoring }
        return runtime
    }
    @Test fun stage1DownloadAndKeepCheckpointForForceStop() = runBlocking<Unit> {
        assertTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("emulator",true) || android.os.Build.MODEL.contains("sdk",true))
        assertFalse("Finish cleanup of earlier staged test",journal.contains("original"))
        val original = JSONObject()
        ledger.all.forEach { (key,value) -> original.put(key,if(value is Set<*>) JSONArray(value.toList()) else value) }
        val ids = ApkInstaller.create(app).pendingRequests().map { it.id }
        check(journal.edit().putString("original",original.toString()).putString("ids",JSONArray(ids).toString()).putInt("pid",Process.myPid()).commit())
        AndroidUpdateTransferExecution(app).cancel()
        ledger.edit().clear().commit()
        val candidate = selfApkCandidate(app)
        // This dedicated test directory can contain a checkpoint from an earlier failed run.
        rangeApkDownloader(app).discardPartial(candidate.release)
        assertNull(rangeApkDownloader(app).savedProgress(candidate.release))
        val runtime = runtime { error("No automatic installer") }
        val scenario = ActivityScenario.launch(UpdateTransferTestActivity::class.java)
        val owner = Any()
        instrumentation.runOnMainSync { runtime.setForeground(owner,true) }
        delay(100)
        instrumentation.runOnMainSync { runtime.action(candidate,UpdateAction.Download) }
        waitFor { ((runtime.state.value.transfer as? UpdateTransferState.Downloading)?.progress?.bytes ?: 0) >= 3*1024*1024 }
        val record = File(app.filesDir,"goldy-range-test").listFiles()!!.single { it.extension == "meta" }
        val bytes = Properties().apply { record.inputStream().use(::load) }.getProperty("bytes").toLong()
        assertTrue(bytes > 0)
        check(journal.edit().putLong("saved",bytes).commit())
        instrumentation.runOnMainSync { runtime.setForeground(owner,false) }
        scenario.close()
        println("Range checkpoint persisted: $bytes; pid=${Process.myPid()}")
        // Runner force-stops the app next, leaving the independent HTTP server running.
    }
    @Test fun stage2ResumeInNewProcessAndPrepareVerifiedInstallRequest() = runBlocking<Unit> {
        assertNotEquals(journal.getInt("pid",0),Process.myPid())
        val mode=InstrumentationRegistry.getArguments().getString("resumeMode","range")
        if(mode=="tamper") {
            val part=File(app.filesDir,"goldy-range-test").listFiles()!!.single { it.extension=="part" }
            java.io.RandomAccessFile(part,"rw").use { it.seek(16); val previous=it.read(); it.seek(16); it.writeByte(previous xor 1) }
        }
        var launches = 0
        val runtime = runtime { launches++ }
        val restarted=java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher=launch(Dispatchers.Default) { runtime.state.collect { state ->
            if(state.message==app.getString(jp.linkserver.beastlocator.R.string.goldy_transfer_restarted)) restarted.set(true)
        } }
        val originalIds = JSONArray(journal.getString("ids","[]")!!).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        var scenario: ActivityScenario<UpdateTransferTestActivity>? = null
        try {
            assertTrue(runtime.state.value.waiting)
            val restored = runtime.state.value.retainedProgress
            if(mode=="tamper") assertNull(restored) else {
                assertNotNull(restored)
                assertTrue(restored!!.bytes >= journal.getLong("saved",0))
                assertTrue(restored.bytes < restored.total!!)
            }
            assertEquals(0,launches)
            if(restored!=null) assertEquals(DownloadProgressFormatter.format(restored,app.resources.configuration.locales[0]),
                UpdateTransferNotifications.create(app,"restored-test",runtime.state.value).extras.getString(android.app.Notification.EXTRA_TEXT))
            scenario = ActivityScenario.launch(UpdateTransferTestActivity::class.java)
            val owner = Any()
            instrumentation.runOnMainSync { runtime.setForeground(owner,true) }
            delay(100)
            instrumentation.runOnMainSync { runtime.action(runtime.selection.value,UpdateAction.Download); runtime.setForeground(owner,false) }
            waitFor { runtime.state.value.busy }
            waitFor {
                val state = runtime.state.value
                check(state.transfer !is UpdateTransferState.Failed) { "Transfer failed: $state" }
                state.transfer == UpdateTransferState.Ready
            }
            assertEquals(0,launches)
            if(mode=="ignore-range") assertTrue("Restart must be explained in the host state",restarted.get())
            val request = runtime.state.value.requests.single { it.id !in originalIds }
            assertEquals(app.packageName,request.metadata.packageName)
            assertEquals(app.packageManager.getPackageInfo(app.packageName,0).versionName,request.metadata.versionName)
            app.contentResolver.openInputStream(request.uri)!!.use { assertTrue(it.read() >= 0) }
            assertTrue(File(app.filesDir,"goldy-range-test").listFiles().orEmpty().none { it.extension == "part" })
            val automaticLaunches = launches
            instrumentation.runOnMainSync {
                runtime.setForeground(owner,true)
                runtime.action(runtime.selection.value,UpdateAction.Install)
                runtime.action(runtime.selection.value,UpdateAction.Install)
            }
            waitFor { runtime.state.value.transfer == UpdateTransferState.HandedOff }
            assertEquals(1,launches)
            File(app.filesDir, "range-process-evidence.json").writeText(JSONObject().apply {
                put("restartExplained",restarted.get()); put("restoredBytes", restored?.bytes ?: 0); put("total", restored?.total)
                put("pid", Process.myPid()); put("requestId", request.id)
                put("packageName", request.metadata.packageName); put("versionName", request.metadata.versionName)
                put("installerLaunches", automaticLaunches); put("explicitInstallerLaunches",launches)
            }.toString())
            println("Range restored=${restored?.bytes}/${restored?.total}; verified request=${request.id}")
        } finally {
            watcher.cancel()
            runtime.cancel()
            AndroidUpdateTransferExecution(app).cancel()
            scenario?.close()
            ApkInstaller.create(app).pendingRequests().filter { it.id !in originalIds }.forEach { ApkInstaller.create(app).release(it) }
            val original = JSONObject(journal.getString("original","{}")!!)
            restorePreferences(ledger,original.keys().asSequence().associateWith { key -> when(val value=original.get(key)) {
                is JSONArray -> (0 until value.length()).map { value.getString(it) }.toSet()
                else -> value
            } })
            journal.edit().clear().commit()
        }
    }
}
