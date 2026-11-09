package jp.linkserver.beastlocator

import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.annotation.RequiresApi
import androidx.work.*
import jp.linkserver.beastlocator.UpdateActivity
import jp.linkserver.beastlocator.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.TimeUnit

/** Android execution belongs to the host; bytes and all APK verification remain in Gold. */
internal interface UpdateTransferExecution {
    suspend fun enqueue(ticket: String, size: Long?): Boolean
    suspend fun cancel()
}

internal class AndroidUpdateTransferExecution(private val context: Context) : UpdateTransferExecution {
    override suspend fun enqueue(ticket: String, size: Long?) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 34) {
            val extras = PersistableBundle().apply { putString(TICKET, ticket) }
            val network = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateTransferJobService::class.java))
                .setUserInitiated(true).setRequiredNetwork(network).setExtras(extras)
                .setEstimatedNetworkBytes(size ?: JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), 0L).build()
            scheduler(context).schedule(job) == JobScheduler.RESULT_SUCCESS
        } else {
            val request = OneTimeWorkRequestBuilder<UpdateTransferWorker>()
                .setInputData(workDataOf(TICKET to ticket))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
                .result.get(15, TimeUnit.SECONDS)
            true
        }
    }
    override suspend fun cancel() = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 34) scheduler(context).cancel(JOB_ID)
        else WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME).result.get(15, TimeUnit.SECONDS)
        Unit
    }
    companion object {
        const val TICKET = "goldy_transfer_ticket"
        const val JOB_ID = ApkUpdateProfile.JOB_ID
        const val WORK_NAME = ApkUpdateProfile.WORK_NAME
        @RequiresApi(34) private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java).forNamespace(ApkUpdateProfile.JOB_NAMESPACE)
    }
}

internal object UpdateTransferNotifications {
    const val ID = ApkUpdateProfile.NOTIFICATION_ID
    private const val CHANNEL = ApkUpdateProfile.CHANNEL
    fun create(context: Context, ticket: String, state: UpdateDownloadState): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.goldy_transfer_channel), NotificationManager.IMPORTANCE_LOW))
        val open = Intent(context, UpdateActivity::class.java).setAction(ApkUpdateProfile.ACTION_VIEW_TRANSFER)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val cancel = Intent(context, UpdateTransferCancelReceiver::class.java)
            .setData(Uri.parse("glyph-apk-update://cancel/$ticket")).putExtra(AndroidUpdateTransferExecution.TICKET, ticket)
        val progress = (state.transfer as? UpdateTransferState.Downloading)?.progress
            ?: state.retainedProgress.takeIf { state.waiting }
        val locale = context.resources.configuration.locales[0]
        val body = when {
            progress != null -> DownloadProgressFormatter.format(progress, locale)
            state.transfer == UpdateTransferState.Ready -> context.getString(R.string.goldy_update_ready)
            state.transfer == UpdateTransferState.PermissionRequired -> context.getString(R.string.goldy_transfer_permission_ready)
            state.waiting -> context.getString(R.string.goldy_transfer_paused)
            state.transfer == UpdateTransferState.Verifying -> context.getString(R.string.goldy_update_verifying)
            state.transfer == UpdateTransferState.PreparingInstall -> context.getString(R.string.goldy_update_preparing)
            else -> state.message ?: context.getString(R.string.goldy_transfer_queued)
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.goldy_update)
            .setContentTitle(context.getString(R.string.goldy_transfer_title)).setContentText(body)
            .setSubText(state.message)
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS).setOngoing(state.busy || state.restoring)
            .apply {
                if (state.busy) {
                    val fraction = progress?.let(DownloadProgressFormatter::fraction)
                    setProgress(1000, ((fraction ?: 0f) * 1000).toInt(), fraction == null)
                    addAction(Notification.Action.Builder(null, context.getString(R.string.goldy_update_cancel_download),
                        PendingIntent.getBroadcast(context, 0, cancel, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
                }
            }.build()
    }
    fun update(context: Context, ticket: String, state: UpdateDownloadState) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try { context.getSystemService(NotificationManager::class.java).notify(ID, create(context, ticket, state)) }
        catch (_: SecurityException) { /* Notification permission is user-controlled; task-manager visibility remains. */ }
    }
}

class UpdateTransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val ticket = params.extras.getString(AndroidUpdateTransferExecution.TICKET) ?: return false
        val runtime = UpdateTransferRuntime.get(application)
        try { setNotification(params, UpdateTransferNotifications.ID, UpdateTransferNotifications.create(this, ticket, runtime.state.value), JOB_END_NOTIFICATION_POLICY_DETACH) }
        catch (_: Exception) { runtime.executionFailed(ticket); return false }
        jobs[params.jobId] = scope.launch {
            val observer = launch {
                var last = 0L
                runtime.state.collect { state ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now-last >= 750 || !state.busy) { UpdateTransferNotifications.update(this@UpdateTransferJobService, ticket, state); last = now }
                }
            }
            try { runtime.runScheduledTransfer(ticket) }
            finally { observer.cancel(); if (jobs[params.jobId] === currentCoroutineContext().job) jobs.remove(params.jobId) }
            UpdateTransferNotifications.update(this@UpdateTransferJobService, ticket, runtime.state.value)
            jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        return UpdateTransferRuntime.get(application).hasScheduledTicket(params.extras.getString(AndroidUpdateTransferExecution.TICKET))
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

class UpdateTransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private fun foreground(ticket: String, state: UpdateDownloadState): ForegroundInfo {
        val notification = UpdateTransferNotifications.create(applicationContext, ticket, state)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(UpdateTransferNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(UpdateTransferNotifications.ID, notification)
    }
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val ticket = requireNotNull(inputData.getString(AndroidUpdateTransferExecution.TICKET))
        return foreground(ticket, UpdateTransferRuntime.get(applicationContext).state.value)
    }
    override suspend fun doWork(): Result = coroutineScope {
        val ticket = inputData.getString(AndroidUpdateTransferExecution.TICKET) ?: return@coroutineScope Result.failure()
        val runtime = UpdateTransferRuntime.get(applicationContext)
        runtime.awaitInitialization()
        if (!runtime.hasScheduledTicket(ticket)) return@coroutineScope Result.success()
        try { setForeground(foreground(ticket, runtime.state.value)) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { runtime.executionFailed(ticket); return@coroutineScope Result.failure() }
        val observer = launch {
            var last = 0L
            runtime.state.collect { state ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (now-last >= 750) { setForeground(foreground(ticket, state)); last = now }
            }
        }
        try { runtime.runScheduledTransfer(ticket); Result.success() }
        finally { observer.cancel() }
    }
}

class UpdateTransferCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ticket = intent.getStringExtra(AndroidUpdateTransferExecution.TICKET) ?: return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try { UpdateTransferRuntime.get(context).cancel(ticket) }
            finally { result.finish() }
        }
    }
}
