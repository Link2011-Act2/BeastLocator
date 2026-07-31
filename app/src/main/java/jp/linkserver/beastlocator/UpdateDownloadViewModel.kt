package jp.linkserver.beastlocator

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState

    data class Downloading(
        val progress: ApkDownloadProgress?
    ) : UpdateDownloadState

    data class Downloaded(
        val apkFile: SingleUseValue<File>
    ) : UpdateDownloadState

    data class Failed(
        val error: Throwable
    ) : UpdateDownloadState

    data object Cancelled : UpdateDownloadState
}

/** A value that can be claimed once, including across LiveData re-delivery after rotation. */
internal class SingleUseValue<T>(private val value: T) {
    private val claimed = AtomicBoolean(false)

    fun claim(): T? = if (claimed.compareAndSet(false, true)) value else null

    fun peek(): T = value
}

internal class UpdateDownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableLiveData<UpdateDownloadState>(UpdateDownloadState.Idle)
    val state: LiveData<UpdateDownloadState> = mutableState

    private var downloadOperation: AppUpdateManager.Operation? = null

    var waitingForInstallPermission: Boolean = false
        private set
    private var installSettingsOpened: Boolean = false

    fun startDownload(updateInfo: AppUpdateInfo) {
        if (downloadOperation != null || mutableState.value is UpdateDownloadState.Downloading) return
        mutableState.value = UpdateDownloadState.Downloading(progress = null)
        downloadOperation = runCatching {
            AppUpdateManager.downloadApk(
                context = getApplication(),
                updateInfo = updateInfo,
                onProgress = { progress ->
                    if (downloadOperation != null) {
                        mutableState.value = UpdateDownloadState.Downloading(progress)
                    }
                },
                onResult = { result ->
                    downloadOperation = null
                    result.onSuccess { apkFile ->
                        mutableState.value = UpdateDownloadState.Downloaded(
                            SingleUseValue(apkFile)
                        )
                    }.onFailure(::reportFailure)
                }
            )
        }.getOrElse { error ->
            reportFailure(error)
            null
        }
    }

    fun cancelDownload() {
        downloadOperation?.cancel()
        downloadOperation = null
        mutableState.value = UpdateDownloadState.Cancelled
    }

    fun claimDownloadedFile(state: UpdateDownloadState.Downloaded): File? {
        if (mutableState.value !== state) return null
        val file = state.apkFile.claim() ?: return null
        mutableState.value = UpdateDownloadState.Idle
        return file
    }

    fun reportInstallerFailure(error: Throwable) {
        reportFailure(error)
    }

    fun beginInstallPermissionRequest() {
        waitingForInstallPermission = true
        installSettingsOpened = true
    }

    fun markInstallSettingsOpenFailed() {
        installSettingsOpened = false
    }

    fun consumeInstallSettingsReturn(): Boolean {
        if (!waitingForInstallPermission || !installSettingsOpened) return false
        installSettingsOpened = false
        return true
    }

    fun clearInstallPermissionRequest() {
        waitingForInstallPermission = false
        installSettingsOpened = false
    }

    private fun reportFailure(error: Throwable) {
        downloadOperation = null
        mutableState.value = UpdateDownloadState.Failed(error)
        AppDiagnostics.warn("update_download_failed", error = error)
    }

    override fun onCleared() {
        downloadOperation?.cancel()
        downloadOperation = null
        (mutableState.value as? UpdateDownloadState.Downloaded)
            ?.apkFile
            ?.peek()
            ?.delete()
        super.onCleared()
    }
}
