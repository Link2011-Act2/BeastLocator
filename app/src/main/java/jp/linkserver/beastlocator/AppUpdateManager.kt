package jp.linkserver.beastlocator

import android.content.Context
import android.widget.Toast
import jp.linkserver.goldy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Host preferences and callback bridge only; Gold owns the catalog, selection and persisted update state. */
object AppUpdateManager {
    private const val PREFS = "github_release_updates"
    private const val PREVIEW = "show_latest_release_for_testing"
    private const val OVERRIDE = "current_version_override_for_testing"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var instance: HostUpdates? = null
    internal fun host(context: Context): HostUpdates = instance ?: synchronized(this) {
        instance ?: HostUpdates(context.applicationContext).also { instance = it }
    }
    /** Detaches a screen's callback; the application-owned check still updates Gold's state. */
    class Operation internal constructor(private val job: Job, private val observing: AtomicBoolean) {
        val isActive: Boolean get() = observing.get() && job.isActive
        fun cancel() { observing.set(false) }
    }
    fun checkForUpdate(context: Context, force: Boolean = false, onResult: (Result<UpdateCandidate?>) -> Unit): Operation {
        val app = context.applicationContext
        val observing = AtomicBoolean(true)
        val job = scope.launch {
            // Deliver cached outcomes after the caller has stored its operation handle.
            yield()
            val result = try {
                val host = host(app)
                when (val outcome = host.check(force)) {
                    is CheckOutcome.Failure -> Result.failure(IllegalStateException(outcome.error.toString()))
                    else -> Result.success(if (force) host.engine.state.value.availableUpdate else
                        host.engine.state.value.notificationUpdate.takeIf { host.engine.state.value.showBanner })
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { Result.failure(error) }
            try { if (observing.get()) onResult(result) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { AppDiagnostics.warn("updater_callback_failed", error = error) }
        }
        return Operation(job, observing)
    }
    fun dismissUpdateNotificationUntilNextVersion(context: Context, tagName: String) {
        command(context) { initialize(); engine.ignoreVersion(tagName) }
    }
    fun hideBanner(context: Context) = command(context) {
        initialize()
        val tag = engine.state.value.notificationUpdate?.release?.tag
        engine.hideBanner()
        withContext(Dispatchers.IO) { kotlin.check(hostPrefs.edit().putString("hidden_banner_tag", tag).commit()) }
    }
    private fun command(context: Context, action: suspend HostUpdates.() -> Unit) {
        val app = context.applicationContext
        scope.launch {
            yield()
            try { host(app).action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                AppDiagnostics.warn("updater_state_save_failed", error = error)
                Toast.makeText(app, R.string.goldy_update_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, 0)
    private fun developer(context: Context) = VersionPolicies.Common.channel(installedVersionName(context)) == jp.linkserver.goldy.ReleaseChannel.IntDev
    fun isShowLatestReleaseForTestingEnabled(context: Context): Boolean = developer(context) && preferences(context).getBoolean(PREVIEW, false)
    fun setShowLatestReleaseForTestingEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(PREVIEW, enabled && developer(context)).apply()
    }
    fun getUpdateCurrentVersionOverrideForTesting(context: Context): String =
        if (developer(context)) preferences(context).getString(OVERRIDE, "").orEmpty() else ""
    fun setUpdateCurrentVersionOverrideForTesting(context: Context, override: String) {
        preferences(context).edit().apply {
            if (developer(context) && override.isNotBlank()) putString(OVERRIDE, override.trim()) else remove(OVERRIDE)
        }.apply()
    }

    internal class HostUpdates(private val app: Context,
        private val store: UpdateStateStore = UpdateStateStores.preferences(app, ApkUpdateProfile.repository)) {
        private val legacy = preferences(app)
        val hostPrefs = app.getSharedPreferences("goldy_host_updates", 0)
        val engine = Goldy.create(app, GoldyConfig(ApkUpdateProfile.repository, ApkUpdateProfile.policy,
            userAgent = "BeastLocator/${installedVersionName(app)}"), store)
        private val gate = Mutex()
        private var initialized = false
        suspend fun initialize() = gate.withLock {
            if (!initialized) {
                if (!hostPrefs.getBoolean("migration_v1", false)) {
                    val tag = legacy.getString("dismissed_update_tag", null)?.takeIf { it.isNotBlank() && it.length <= 512 }?.lowercase(Locale.ROOT)
                    val previous = store.read()
                    store.write(previous.copy(ignoredTags = previous.ignoredTags + listOfNotNull(tag)))
                    withContext(Dispatchers.IO) {
                        kotlin.check(hostPrefs.edit().putLong("legacy_last_check_ms", legacy.getLong("last_check_ms", 0))
                            .putBoolean("migration_v1", true).commit())
                    }
                    // The previous in-memory DTO lacked authenticated release/asset metadata. Fetch it afresh.
                }
                engine.updateOptions(options())
                engine.restoreCachedState()
                if (hostPrefs.getString("hidden_banner_tag", null)?.equals(engine.state.value.notificationUpdate?.release?.tag, true) == true) engine.hideBanner()
                initialized = true
            }
        }
        private fun options() = updateOptions(installedVersionName(app), legacy.getBoolean(PREVIEW, false), legacy.getString(OVERRIDE, null))
        suspend fun check(manual: Boolean): CheckOutcome {
            initialize()
            engine.updateOptions(options())
            val result = engine.check(if (manual) CheckTrigger.Manual else CheckTrigger.Automatic)
            if (hostPrefs.getString("hidden_banner_tag", null)?.equals(engine.state.value.notificationUpdate?.release?.tag, true) == true) engine.hideBanner()
            return result
        }
    }
}
