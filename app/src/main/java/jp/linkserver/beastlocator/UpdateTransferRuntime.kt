package jp.linkserver.beastlocator

import android.app.Application
import android.content.Context
import android.content.Intent
import jp.linkserver.goldy.*
import jp.linkserver.beastlocator.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

/** Application state; Android jobs own transfers. No Activity/ViewModel lifetime owns the bytes. */
internal class UpdateTransferRuntime(
    private val application: Application,
    private val downloader: ApkDownloader = ApkDownloader.createResumable(application, ApkUpdateProfile.downloadConfig(application)),
    private val installer: ApkInstaller = ApkInstaller.create(application),
    private val execution: UpdateTransferExecution = AndroidUpdateTransferExecution(application),
    private val startActivity: (Intent) -> Unit = { application.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
) {
    companion object {
        @Volatile private var instance: UpdateTransferRuntime? = null
        fun get(context: Context): UpdateTransferRuntime = instance ?: synchronized(this) {
            instance ?: UpdateTransferRuntime(context.applicationContext as Application).also { instance = it }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val initialized = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(UpdateDownloadState())
    val state = mutableState.asStateFlow()
    private val mutableSelection = MutableStateFlow<UpdateCandidate?>(null)
    val selection = mutableSelection.asStateFlow()
    private val ledger = application.getSharedPreferences("goldy_apk_transfer_state", 0)
    private val foregroundOwners = mutableSetOf<Any>()
    private var hasEnteredForeground = false
    private var launchGeneration = 0L
    private var operation: Job? = null
    private var cancelling = false
    @Volatile private var activeTicket: String? = null
    private var settingsInFlight = false
    private var receipt: VerifiedApk? = null
    private var receiptRelease: GitHubRelease? = null
    private var selectedRequest: InstallRequest? = null
    private var selectedRequestTag: String? = null
    private var selectedRequestIdentity: String? = null

    init {
        scope.launch {
            try {
                downloader.cleanPartialFiles()
                refreshRequests()
                val snapshot = ledger.getString("transfer_snapshot", null)?.let(UpdateTransferSnapshot::decode)
                mutableSelection.value = snapshot
                activeTicket = ledger.getString("transfer_ticket", null)
                settingsInFlight = ledger.getBoolean("permission_settings_open", false)
                val savedId = ledger.getString("selected_request", null)
                selectedRequest = state.value.requests.firstOrNull { it.id == savedId }
                // Recover a prepared APK without borrowing another transfer's metadata.
                if (selectedRequest == null && snapshot == null && activeTicket == null)
                    selectedRequest = state.value.requests.maxByOrNull { it.metadata.versionCode }
                val id = selectedRequest?.id
                if (activeTicket != null && snapshot != null && selectedRequest != null &&
                    ledger.getString("request_identity_$id", null) != transferIdentity(snapshot)) selectedRequest = null
                if (selectedRequest != null) {
                    selectedRequestTag = ledger.getString("request_tag_$id", null)
                    selectedRequestIdentity = ledger.getString("request_identity_$id", null)
                    if (activeTicket != null && snapshot != null) {
                        activeTicket = null
                        persist { remove("transfer_ticket").putString("transfer_phase", "finished") }
                    }
                    mutableState.update { it.copy(tag = selectedRequestTag.orEmpty(), releaseIdentity = selectedRequestIdentity) }
                    presentRequest(checkNotNull(selectedRequest))
                } else if (snapshot != null) {
                    mutableState.update { it.copy(tag = snapshot.release.tag, releaseIdentity = transferIdentity(snapshot)) }
                    if (activeTicket != null) {
                        if (ledger.getString("transfer_phase", null) != "enqueued") {
                            // Restore a checkpoint for explicit continuation; never replay an installer.
                            activeTicket = null
                            persist { remove("transfer_ticket").putString("transfer_phase", "paused") }
                            execution.cancel()
                        }
                        paused()
                    }
                    else if (ledger.getBoolean("permission_waiting", false)) reconcilePermission()
                    else if (ledger.getString("transfer_phase", null) == "paused") paused()
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { activeTicket = null; fail(text(R.string.goldy_update_restore_failed), null) }
            finally { mutableState.update { it.copy(restoring = false) }; initialized.complete(Unit) }
        }
    }

    suspend fun awaitInitialization() = initialized.await()
    fun hasScheduledTicket(ticket: String?) = ticket != null && ticket == activeTicket

    fun setForeground(owner: Any, visible: Boolean) {
        if (!visible) { foregroundOwners.remove(owner); return }
        if (!foregroundOwners.add(owner)) return
        val returning = hasEnteredForeground
        hasEnteredForeground = true
        val generation = launchGeneration
        scope.launch {
            try {
                initialized.await()
                settingsInFlight = false
                persist { putBoolean("permission_settings_open", false) }
                if (operation?.isActive != true && !state.value.busy) {
                    // Cold-start initialization already reconciled the installed APK.
                    // Do not hide/reject the first visible Install behind duplicate work.
                    if (returning) refreshRequests()
                    if (generation == launchGeneration && operation?.isActive != true) {
                        selectedRequest?.let(::presentRequest)
                        reconcilePermission()
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { fail(text(R.string.goldy_update_restore_failed), null) }
        }
    }
    fun selectCandidate(candidate: UpdateCandidate?) {
        if (state.value.restoring) { scope.launch { initialized.await(); selectCandidate(candidate) }; return }
        if (candidate == null && state.value.hasTransfer) return
        val pinned = state.value.busy || state.value.waiting ||
            state.value.transfer == UpdateTransferState.PermissionRequired
        if (pinned && state.value.releaseIdentity != null && transferIdentity(candidate) != state.value.releaseIdentity) return
        if (activeTicket == null && operation?.isActive != true || transferIdentity(candidate) == state.value.releaseIdentity)
            mutableSelection.value = candidate
    }
    fun action(candidate: UpdateCandidate?, action: UpdateAction) {
        if (action == UpdateAction.CancelDownload) { requestCancel(); return }
        if (action == UpdateAction.Install && state.value.transfer == UpdateTransferState.HandedOff) return
        command {
            mutableState.update { it.copy(message = null) }
            when (action) {
                UpdateAction.OpenInstallPermissionSettings -> permissionSettings()
                UpdateAction.Download -> schedule(candidate)
                UpdateAction.Install -> {
                    val ready = selectedRequest.takeIf { candidate == null || selectedRequestTag == candidate.release.tag &&
                        selectedRequestIdentity == transferIdentity(candidate) }
                    if (ready != null) launch(ready)
                    else if (candidate != null && receiptMatches(candidate)) {
                        prepareReceipt(candidate)
                        selectedRequest?.takeIf { state.value.transfer == UpdateTransferState.Ready }?.let { launch(it) }
                    } else schedule(candidate)
                }
                UpdateAction.CancelDownload -> Unit
            }
        }
    }
    private fun command(block: suspend () -> Unit) {
        if (state.value.restoring || cancelling || operation?.isActive == true || state.value.busy || foregroundOwners.isEmpty()) return
        operation = scope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { fail(errorMessage(error)) }
        }
    }

    private suspend fun schedule(candidate: UpdateCandidate?) {
        candidate ?: return
        val snapshot = UpdateTransferSnapshot.encode(candidate)
        val identity = transferIdentity(candidate)
        if (identity != selectedRequestIdentity) {
            selectedRequest = null; selectedRequestTag = null; selectedRequestIdentity = null
            persist { remove("selected_request") }
            mutableState.update { it.copy(preparedRequest = null) }
        } else if (selectedRequest != null) { presentRequest(checkNotNull(selectedRequest)); return }
        mutableSelection.value = candidate
        mutableState.update { it.copy(tag = candidate.release.tag, releaseIdentity = identity, waiting = false) }
        persist { putString("transfer_snapshot", snapshot) }
        when (installer.permissionStatus()) {
            InstallPermission.UserPermissionRequired -> {
                persist { putBoolean("permission_waiting", true) }
                mutableState.update { it.copy(transfer = UpdateTransferState.PermissionRequired) }; return
            }
            InstallPermission.Allowed -> Unit
            else -> { fail(text(R.string.goldy_update_install_failed)); return }
        }
        if (activeTicket != null) execution.cancel()
        discardReceipt()
        val ticket = UUID.randomUUID().toString()
        persist { putString("transfer_ticket", ticket).putString("transfer_phase", "enqueued").putBoolean("permission_waiting", false) }
        activeTicket = ticket
        val saved = downloader.savedProgress(candidate.release)
        mutableState.update { it.copy(transfer = UpdateTransferState.Downloading(saved ?: ApkProgress(0, null, 1)), retainedProgress = saved) }
        if (!execution.enqueue(ticket, ApkUpdateProfile.selection.select(candidate.release)?.size)) paused(text(R.string.goldy_transfer_schedule_failed))
    }

    /** Only an Android execution component calls this. Auto-launch is limited to foreground completion. */
    suspend fun runScheduledTransfer(ticket: String) = withContext(Dispatchers.Main.immediate) {
        initialized.await()
        val job = currentCoroutineContext().job
        operation?.takeIf { it !== job && !it.isCompleted }?.join()
        if (!hasScheduledTicket(ticket)) return@withContext
        operation = job
        var interrupted = false
        var publishReady = false
        try {
            val candidate = UpdateTransferSnapshot.decode(checkNotNull(ledger.getString("transfer_snapshot", null)))
            val savedProgress = downloader.savedProgress(candidate.release)
            var previousBytes = savedProgress?.bytes ?: 0L
            persist { putString("transfer_phase", "running") }
            mutableState.update { it.copy(tag = candidate.release.tag, releaseIdentity = transferIdentity(candidate), waiting = false,
                transfer = UpdateTransferState.Downloading(savedProgress ?: ApkProgress(0, ApkUpdateProfile.selection.select(candidate.release)?.size, 1)), message = null) }
            receipt = downloader.downloadResumable(candidate.release) { progress ->
                val restarted = progress.bytes < previousBytes
                previousBytes = progress.bytes
                if (hasScheduledTicket(ticket)) mutableState.update { it.copy(transfer = if (progress.total != null && progress.bytes >= progress.total!!)
                    UpdateTransferState.Verifying else UpdateTransferState.Downloading(progress), retainedProgress = progress,
                    message = if (restarted) text(R.string.goldy_transfer_restarted) else it.message) }
            }
            receiptRelease = candidate.release
            prepareReceipt(candidate, publishReady = false)
            publishReady = selectedRequest != null && state.value.transfer == UpdateTransferState.PreparingInstall
        } catch (cancel: CancellationException) {
            interrupted = hasScheduledTicket(ticket)
            withContext(NonCancellable) {
                discardReceipt()
                if (interrupted) { persist { putString("transfer_phase", "paused") }; paused() }
            }
            throw cancel
        } catch (error: Exception) {
            discardReceipt()
            val retained = ledger.getString("transfer_snapshot", null)?.let(UpdateTransferSnapshot::decode)?.release?.let { downloader.savedProgress(it) }
            if (retained != null) paused(errorMessage(error)) else fail(errorMessage(error))
        } finally {
            withContext(NonCancellable) {
                if (hasScheduledTicket(ticket) && !interrupted) {
                    activeTicket = null
                    persist { remove("transfer_ticket").putString("transfer_phase", if (state.value.waiting) "paused" else "finished") }
                } else publishReady = false
                try { refreshRequests() } catch (_: Exception) { mutableState.update { it.copy(message = text(R.string.goldy_update_restore_failed)) } }
            }
            try {
                selectedRequest?.takeIf { publishReady }?.let { request ->
                    mutableState.update { it.copy(transfer = UpdateTransferState.Ready, waiting = false, message = null) }
                    // Only this live completion may auto-launch; restored Ready is always explicit.
                    try {
                        if (currentCoroutineContext().isActive && foregroundOwners.isNotEmpty()) launch(request)
                    } catch (cancel: CancellationException) {
                        if (state.value.transfer == UpdateTransferState.PreparingInstall)
                            mutableState.update { it.copy(transfer = UpdateTransferState.Ready) }
                        throw cancel
                    }
                }
            } finally {
                // Keep commands serialized through the installer handoff as well as preparation.
                if (operation === job) operation = null
            }
        }
    }

    fun requestCancel() { scope.launch { this@UpdateTransferRuntime.cancel() } }
    suspend fun cancel(expectedTicket: String? = activeTicket): Unit = withContext(Dispatchers.Main.immediate) {
        initialized.await()
        if (cancelling || expectedTicket != null && expectedTicket != activeTicket) return@withContext
        if (!state.value.busy && activeTicket == null && !state.value.waiting) return@withContext
        cancelling = true
        activeTicket = null // Reject late progress and stale notification actions before cancelling the job.
        try {
            persist { remove("transfer_ticket").putString("transfer_phase", "cancelled") }
            operation?.cancelAndJoin()
            execution.cancel()
            ledger.getString("transfer_snapshot", null)?.let(UpdateTransferSnapshot::decode)?.release?.let { downloader.discardPartial(it) }
            mutableState.update { it.copy(transfer = UpdateTransferState.Cancelled, waiting = false, message = null, retainedProgress = null) }
        } catch (_: Exception) { fail(text(R.string.goldy_update_restore_failed), null) }
        finally {
            cancelling = false
            application.getSystemService(android.app.NotificationManager::class.java).cancel(UpdateTransferNotifications.ID)
        }
    }
    fun executionFailed(ticket: String) { scope.launch {
        initialized.await()
        if (hasScheduledTicket(ticket)) {
            try { persist { putString("transfer_phase", "paused") }; paused(text(R.string.goldy_transfer_schedule_failed)) }
            catch (_: Exception) { activeTicket = null; fail(text(R.string.goldy_update_restore_failed), null) }
        }
    } }
    private suspend fun paused(message: String = text(R.string.goldy_transfer_paused)) {
        val saved = ledger.getString("transfer_snapshot", null)?.let(UpdateTransferSnapshot::decode)?.release?.let { downloader.savedProgress(it) }
        mutableState.update { it.copy(transfer = UpdateTransferState.Failed(message, UpdateAction.Download), waiting = true, message = message, retainedProgress = saved) }
    }
    private fun receiptMatches(candidate: UpdateCandidate): Boolean = receipt != null && receiptRelease?.let {
        it.id == candidate.release.id && it.tag == candidate.release.tag && it.url == candidate.release.url &&
            ApkUpdateProfile.selection.select(candidate.release) == receipt?.asset
    } == true
    private suspend fun prepareReceipt(candidate: UpdateCandidate, publishReady: Boolean = true) {
        mutableState.update { it.copy(transfer = UpdateTransferState.PreparingInstall) }
        when (val prepared = installer.prepare(checkNotNull(receipt))) {
            is InstallPreparation.PermissionRequired -> {
                persist { putBoolean("permission_waiting", true) }
                mutableState.update { it.copy(transfer = UpdateTransferState.PermissionRequired) }
            }
            is InstallPreparation.Failed -> { discardReceipt(); fail(installError(prepared.error)) }
            is InstallPreparation.Ready -> {
                selectedRequest = prepared.request
                selectedRequestTag = candidate.release.tag
                selectedRequestIdentity = transferIdentity(candidate)
                persist { putString("selected_request", prepared.request.id).putString("request_tag_${prepared.request.id}", selectedRequestTag)
                    .putString("request_identity_${prepared.request.id}", selectedRequestIdentity).putBoolean("permission_waiting", false) }
                discardReceipt() // Only the caller's cache receipt; the durable SDK lease stays alive.
                refreshRequests()
                if (publishReady) mutableState.update { it.copy(transfer = UpdateTransferState.Ready, waiting = false, message = null) }
            }
        }
    }

    private fun presentRequest(request: InstallRequest) {
        mutableState.update { it.copy(preparedRequest = request) }
        when {
            installer.permissionStatus() == InstallPermission.UserPermissionRequired -> mutableState.update { it.copy(transfer = UpdateTransferState.PermissionRequired) }
            installer.permissionStatus() == InstallPermission.Allowed -> mutableState.update { it.copy(transfer = UpdateTransferState.Ready, message = null) }
            else -> fail(text(R.string.goldy_update_install_failed), null)
        }
    }
    private suspend fun reconcilePermission() {
        if (state.value.transfer != UpdateTransferState.PermissionRequired && !ledger.getBoolean("permission_waiting", false)) return
        if (installer.permissionStatus() == InstallPermission.Allowed) {
            persist { putBoolean("permission_waiting", false) }
            selectedRequest?.let { presentRequest(it) } ?: mutableState.update {
                it.copy(transfer = if (receipt != null) UpdateTransferState.Ready else UpdateTransferState.Idle, message = null)
            }
        } else if (installer.permissionStatus() == InstallPermission.UserPermissionRequired)
            mutableState.update { it.copy(transfer = UpdateTransferState.PermissionRequired) }
        else fail(text(R.string.goldy_update_install_failed), null)
    }
    private suspend fun permissionSettings() {
        if (installer.permissionStatus() == InstallPermission.Allowed) { reconcilePermission(); return }
        if (installer.permissionStatus() != InstallPermission.UserPermissionRequired) { fail(text(R.string.goldy_update_install_failed), null); return }
        if (settingsInFlight || foregroundOwners.isEmpty()) return
        settingsInFlight = true
        persist { putBoolean("permission_waiting", true).putBoolean("permission_settings_open", true) }
        mutableState.update { it.copy(transfer = UpdateTransferState.PermissionRequired) }
        try { startActivity(installer.unknownSourcesSettingsIntent()) }
        catch (_: Exception) { settingsInFlight = false; fail(text(R.string.settings_open_failed), UpdateAction.OpenInstallPermissionSettings) }
    }
    private suspend fun launch(request: InstallRequest) {
        if (foregroundOwners.isEmpty()) { mutableState.update { it.copy(transfer = UpdateTransferState.Ready) }; return }
        if (installer.permissionStatus() != InstallPermission.Allowed) {
            persist { putBoolean("permission_waiting", true) }
            mutableState.update { it.copy(transfer = UpdateTransferState.PermissionRequired) }; return
        }
        launchGeneration++
        mutableState.update { it.copy(transfer = UpdateTransferState.PreparingInstall, message = null) }
        var callbackAttempted = false
        when (val result = installer.launch(request) { intent ->
            check(foregroundOwners.isNotEmpty()) { "Foreground interaction required" }
            callbackAttempted = true
            startActivity(intent)
        }) {
            InstallLaunchResult.HandedOff -> {
                mutableState.update { it.copy(transfer = UpdateTransferState.HandedOff) }
            }
            is InstallLaunchResult.PermissionRequired -> {
                persist { putBoolean("permission_waiting", true) }
                mutableState.update { it.copy(transfer = UpdateTransferState.PermissionRequired) }
            }
            is InstallLaunchResult.Failed -> {
                if (!callbackAttempted && foregroundOwners.isEmpty()) {
                    mutableState.update { it.copy(transfer = UpdateTransferState.Ready, message = text(R.string.goldy_transfer_foreground_install)) }
                } else if (result.error.reason in setOf(InstallFailure.FileMissing, InstallFailure.FileChanged,
                        InstallFailure.InvalidFile, InstallFailure.ApkRejected)) {
                    selectedRequest = null; selectedRequestTag = null; selectedRequestIdentity = null
                    persist { remove("selected_request") }
                    mutableState.update { it.copy(preparedRequest = null) }
                    fail(installError(result.error), UpdateAction.Download)
                } else fail(installError(result.error), UpdateAction.Install)
            }
        }
    }
    private suspend fun refreshRequests() {
        val result = installer.reconcileInstalledUpdates()
        val completed = result.installedRequestIds + result.supersededRequestIds
        val selectedId = ledger.getString("selected_request", null)
        val identity = selectedId?.let { ledger.getString("request_identity_" + it, null) }
        val completedSelection = selectedId != null && selectedId in completed
        val saved = ledger.getString("transfer_snapshot", null)?.let {
            runCatching { UpdateTransferSnapshot.decode(it) }.getOrNull()
        }
        val completedTransfer = completedSelection && identity != null && transferIdentity(saved) == identity
        if (completedTransfer) {
            activeTicket = null
            if (operation !== currentCoroutineContext().job) execution.cancel()
            if (transferIdentity(mutableSelection.value) == identity) mutableSelection.value = null
            mutableState.update { it.copy(tag = "", releaseIdentity = null, transfer = UpdateTransferState.Idle,
                waiting = false, retainedProgress = null, message = null) }
        }
        if (completedSelection) {
            selectedRequest = null; selectedRequestTag = null; selectedRequestIdentity = null
        }
        if (completedSelection || ledger.contains("claimed") || ledger.contains("handed_off") ||
            completed.any { ledger.contains("request_tag_" + it) || ledger.contains("request_identity_" + it) }) {
            persist {
                // Old one-shot claims become an ordinary explicit Install after restoration.
                remove("claimed").remove("handed_off")
                completed.forEach { remove("request_tag_" + it).remove("request_identity_" + it) }
                if (completedSelection) remove("selected_request").putBoolean("permission_waiting", false)
                if (completedTransfer) remove("transfer_snapshot").remove("transfer_ticket").putString("transfer_phase", "finished")
            }
        }
        selectedRequest = selectedRequest?.let { selected -> result.pendingRequests.firstOrNull { it.id == selected.id } }
        mutableState.update { it.copy(requests = result.pendingRequests, preparedRequest = selectedRequest) }
    }
    private suspend fun persist(block: android.content.SharedPreferences.Editor.() -> Unit) = withContext(Dispatchers.IO) {
        check(ledger.edit().apply(block).commit()) { "Could not persist transfer state" }
    }
    private suspend fun discardReceipt() {
        val old = receipt; receipt = null; receiptRelease = null
        if (old != null) withContext(NonCancellable + Dispatchers.IO) {
            if (old.file.exists() && !old.file.delete()) mutableState.update { it.copy(message = text(R.string.goldy_update_cleanup_failed)) }
        }
    }
    private fun fail(message: String, retry: UpdateAction? = UpdateAction.Download) {
        mutableState.update { it.copy(transfer = UpdateTransferState.Failed(message, retry), message = message, waiting = false) }
    }
    private fun text(id: Int) = application.getString(id)
    private fun errorMessage(error: Exception) = if (error is ApkException) when (error.reason) {
        ApkFailure.SignerMismatch -> text(R.string.goldy_update_signing_mismatch)
        ApkFailure.InvalidApk, ApkFailure.PackageMismatch, ApkFailure.VersionCodeTooLow, ApkFailure.VersionMismatch -> text(R.string.goldy_update_invalid_apk)
        else -> text(R.string.goldy_update_download_failed)
    } else text(R.string.goldy_update_download_failed)
    private fun installError(error: InstallError) = when (error.apkFailure) {
        ApkFailure.SignerMismatch -> text(R.string.goldy_update_signing_mismatch)
        ApkFailure.PackageMismatch, ApkFailure.VersionCodeTooLow, ApkFailure.VersionMismatch -> text(R.string.goldy_update_invalid_apk)
        else -> text(R.string.goldy_update_install_failed)
    }
}
