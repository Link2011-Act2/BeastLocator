package jp.linkserver.beastlocator

import android.app.Application
import android.content.Intent
import jp.linkserver.goldy.ApkDownloader
import jp.linkserver.goldy.ApkInstaller
import jp.linkserver.goldy.*
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.lang.reflect.Proxy
import java.security.MessageDigest

/** Test executor only. All actual Gold crypto, download and installer checks remain enabled. */
internal class InlineTransferExecution : UpdateTransferExecution {
    lateinit var runtime: UpdateTransferRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    override suspend fun enqueue(ticket: String, size: Long?): Boolean {
        job = scope.launch { runtime.runScheduledTransfer(ticket) }
        return true
    }
    override suspend fun cancel() { job?.cancelAndJoin() }
}
internal fun transferTestModel(app: Application, downloader: ApkDownloader, installer: ApkInstaller,
    onActivity: (Intent) -> Unit): UpdateDownloadViewModel {
    val execution = InlineTransferExecution()
    val runtime = UpdateTransferRuntime(app, downloader, installer, execution, onActivity)
    execution.runtime = runtime
    return UpdateDownloadViewModel(app, runtime).apply { onForegroundChanged(true) }
}

/** Only replaces HTTP transport. Real inspector, full digest and install lease checks still run. */
internal fun selfApkDownloader(app: Application, chunkDelay: Long = 0, file: File = File(app.applicationInfo.sourceDir)): ApkDownloader {
    val connectionType = Class.forName("jp.linkserver.goldy.AssetConnection")
    val transportType = Class.forName("jp.linkserver.goldy.AssetTransport")
    val transport = Proxy.newProxyInstance(transportType.classLoader, arrayOf(transportType)) { _, method, _ ->
        check(method.name == "open")
        val stream = object : java.io.FilterInputStream(file.inputStream()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (chunkDelay > 0) Thread.sleep(chunkDelay)
                return super.read(buffer, offset, minOf(length, 32768))
            }
        }
        Proxy.newProxyInstance(connectionType.classLoader, arrayOf(connectionType)) { _, call, values -> when (call.name) {
            "getStatus" -> 200
            "header" -> if (values?.firstOrNull() == "Content-Length") file.length().toString() else null
            "input" -> stream
            "close" -> { stream.close(); Unit }
            else -> error(call.name)
        } }
    }
    return ApkDownloader::class.java.getConstructor(ApkDownloadConfig::class.java, File::class.java,
        ApkInspector::class.java, transportType).newInstance(ApkDownloadConfig(ApkUpdateProfile.repository,
        ApkUpdateProfile.selection, ApkVersionBindings.Published, maxAttempts = 1),
        File(app.cacheDir, "goldy-apks").apply { mkdirs() }, AndroidApkInspector(app), transport)
}

internal fun selfApkCandidate(app: Application, file: File = File(app.applicationInfo.sourceDir)): UpdateCandidate {
    val tag = "v${app.packageManager.getPackageInfo(app.packageName, 0).versionName}"
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; hash.update(b, 0, n) } }
    val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    return UpdateCandidate(GitHubRelease(701, tag, url = "https://github.com/Link2011-Act2/BeastLocator/releases/tag/$tag", assets = listOf(
        GitHubAsset(702, "beastlocator_test.apk", "https://github.com/Link2011-Act2/BeastLocator/releases/download/$tag/beastlocator_test.apk", file.length(), "sha256:$digest"))))
}
