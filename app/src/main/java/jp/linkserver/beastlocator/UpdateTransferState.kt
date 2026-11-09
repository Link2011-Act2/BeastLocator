package jp.linkserver.beastlocator

import jp.linkserver.goldy.ApkProgress

/** View presentation types. APK authority and verification are exclusively Gold Core's. */
internal enum class UpdateAction { Download, CancelDownload, Install, OpenInstallPermissionSettings }

internal sealed interface UpdateTransferState {
    data object Idle : UpdateTransferState
    data class Downloading(val progress: ApkProgress) : UpdateTransferState
    data object Verifying : UpdateTransferState
    data object Ready : UpdateTransferState
    data object PreparingInstall : UpdateTransferState
    data object PermissionRequired : UpdateTransferState
    data object HandedOff : UpdateTransferState
    data object Cancelled : UpdateTransferState
    data class Failed(val message: String, val retry: UpdateAction? = UpdateAction.Download) : UpdateTransferState
}
