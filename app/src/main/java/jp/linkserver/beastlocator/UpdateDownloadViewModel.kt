package jp.linkserver.beastlocator

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import jp.linkserver.goldy.*
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Identifies the pinned catalog/asset snapshot for presentation; never authenticates an APK. */
internal fun transferIdentity(candidate: UpdateCandidate?): String? = candidate?.release?.let { release ->
    val asset = ApkUpdateProfile.selection.select(release)
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(release.id.toString(), release.tag, release.url, asset?.id?.toString(), asset?.downloadUrl,
        asset?.size?.toString(), asset?.digest).forEach { value ->
        val bytes = value?.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
        if (bytes != null) digest.update(bytes)
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal data class UpdateDownloadState(
    val tag: String = "",
    val transfer: UpdateTransferState = UpdateTransferState.Idle,
    val requests: List<InstallRequest> = emptyList(),
    val message: String? = null,
    val restoring: Boolean = true,
    val releaseIdentity: String? = null,
    val waiting: Boolean = false,
    val retainedProgress: ApkProgress? = null,
    val preparedRequest: InstallRequest? = null
) {
    val busy get() = transfer is UpdateTransferState.Downloading || transfer == UpdateTransferState.Verifying ||
        transfer == UpdateTransferState.PreparingInstall
    val hasTransfer get() = requests.isNotEmpty() || tag.isNotBlank() && (busy || waiting ||
        transfer == UpdateTransferState.Ready || transfer == UpdateTransferState.PermissionRequired)
}

/** Presentation bridge only; clearing a ViewModel never cancels an Android-managed transfer. */
internal class UpdateDownloadViewModel @JvmOverloads constructor(
    application: Application,
    internal val runtime: UpdateTransferRuntime = UpdateTransferRuntime.get(application)
) : AndroidViewModel(application) {
    val state = runtime.state
    val selection = runtime.selection
    fun selectCandidate(candidate: UpdateCandidate?) = runtime.selectCandidate(candidate)
    fun action(candidate: UpdateCandidate?, action: UpdateAction) = runtime.action(candidate, action)
    fun cancel() = runtime.requestCancel()
    fun onForegroundChanged(visible: Boolean) = runtime.setForeground(this, visible)
    override fun onCleared() { runtime.setForeground(this, false); super.onCleared() }
}
