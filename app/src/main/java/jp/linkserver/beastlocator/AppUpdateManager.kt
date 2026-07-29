package jp.linkserver.beastlocator

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class ApkDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?
)

object AppUpdateManager {
    private const val REPOSITORY_URL = "https://github.com/Link2011-Act2/BeastLocator"
    private const val UPDATE_PREFS = "github_release_updates"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_DISMISSED_UPDATE_TAG = "dismissed_update_tag"
    private const val KEY_SHOW_LATEST_FOR_TESTING = "show_latest_release_for_testing"
    private const val KEY_CURRENT_VERSION_OVERRIDE_FOR_TESTING =
        "current_version_override_for_testing"
    private const val CHECK_INTERVAL_MILLIS = 8L * 60L * 60L * 1000L
    private const val MAX_QUEUED_OPERATIONS = 4
    private const val MAX_APK_BYTES = 250L * 1024L * 1024L
    private const val PROGRESS_UPDATE_STEP_BYTES = 256L * 1024L
    private const val MAX_REDIRECTS = 5

    private val executor = ThreadPoolExecutor(
        2,
        2,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_QUEUED_OPERATIONS),
        { runnable -> Thread(runnable, "beastlocator-updater").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    class Operation internal constructor(
        private val cancelled: AtomicBoolean,
        private val futureRef: AtomicReference<Future<*>?>,
        private val connectionRef: AtomicReference<HttpURLConnection?>
    ) {
        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            connectionRef.getAndSet(null)?.disconnect()
            futureRef.getAndSet(null)?.cancel(true)
            executor.purge()
        }
    }

