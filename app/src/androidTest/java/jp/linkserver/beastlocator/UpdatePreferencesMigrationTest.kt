package jp.linkserver.beastlocator

import androidx.test.platform.app.InstrumentationRegistry
import jp.linkserver.goldy.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdatePreferencesMigrationTest {
    @Test fun failedStateWriteKeepsLegacySettingsAndDoesNotMarkMigrationComplete() = runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val legacy=app.getSharedPreferences("github_release_updates",0)
        val host=app.getSharedPreferences("goldy_host_updates",0)
        val before=legacy.all.toMap(); val oldHost=host.all.toMap()
        try {
            legacy.edit().putString("dismissed_update_tag","v2.0.0-RC1").commit()
            val saved=legacy.all.toMap()
            host.edit().clear().commit()
            val broken=object: UpdateStateStore {
                override suspend fun read()=StoredUpdateState()
                override suspend fun write(state: StoredUpdateState) { throw java.io.IOException("Fixture storage failure") }
            }
            try { AppUpdateManager.HostUpdates(app,broken).initialize(); fail("Write failure must propagate") }
            catch (_: java.io.IOException) { }
            assertFalse(host.getBoolean("migration_v1",false))
            assertEquals(saved,legacy.all)
        } finally { restorePreferences(legacy,before); restorePreferences(host,oldHost) }
    }
    @Test fun legacySettingsAndMultipleIgnoredTagsSurviveRepeatedInitialization() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val legacy = app.getSharedPreferences("github_release_updates",0)
        val host = app.getSharedPreferences("goldy_host_updates",0)
        val oldLegacy=legacy.all.toMap(); val oldHost=host.all.toMap()
        val store=UpdateStateStores.preferences(app,ApkUpdateProfile.repository)
        val original=store.read()
        try {
            legacy.edit().clear().putString("dismissed_update_tag","v2.0.0-Beta2")
                .putBoolean("show_latest_release_for_testing",true)
                .putString("current_version_override_for_testing","0.1.0-IntDev")
                .putLong("last_check_ms",1234567L).putString("unrelated","preserved").commit()
            val saved=legacy.all.toMap()
            host.edit().clear().commit()
            store.write(StoredUpdateState(ignoredTags=setOf("v3.0.0-release")))
            AppUpdateManager.HostUpdates(app).initialize()
            assertEquals(setOf("v2.0.0-beta2","v3.0.0-release"),store.read().ignoredTags)
            assertNull(store.read().cache)
            assertEquals(saved,legacy.all)
            assertEquals(1234567L,host.getLong("legacy_last_check_ms",0))
            store.write(store.read().copy(ignoredTags=store.read().ignoredTags+"v4.0.0-rc6"))
            AppUpdateManager.HostUpdates(app).initialize()
            assertEquals(3,store.read().ignoredTags.size)
            assertEquals(saved,legacy.all)
            val dev=VersionPolicies.Common.channel(installedVersionName(app))==jp.linkserver.goldy.ReleaseChannel.IntDev
            assertEquals(dev,AppUpdateManager.isShowLatestReleaseForTestingEnabled(app))
            assertEquals(if(dev) "0.1.0-IntDev" else "",AppUpdateManager.getUpdateCurrentVersionOverrideForTesting(app))
        } finally {
            store.write(original); restorePreferences(legacy,oldLegacy); restorePreferences(host,oldHost)
        }
    }
}
