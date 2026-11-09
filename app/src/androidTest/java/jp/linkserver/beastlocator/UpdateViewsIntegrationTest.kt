package jp.linkserver.beastlocator

import android.app.Application
import android.content.Intent
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import jp.linkserver.goldy.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real XML UI and Gold verification. Only HTTP bytes and Activity handoff are fixture boundaries. */
class UpdateViewsIntegrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as Application
    private val ledger get()=app.getSharedPreferences("goldy_apk_transfer_state",0)
    private val hostPrefs get()=app.getSharedPreferences("goldy_host_updates",0)
    private val legacy get()=app.getSharedPreferences("github_release_updates",0)
    private val store get()=UpdateStateStores.preferences(app,ApkUpdateProfile.repository)
    private val runtimeField=UpdateTransferRuntime::class.java.getDeclaredField("instance").apply { isAccessible=true }
    private val hostField=AppUpdateManager::class.java.getDeclaredField("instance").apply { isAccessible=true }
    private var previousRuntime: Any?=null
    private var previousHost: Any?=null
    private lateinit var oldLedger: Map<String,*>
    private lateinit var oldHost: Map<String,*>
    private lateinit var oldLegacy: Map<String,*>
    private lateinit var oldState: StoredUpdateState
    private lateinit var originalIds: Set<String>
    private var scenario: ActivityScenario<*>?=null
    private var model: UpdateDownloadViewModel?=null
    private val launches=AtomicInteger()
    private fun waitFor(block:()->Boolean)=runBlocking { withTimeout(90000) { while(!block()) delay(25) } }
    @Before fun setup()=runBlocking {
        assertTrue(android.os.Build.MODEL.contains("sdk",true) || android.os.Build.FINGERPRINT.contains("generic"))
        oldLedger=ledger.all.toMap(); oldHost=hostPrefs.all.toMap(); oldLegacy=legacy.all.toMap()
        oldState=store.read(); originalIds=ApkInstaller.create(app).pendingRequests().map { it.id }.toSet()
        previousRuntime=runtimeField.get(null); previousHost=hostField.get(null)
        ledger.edit().clear().commit(); hostPrefs.edit().clear().commit(); legacy.edit().clear().commit()
        store.write(StoredUpdateState()); hostField.set(null,null)
    }
    @After fun cleanup()=runBlocking {
        scenario?.close()
        model?.let { value -> instrumentation.runOnMainSync { value.onForegroundChanged(false); value.cancel() } }
        // No external Activity was launched: all callbacks above are recorded only.
        ApkInstaller.create(app).pendingRequests().filter { it.id !in originalIds }.forEach { ApkInstaller.create(app).release(it) }
        store.write(oldState)
        restorePreferences(ledger,oldLedger); restorePreferences(hostPrefs,oldHost); restorePreferences(legacy,oldLegacy)
        runtimeField.set(null,previousRuntime); hostField.set(null,previousHost)
    }
    private fun prepare(): Pair<UpdateDownloadViewModel,InstallRequest> {
        lateinit var value: UpdateDownloadViewModel
        instrumentation.runOnMainSync {
            value=transferTestModel(app,selfApkDownloader(app),ApkInstaller.create(app)) { launches.incrementAndGet(); Unit }
        }
        model=value
        waitFor { !value.state.value.restoring }
        instrumentation.runOnMainSync { value.action(selfApkCandidate(app),UpdateAction.Download) }
        waitFor { value.state.value.transfer==UpdateTransferState.Ready }
        runtimeField.set(null,value.runtime)
        return value to checkNotNull(value.state.value.preparedRequest)
    }
    @Test fun preparedApkWithoutReleaseRestoresOneExplicitInstallAcrossRecreation() {
        val (value,request)=prepare()
        ledger.edit().remove("transfer_snapshot").commit()
        lateinit var restored: UpdateDownloadViewModel
        instrumentation.runOnMainSync {
            restored=transferTestModel(app,selfApkDownloader(app),ApkInstaller.create(app)) { launches.incrementAndGet(); Unit }
        }
        model=restored; waitFor { !restored.state.value.restoring }
        runtimeField.set(null,restored.runtime)
        instrumentation.runOnMainSync { value.onForegroundChanged(false) }
        val activity=ActivityScenario.launch(UpdateActivity::class.java); scenario=activity
        onView(withId(R.id.updateDownloadButton)).check(matches(withText(R.string.goldy_transfer_install)))
        activity.recreate()
        onView(withId(R.id.updateDownloadButton)).perform(scrollTo(),click())
        waitFor { restored.state.value.transfer==UpdateTransferState.HandedOff }
        assertEquals(1,launches.get())
        onView(withId(R.id.updateDownloadButton)).check(matches(isNotEnabled()))
        assertEquals(request.id,restored.state.value.preparedRequest?.id)
        app.contentResolver.openInputStream(request.uri)!!.use { assertTrue(it.read()>=0) }
    }
    @Test fun tamperedPreparedRecordOffersDownloadWithoutLaunchingInstaller() {
        val (value,request)=prepare()
        scenario=ActivityScenario.launch(UpdateActivity::class.java)
        onView(withId(R.id.updateDownloadButton)).check(matches(withText(R.string.goldy_transfer_install)))
        val record=File(app.filesDir,"goldy-apks/records/${request.id}.json")
        val json=JSONObject(record.readText()).put("sha256","0".repeat(64))
        record.writeText(json.toString())
        onView(withId(R.id.updateDownloadButton)).perform(scrollTo(),click())
        waitFor { value.state.value.transfer is UpdateTransferState.Failed }
        onView(withId(R.id.updateDownloadButton)).check(matches(withText(R.string.goldy_transfer_download)))
        assertEquals(0,launches.get())
    }
    @Test fun normalAppEntryRetriesConfirmedUpdateCleanupWithoutStartingInstaller() {
        val (old,request)=prepare()
        val record=File(app.filesDir,"goldy-apks/records/${request.id}.json")
        val json=JSONObject(record.readText())
        json.getJSONObject("lifecycle").put("confirmed",true)
        record.writeText(json.toString())
        instrumentation.runOnMainSync { old.onForegroundChanged(false) }
        runtimeField.set(null,null)
        scenario=ActivityScenario.launch(MainActivity::class.java)
        waitFor { (runtimeField.get(null) as? UpdateTransferRuntime)?.state?.value?.restoring == false }
        val restored=runtimeField.get(null) as UpdateTransferRuntime
        assertTrue(restored.state.value.requests.none { it.id==request.id })
        app.contentResolver.openInputStream(request.uri)!!.use { assertTrue(it.read()>=0) }
        assertEquals(0,launches.get())
    }
    @Test fun detachedCheckCallbackCannotCallDestroyedScreenAndCachedOutcomeStillPublishes()=runBlocking<Unit> {
        val info=app.packageManager.getPackageInfo(app.packageName,0)
        @Suppress("DEPRECATION")
        val code=if(android.os.Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong()
        val installed=InstalledVersion(checkNotNull(info.versionName),code)
        val release=GitHubRelease(902,"v999.0.0-Release",url="https://github.com/Link2011-Act2/BeastLocator/releases/tag/v999.0.0-Release")
        store.write(StoredUpdateState(cache=UpdateCache(ApkUpdateProfile.repository,ApkUpdateProfile.policy.id,installed,
            installed.name,false,ReleaseCatalog(listOf(release)),System.currentTimeMillis())))
        val callback=AtomicInteger()
        instrumentation.runOnMainSync {
            val operation=AppUpdateManager.checkForUpdate(app,false) { callback.incrementAndGet() }
            assertEquals(0,callback.get()) // An immediate cached result cannot race handle assignment.
            operation.cancel()
        }
        waitFor { AppUpdateManager.host(app).engine.state.value.lastOutcome is CheckOutcome.Available }
        assertEquals(0,callback.get())
        assertNotNull(AppUpdateManager.host(app).engine.state.value.availableUpdate)
    }
    @Test fun releaseNotesAndExpandableHistoryRemainViewsAfterRecreation() {
        val release=GitHubRelease(900,"v999.0.0-Release",notes="Latest release notes",
            url="https://github.com/Link2011-Act2/BeastLocator/releases/tag/v999.0.0-Release")
        val older=release.copy(id=899,tag="v998.0.0-RC1",notes="Earlier release notes",
            url="https://github.com/Link2011-Act2/BeastLocator/releases/tag/v998.0.0-RC1")
        val activity=ActivityScenario.launch<UpdateActivity>(UpdateActivity.createIntent(app,UpdateCandidate(release,listOf(older))))
        scenario=activity
        onView(withId(R.id.updateTagText)).check(matches(withText(release.tag)))
        onView(withId(R.id.updateReleaseNotesText)).check(matches(withText("Latest release notes")))
        onView(withId(R.id.updateIntermediateNotesToggle)).perform(scrollTo(),click())
        onView(withId(R.id.updateIntermediateNotesText)).check(matches(isDisplayed()))
        activity.recreate()
        onView(withId(R.id.updateIntermediateNotesText)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.updateDownloadButton)).check(matches(withText(R.string.goldy_update_retry)))
    }
    @Test fun ignoredCandidateStillHasViewUpdateAsAboutPrimaryAction()=runBlocking<Unit> {
        val info=app.packageManager.getPackageInfo(app.packageName,0)
        val installed=InstalledVersion(checkNotNull(info.versionName),if(android.os.Build.VERSION.SDK_INT>=28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong())
        val release=GitHubRelease(901,"v999.0.0-Release",notes="Manual access",
            url="https://github.com/Link2011-Act2/BeastLocator/releases/tag/v999.0.0-Release")
        legacy.edit().putString("dismissed_update_tag",release.tag).commit()
        store.write(StoredUpdateState(cache=UpdateCache(ApkUpdateProfile.repository,ApkUpdateProfile.policy.id,installed,
            installed.name,false,ReleaseCatalog(listOf(release)),System.currentTimeMillis())))
        val host=AppUpdateManager.host(app)
        host.initialize()
        assertNotNull(host.engine.state.value.availableUpdate)
        assertNull(host.engine.state.value.notificationUpdate)
        scenario=ActivityScenario.launch(AboutActivity::class.java)
        onView(withId(R.id.aboutCheckUpdateButton)).check(matches(withText(R.string.goldy_update_view))).perform(scrollTo(),click())
        onView(withId(R.id.updateTagText)).check(matches(withText(release.tag)))
        assertEquals(0,launches.get())
        androidx.test.espresso.Espresso.pressBack()
    }
}