    fun checkForUpdate(
        context: Context,
        force: Boolean = false,
        onResult: (Result<AppUpdateInfo?>) -> Unit
    ): Operation? {
        val appContext = context.applicationContext
        if (!force && !shouldCheckForUpdates(appContext)) return null
        return submit { cancelled, _ ->
            val result = runCatching {
                val currentVersion = resolveUpdateCurrentVersionForTesting(appContext)
                GitHubReleaseChecker.check(
                    repositoryUrl = REPOSITORY_URL,
                    currentVersion = currentVersion,
                    showLatestForTesting = isShowLatestReleaseForTestingEnabled(appContext)
                )
            }
            appContext.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
                .edit { putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()) }
            postIfActive(cancelled) { onResult(result) }
        }
    }

    fun downloadApk(
        context: Context,
        updateInfo: AppUpdateInfo,
        onProgress: (ApkDownloadProgress) -> Unit,
        onResult: (Result<File>) -> Unit
    ): Operation {
        val appContext = context.applicationContext
        return submit { cancelled, connectionRef ->
            val result = runCatching {
                val downloadUrl = updateInfo.apkDownloadUrl
                    ?: error("APK asset is not available")
                check(isTrustedGitHubDownloadUrl(downloadUrl)) {
                    "Untrusted APK download URL"
                }
                val file = downloadApkFile(
                    appContext,
                    downloadUrl,
                    updateInfo.apkAssetName ?: "${updateInfo.tagName}.apk",
                    cancelled,
                    connectionRef,
                    onProgress
                )
                runCatching { validateApkPackage(appContext, file) }
                    .onFailure { file.delete() }
                    .getOrThrow()
                file
            }
            postIfActive(cancelled) { onResult(result) }
        }
    }

    fun isUpdateNotificationDismissed(context: Context, tagName: String): Boolean =
        context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DISMISSED_UPDATE_TAG, null)
            .equals(tagName, ignoreCase = true)

    fun dismissUpdateNotificationUntilNextVersion(context: Context, tagName: String) {
        context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_DISMISSED_UPDATE_TAG, tagName) }
    }

    fun isShowLatestReleaseForTestingEnabled(context: Context): Boolean {
        if (!isIntDevBuild()) return false
        return context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_LATEST_FOR_TESTING, false)
    }

    fun setShowLatestReleaseForTestingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).edit {
            if (isIntDevBuild()) {
                putBoolean(KEY_SHOW_LATEST_FOR_TESTING, enabled)
            } else {
                remove(KEY_SHOW_LATEST_FOR_TESTING)
            }
        }
    }

    fun getUpdateCurrentVersionOverrideForTesting(context: Context): String {
        if (!isIntDevBuild()) return ""
        return context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CURRENT_VERSION_OVERRIDE_FOR_TESTING, null)
            .orEmpty()
    }

    fun setUpdateCurrentVersionOverrideForTesting(context: Context, override: String) {
        val trimmedOverride = override.trim()
        context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).edit {
            if (!isIntDevBuild() || trimmedOverride.isBlank()) {
                remove(KEY_CURRENT_VERSION_OVERRIDE_FOR_TESTING)
            } else {
                putString(KEY_CURRENT_VERSION_OVERRIDE_FOR_TESTING, trimmedOverride)
            }
        }
    }

    fun resolveUpdateCurrentVersionForTesting(context: Context): String =
        getUpdateCurrentVersionOverrideForTesting(context).ifBlank { BuildConfig.VERSION_NAME }

    fun canRequestPackageInstalls(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    fun openUnknownAppInstallSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri()
        )
        context.startActivity(intent)
    }

    fun openPackageInstaller(context: Context, apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        check(resolved != null) { "Package installer is not available" }
        context.startActivity(intent)
    }

    private fun shouldCheckForUpdates(context: Context): Boolean {
        if (isShowLatestReleaseForTestingEnabled(context)) return true
        val lastCheck = context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK_MS, 0L)
        val elapsed = System.currentTimeMillis() - lastCheck
        return elapsed < 0L || elapsed >= CHECK_INTERVAL_MILLIS
    }

    private fun isIntDevBuild(): Boolean =
        ReleaseChannelDetector.detect(BuildConfig.VERSION_NAME) == ReleaseChannel.INTDEV

    private fun submit(
        block: (
            cancelled: AtomicBoolean,
            connectionRef: AtomicReference<HttpURLConnection?>
        ) -> Unit
    ): Operation {
        val cancelled = AtomicBoolean(false)
        val futureRef = AtomicReference<Future<*>?>(null)
        val connectionRef = AtomicReference<HttpURLConnection?>(null)
        val operation = Operation(cancelled, futureRef, connectionRef)
        val future = runCatching {
            executor.submit {
                try {
                    if (!cancelled.get()) block(cancelled, connectionRef)
                } finally {
                    connectionRef.getAndSet(null)?.disconnect()
                    futureRef.set(null)
                }
            }
        }.getOrElse { error ->
            cancelled.set(true)
            throw IllegalStateException("Updater queue is full", error)
        }
        futureRef.set(future)
        if (cancelled.get()) operation.cancel()
        return operation
    }

    private fun postIfActive(cancelled: AtomicBoolean, action: () -> Unit) {
        if (cancelled.get()) return
        mainHandler.post {
            if (!cancelled.get()) {
                runCatching(action).onFailure {
                    AppDiagnostics.warn("updater_callback_failed", error = it)
                }
            }
        }
    }

    private fun downloadApkFile(
        context: Context,
        downloadUrl: String,
        assetName: String,
        cancelled: AtomicBoolean,
        connectionRef: AtomicReference<HttpURLConnection?>,
        onProgress: (ApkDownloadProgress) -> Unit
    ): File {
        val safeName = assetName.substringAfterLast('/')
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .ifBlank { "BeastLocator-update.apk" }
            .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
        val updatesDir = File(context.cacheDir, "updates").apply {
            check(isDirectory || mkdirs()) { "Could not create update cache" }
        }
        updatesDir.listFiles()
            ?.filter { it.isFile && (it.extension.equals("apk", true) || it.name.endsWith(".part")) }
            ?.forEach { it.delete() }
        val destination = File(updatesDir, safeName)
        val partial = File(updatesDir, "$safeName.part")
        val connection = openDownloadConnection(downloadUrl, connectionRef)
        try {
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            check(totalBytes == null || totalBytes <= MAX_APK_BYTES) { "APK is too large" }
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloadedBytes = 0L
                    var lastProgressBytes = -PROGRESS_UPDATE_STEP_BYTES
                    postIfActive(cancelled) {
                        onProgress(ApkDownloadProgress(downloadedBytes, totalBytes))
                    }
                    while (true) {
                        check(!cancelled.get() && !Thread.currentThread().isInterrupted) {
                            "Download cancelled"
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        downloadedBytes += read
                        check(downloadedBytes <= MAX_APK_BYTES) { "APK is too large" }
                        output.write(buffer, 0, read)
                        if (downloadedBytes - lastProgressBytes >= PROGRESS_UPDATE_STEP_BYTES) {
                            lastProgressBytes = downloadedBytes
                            postIfActive(cancelled) {
                                onProgress(ApkDownloadProgress(downloadedBytes, totalBytes))
                            }
                        }
                    }
                    check(totalBytes == null || downloadedBytes == totalBytes) {
                        "APK download was incomplete"
                    }
                }
            }
            check(partial.renameTo(destination)) { "Could not finalize APK" }
            return destination
        } catch (error: Throwable) {
            destination.delete()
            throw error
        } finally {
            connectionRef.compareAndSet(connection, null)
            connection.disconnect()
            partial.delete()
        }
    }

    private fun openDownloadConnection(
        initialUrl: String,
        connectionRef: AtomicReference<HttpURLConnection?>
    ): HttpURLConnection {
        var currentUri = URI(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            check(isTrustedGitHubDownloadUrl(currentUri.toString())) {
                "Untrusted APK download redirect"
            }
            val connection = (URL(currentUri.toString()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "BeastLocator-Updater/${BuildConfig.VERSION_NAME}")
            }
            connectionRef.set(connection)
            val status = connection.responseCode
            if (status in 200..299) return connection
            if (status !in REDIRECT_STATUS_CODES || redirectCount >= MAX_REDIRECTS) {
                connection.disconnect()
                error("APK download returned HTTP $status")
            }
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            check(!location.isNullOrBlank()) { "APK redirect has no destination" }
            currentUri = currentUri.resolve(location)
        }
        error("Too many APK download redirects")
    }

    private fun validateApkPackage(context: Context, apkFile: File) {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        }
        check(info?.packageName == context.packageName) {
            "Downloaded APK is not a BeastLocator package"
        }
    }

    private val REDIRECT_STATUS_CODES = setOf(
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER,
        307,
        308
    )
}
