package jp.linkserver.beastlocator

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.common.ConnectionResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeofenceRecoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    private data class DestinationSnapshot(
        val generation: Long,
        val destination: Destination,
        val shouldRegister: Boolean
    )

    override fun doWork(): Result {
        if (runAttemptCount >= MAX_PERSISTED_ATTEMPTS) {
            AppDiagnostics.warn(
                "geofence_persisted_retry_exhausted",
                "attempt=$runAttemptCount"
            )
            return Result.failure()
        }

        val store = DestinationStore(applicationContext)
        val snapshot = readSnapshot(store)
        if (!snapshot.shouldRegister) {
            val succeeded = awaitGeofenceOperation { completion ->
                GeofenceHelper.clearDestinationGeofenceIfInactive(
                    applicationContext,
                    snapshot.generation,
                    completion
                )
            }
            return resultAfterOperation(snapshot, succeeded)
        }
        if (!BackgroundLocationUpdater.hasRequiredLocationPermissions(applicationContext)) {
            // Permission changes require user action; Activity startup synchronizes afterward.
            return Result.success()
        }
        val availability = BackgroundLocationUpdater.googleLocationAvailability(applicationContext)
        if (availability != ConnectionResult.SUCCESS) {
            return if (availability == ConnectionResult.SERVICE_UPDATING ||
                availability == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED
            ) {
                Result.retry()
            } else {
                Result.success()
            }
        }

        val succeeded = awaitGeofenceOperation { completion ->
            GeofenceHelper.registerDestinationGeofenceIfCurrent(
                applicationContext,
                snapshot.destination,
                snapshot.generation,
                completion
            )
        }
        return resultAfterOperation(snapshot, succeeded)
    }

    private fun awaitGeofenceOperation(
        start: (((Boolean) -> Unit) -> Unit)
    ): Boolean {
        val completed = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        start { result ->
            succeeded.set(result)
            completed.countDown()
        }

        val finished = try {
            completed.await(WORKER_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        return finished && succeeded.get()
    }

    private fun resultAfterOperation(
        snapshot: DestinationSnapshot,
        operationSucceeded: Boolean
    ): Result {
        if (!snapshot.matches(DestinationStore(applicationContext))) {
            AppDiagnostics.info(
                "geofence_worker_state_changed",
                "generation=${snapshot.generation}, register=${snapshot.shouldRegister}"
            )
            reconcileCurrentState()
            return Result.retry()
        }
        return if (operationSucceeded) Result.success() else Result.retry()
    }

    private fun reconcileCurrentState() {
        val current = readSnapshot(DestinationStore(applicationContext))
        if (current.shouldRegister) {
            if (GeofenceHelper.canRegisterDestinationGeofence(applicationContext)) {
                GeofenceHelper.registerDestinationGeofenceIfCurrent(
                    applicationContext,
                    current.destination,
                    current.generation
                ) { }
            }
        } else {
            GeofenceHelper.clearDestinationGeofenceIfInactive(
                applicationContext,
                current.generation
            ) { }
        }
    }

    private fun readSnapshot(store: DestinationStore): DestinationSnapshot = DestinationSnapshot(
        generation = store.getDestinationGeneration(),
        destination = store.getDestination(),
        shouldRegister = !store.isDestinationAnswered() &&
            !store.isDebugDistanceOverrideEnabled()
    )

    private fun DestinationSnapshot.matches(store: DestinationStore): Boolean {
        val current = readSnapshot(store)
        return current == this
    }

    companion object {
        private const val MAX_PERSISTED_ATTEMPTS = 5
        private const val WORKER_WAIT_TIMEOUT_SECONDS = 60L
    }
}

object GeofenceRecoveryScheduler {
    private const val UNIQUE_WORK_NAME = "destination_geofence_recovery"
    private const val EVENT_RECOVERY_WORK_NAME = "destination_geofence_event_recovery"
    private const val DEFAULT_DELAY_MILLIS = 15_000L

    fun enqueue(
        context: Context,
        delayMillis: Long = DEFAULT_DELAY_MILLIS,
        completion: ((Boolean) -> Unit)? = null
    ) {
        enqueueInternal(
            context,
            delayMillis,
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            completion
        )
    }

    fun enqueueEventRecovery(
        context: Context,
        delayMillis: Long = DEFAULT_DELAY_MILLIS,
        completion: ((Boolean) -> Unit)? = null
    ) {
        enqueueInternal(
            context,
            delayMillis,
            EVENT_RECOVERY_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            completion
        )
    }

    private fun enqueueInternal(
        context: Context,
        delayMillis: Long,
        uniqueWorkName: String,
        policy: ExistingWorkPolicy,
        completion: ((Boolean) -> Unit)?
    ) {
        val appContext = context.applicationContext
        runCatching {
            val request = OneTimeWorkRequest.Builder(GeofenceRecoveryWorker::class.java)
                .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()
            val operation = WorkManager.getInstance(appContext).enqueueUniqueWork(
                uniqueWorkName,
                policy,
                request
            )
            if (completion != null) {
                operation.result.addListener(
                    {
                        val succeeded = runCatching {
                            operation.result.get()
                            true
                        }.onFailure {
                            AppDiagnostics.warn(
                                "geofence_recovery_enqueue_operation_failed",
                                error = it
                            )
                        }.getOrDefault(false)
                        runCatching { completion(succeeded) }
                            .onFailure {
                                AppDiagnostics.warn(
                                    "geofence_recovery_enqueue_callback_failed",
                                    error = it
                                )
                            }
                    },
                    ContextCompat.getMainExecutor(appContext)
                )
            }
        }.onFailure {
            AppDiagnostics.warn("geofence_recovery_enqueue_failed", error = it)
            runCatching { completion?.invoke(false) }
                .onFailure { callbackError ->
                    AppDiagnostics.warn(
                        "geofence_recovery_enqueue_callback_failed",
                        error = callbackError
                    )
                }
        }
    }
}
