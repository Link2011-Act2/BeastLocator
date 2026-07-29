package jp.linkserver.beastlocator

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import java.util.concurrent.TimeoutException

object GeofenceHelper {
    const val ACTION_GEOFENCE = "jp.linkserver.beastlocator.ACTION_GEOFENCE_EVENT"
    const val GEOFENCE_RADIUS_METERS = 150f

    private const val GEOFENCE_ID_PREFIX = "destination_geofence:"
    private const val CLEAR_RETRY_KEY = "clear"
    private const val OPERATION_TIMEOUT_MILLIS = 8_000L
    internal const val RETRY_EXHAUSTION_COOLDOWN_MILLIS = 60_000L
    private val RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 3_000L, 7_000L)

    private data class RegistrationTarget(
        val destination: Destination,
        val generation: Long
    ) {
        val retryKey: String = requestIdForGeneration(generation)
    }

    private enum class OperationKind {
        REMOVE,
        ADD
    }

    private data class Operation(
        val token: Long,
        val epoch: Long,
        val kind: OperationKind,
        val target: RegistrationTarget?
    )

    private data class CompletionWaiter(
        val target: RegistrationTarget?,
        val callback: (Boolean) -> Unit
    )

    private sealed interface StartAction {
        data class Remove(val operation: Operation) : StartAction
        data class Add(
            val operation: Operation,
            val target: RegistrationTarget
        ) : StartAction
    }

    private val registrationLock = Any()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private var desiredTarget: RegistrationTarget? = null
    private var installedTarget: RegistrationTarget? = null
    private var mustClearSystemRegistration = true
    private var currentOperation: Operation? = null
    private var operationTimeoutRunnable: Runnable? = null
    private var retryRunnable: Runnable? = null
    private var retryAttempt = 0
    private var retryAttemptKey: String? = null
    private var exhaustedRetryKey: String? = null
    private var retryExhaustedAtElapsedRealtime = 0L
    private var nextOperationToken = 0L
    private var stateEpoch = 0L
    private var retryAfterInvalidatedOperation = false
    private val completionWaiters = mutableListOf<CompletionWaiter>()

    private fun geofencingClient(context: Context): GeofencingClient =
        LocationServices.getGeofencingClient(context)

    private fun geofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = ACTION_GEOFENCE
        }
        val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(
            context,
            10,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag
        )
    }

    internal fun requestIdForGeneration(generation: Long): String =
        "$GEOFENCE_ID_PREFIX$generation"

    internal fun generationFromRequestId(requestId: String): Long? {
        if (!requestId.startsWith(GEOFENCE_ID_PREFIX)) return null
        return requestId.substring(GEOFENCE_ID_PREFIX.length)
            .toLongOrNull()
            ?.takeIf { it >= 0L }
    }

    internal fun retryDelayMillis(attempt: Int): Long? =
        RETRY_DELAYS_MILLIS.getOrNull(attempt)

    internal fun retryCooldownElapsed(
        exhaustedAtElapsedRealtime: Long,
        nowElapsedRealtime: Long
    ): Boolean = exhaustedAtElapsedRealtime > 0L &&
        nowElapsedRealtime >= exhaustedAtElapsedRealtime &&
        nowElapsedRealtime - exhaustedAtElapsedRealtime >= RETRY_EXHAUSTION_COOLDOWN_MILLIS

    fun canRegisterDestinationGeofence(context: Context): Boolean {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBackgroundLocation = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBackgroundLocation) return false
        }

        return BackgroundLocationUpdater.isGoogleLocationAvailable(context)
    }

    private fun registrationSnapshotMatches(
        store: DestinationStore,
        destination: Destination,
        expectedGeneration: Long
    ): Boolean = store.getDestinationGeneration() == expectedGeneration &&
        store.getDestination() == destination &&
        !store.isDestinationAnswered() &&
        !store.isDebugDistanceOverrideEnabled()

    private fun inactiveSnapshotMatches(
        store: DestinationStore,
        expectedGeneration: Long
    ): Boolean = store.getDestinationGeneration() == expectedGeneration &&
        (store.isDestinationAnswered() || store.isDebugDistanceOverrideEnabled())

    fun registerDestinationGeofence(
        context: Context,
        destination: Destination,
        completion: ((Boolean) -> Unit)? = null
    ) {
        registerDestinationGeofenceInternal(
            context = context,
            destination = destination,
            expectedGeneration = null,
            completion = completion
        )
    }

    internal fun registerDestinationGeofenceIfCurrent(
        context: Context,
        destination: Destination,
        expectedGeneration: Long,
        completion: (Boolean) -> Unit
    ) {
        registerDestinationGeofenceInternal(
            context = context,
            destination = destination,
            expectedGeneration = expectedGeneration,
            completion = completion
        )
    }

    @SuppressLint("MissingPermission")
    private fun registerDestinationGeofenceInternal(
        context: Context,
        destination: Destination,
        expectedGeneration: Long?,
        completion: ((Boolean) -> Unit)?
    ) {
        val appContext = context.applicationContext
        val store = DestinationStore(appContext)
        if (expectedGeneration != null &&
            !registrationSnapshotMatches(store, destination, expectedGeneration)
        ) {
            completion?.invoke(false)
            return
        }
        if (store.isDebugDistanceOverrideEnabled()) {
            clearDestinationGeofence(appContext)
            completion?.invoke(false)
            return
        }
        if (!destination.isValidCoordinate() || !canRegisterDestinationGeofence(appContext)) {
            if (completion != null) {
                AppDiagnostics.warn("geofence_registration_prerequisite_missing")
                completion(false)
            }
            return
        }

        val target = RegistrationTarget(
            destination = destination,
            generation = store.getDestinationGeneration()
        )
        var immediateResult: Boolean? = null
        var rejectedStaleSnapshot = false
        var cancelledCallbacks: List<(Boolean) -> Unit> = emptyList()
        synchronized(registrationLock) {
            if (expectedGeneration != null &&
                !registrationSnapshotMatches(store, destination, expectedGeneration)
            ) {
                rejectedStaleSnapshot = true
            } else {
                if (desiredTarget != target) {
                    cancelledCallbacks = takeWaitersExceptLocked(target)
                    desiredTarget = target
                    cancelRetryLocked()
                    resetRetryStateLocked()
                    if (installedTarget != null && installedTarget != target) {
                        mustClearSystemRegistration = true
                    }
                }

                if (exhaustedRetryKey == target.retryKey &&
                    retryCooldownElapsed(
                        retryExhaustedAtElapsedRealtime,
                        SystemClock.elapsedRealtime()
                    )
                ) {
                    AppDiagnostics.info("geofence_retry_cooldown_elapsed", target.retryKey)
                    resetRetryStateLocked()
                }

                when {
                    installedTarget == target &&
                        !mustClearSystemRegistration &&
                        currentOperation == null -> immediateResult = true
                    exhaustedRetryKey == target.retryKey -> immediateResult = false
                    completion != null -> completionWaiters += CompletionWaiter(target, completion)
                }
            }
        }
        if (rejectedStaleSnapshot) {
            completion?.invoke(false)
            return
        }
        cancelledCallbacks.forEach { it(false) }
        immediateResult?.let {
            completion?.invoke(it)
            return
        }
        reconcile(appContext)
    }

    fun clearDestinationGeofence(
        context: Context,
        completion: ((Boolean) -> Unit)? = null
    ) {
        clearDestinationGeofenceInternal(
            context = context,
            expectedInactiveGeneration = null,
            completion = completion
        )
    }

    internal fun clearDestinationGeofenceIfInactive(
        context: Context,
        expectedGeneration: Long,
        completion: (Boolean) -> Unit
    ) {
        clearDestinationGeofenceInternal(
            context = context,
            expectedInactiveGeneration = expectedGeneration,
            completion = completion
        )
    }

    private fun clearDestinationGeofenceInternal(
        context: Context,
        expectedInactiveGeneration: Long?,
        completion: ((Boolean) -> Unit)?
    ) {
        val appContext = context.applicationContext
        val store = DestinationStore(appContext)
        if (expectedInactiveGeneration != null &&
            !inactiveSnapshotMatches(store, expectedInactiveGeneration)
        ) {
            completion?.invoke(false)
            return
        }

        var cancelledCallbacks: List<(Boolean) -> Unit> = emptyList()
        var immediateResult: Boolean? = null
        var rejectedStaleSnapshot = false
        synchronized(registrationLock) {
            if (expectedInactiveGeneration != null &&
                !inactiveSnapshotMatches(store, expectedInactiveGeneration)
            ) {
                rejectedStaleSnapshot = true
            } else {
                store.clearRegisteredGeofenceDestination()
                if (desiredTarget != null) {
                    desiredTarget = null
                    cancelledCallbacks = takeWaitersExceptLocked(null)
                    cancelRetryLocked()
                    resetRetryStateLocked()
                }
                if (exhaustedRetryKey == CLEAR_RETRY_KEY &&
                    retryCooldownElapsed(
                        retryExhaustedAtElapsedRealtime,
                        SystemClock.elapsedRealtime()
                    )
                ) {
                    AppDiagnostics.info("geofence_clear_retry_cooldown_elapsed")
                    resetRetryStateLocked()
                }
                if (installedTarget != null || currentOperation?.kind == OperationKind.ADD) {
                    mustClearSystemRegistration = true
                }
                when {
                    installedTarget == null &&
                        !mustClearSystemRegistration &&
                        currentOperation == null -> immediateResult = true
                    exhaustedRetryKey == CLEAR_RETRY_KEY -> immediateResult = false
                    completion != null -> completionWaiters += CompletionWaiter(null, completion)
                }
            }
        }
        if (rejectedStaleSnapshot) {
            completion?.invoke(false)
            return
        }
        cancelledCallbacks.forEach { it(false) }
        immediateResult?.let {
            completion?.invoke(it)
            return
        }
        reconcile(appContext)
    }

    /**
     * Called after boot/package replacement because an in-memory or persisted value cannot prove
     * that Google Play services still owns the geofence.
     */
    fun invalidateSystemRegistration(context: Context) {
        val appContext = context.applicationContext
        DestinationStore(appContext).clearRegisteredGeofenceDestination()
        var cancelledCallbacks: List<(Boolean) -> Unit>
        synchronized(registrationLock) {
            stateEpoch += 1L
            desiredTarget = null
            installedTarget = null
            mustClearSystemRegistration = true
            retryAfterInvalidatedOperation = false
            cancelledCallbacks = takeWaitersExceptLocked(null)
            cancelRetryLocked()
            resetRetryStateLocked()
        }
        cancelledCallbacks.forEach { it(false) }
        reconcile(appContext)
    }

    fun handleGeofenceError(
        context: Context,
        errorCode: Int,
        onRecoveryEnqueued: ((Boolean) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val store = DestinationStore(appContext)
        store.clearRegisteredGeofenceDestination()
        AppDiagnostics.warn("geofence_event_error", "status=$errorCode")
        if (store.isDebugDistanceOverrideEnabled()) {
            clearDestinationGeofence(appContext)
            onRecoveryEnqueued?.invoke(true)
            return
        }
        val recoveryTarget = if (!store.isDestinationAnswered() &&
            canRegisterDestinationGeofence(appContext)
        ) {
            RegistrationTarget(store.getDestination(), store.getDestinationGeneration())
        } else {
            null
        }

        var cancelledCallbacks: List<(Boolean) -> Unit>
        synchronized(registrationLock) {
            stateEpoch += 1L
            installedTarget = null
            mustClearSystemRegistration = true
            if (desiredTarget != recoveryTarget) {
                cancelRetryLocked()
                resetRetryStateLocked()
            }
            cancelledCallbacks = takeWaitersExceptLocked(recoveryTarget)
            desiredTarget = recoveryTarget
            retryAfterInvalidatedOperation = currentOperation != null
        }
        cancelledCallbacks.forEach { it(false) }
        GeofenceRecoveryScheduler.enqueueEventRecovery(
            appContext,
            completion = onRecoveryEnqueued
        )
        if (currentOperation == null) scheduleRetry(appContext)
    }

    private fun reconcile(context: Context) {
        var completedCallbacks: List<(Boolean) -> Unit> = emptyList()
        var callbackResult = true
        val action = synchronized(registrationLock) {
            if (currentOperation != null || retryRunnable != null) return@synchronized null

            val retryKey = retryKeyLocked()
            if (exhaustedRetryKey == retryKey) {
                completedCallbacks = takeWaitersForTargetLocked(desiredTarget)
                callbackResult = false
                return@synchronized null
            }

            when {
                mustClearSystemRegistration ||
                    (installedTarget != null && installedTarget != desiredTarget) -> {
                    val operation = newOperationLocked(OperationKind.REMOVE, null)
                    StartAction.Remove(operation)
                }
                desiredTarget != null && installedTarget != desiredTarget -> {
                    val target = checkNotNull(desiredTarget)
                    val operation = newOperationLocked(OperationKind.ADD, target)
                    StartAction.Add(operation, target)
                }
                desiredTarget != null && installedTarget == desiredTarget -> {
                    completedCallbacks = takeWaitersForTargetLocked(desiredTarget)
                    null
                }
                desiredTarget == null && installedTarget == null -> {
                    completedCallbacks = takeWaitersForTargetLocked(null)
                    null
                }
                else -> null
            }
        }

        completedCallbacks.forEach { it(callbackResult) }
        when (action) {
            is StartAction.Remove -> startRemoval(context, action.operation)
            is StartAction.Add -> startRegistration(context, action.operation, action.target)
            null -> Unit
        }
    }

    private fun newOperationLocked(
        kind: OperationKind,
        target: RegistrationTarget?
    ): Operation {
        val operation = Operation(
            token = ++nextOperationToken,
            epoch = stateEpoch,
            kind = kind,
            target = target
        )
        currentOperation = operation
        return operation
    }

    @SuppressLint("MissingPermission")
    private fun startRegistration(
        context: Context,
        operation: Operation,
        target: RegistrationTarget
    ) {
        armOperationTimeout(context, operation)
        val task = runCatching {
            val geofence = Geofence.Builder()
                .setRequestId(requestIdForGeneration(target.generation))
                .setCircularRegion(
                    target.destination.lat,
                    target.destination.lng,
                    GEOFENCE_RADIUS_METERS
                )
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
                )
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build()

            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(geofence)
                .build()
            geofencingClient(context).addGeofences(request, geofencePendingIntent(context))
        }.getOrElse {
            completeOperation(context, operation, succeeded = false, error = it)
            return
        }

        task.addOnCompleteListener {
            completeOperation(
                context,
                operation,
                succeeded = it.isSuccessful,
                error = it.exception
            )
        }
    }

    private fun startRemoval(context: Context, operation: Operation) {
        armOperationTimeout(context, operation)
        val task = runCatching {
            geofencingClient(context).removeGeofences(geofencePendingIntent(context))
        }.getOrElse {
            completeOperation(context, operation, succeeded = false, error = it)
            return
        }
        task.addOnCompleteListener {
            completeOperation(
                context,
                operation,
                succeeded = it.isSuccessful,
                error = it.exception
            )
        }
    }

    private fun armOperationTimeout(context: Context, operation: Operation) {
        val timeout = Runnable {
            completeOperation(
                context,
                operation,
                succeeded = false,
                error = TimeoutException("Geofence ${operation.kind} timed out")
            )
        }
        synchronized(registrationLock) {
            if (currentOperation?.token != operation.token) return
            operationTimeoutRunnable?.let(mainHandler::removeCallbacks)
            operationTimeoutRunnable = timeout
        }
        mainHandler.postDelayed(timeout, OPERATION_TIMEOUT_MILLIS)
    }

    private fun completeOperation(
        context: Context,
        operation: Operation,
        succeeded: Boolean,
        error: Throwable?
    ) {
        var successfulCallbacks: List<(Boolean) -> Unit> = emptyList()
        var shouldRetry = false
        var shouldReconcile = false
        var cacheTarget: Destination? = null
        var clearCache = false
        var ignoredStaleEpoch = false

        synchronized(registrationLock) {
            if (currentOperation?.token != operation.token) return
            currentOperation = null
            operationTimeoutRunnable?.let(mainHandler::removeCallbacks)
            operationTimeoutRunnable = null

            if (operation.epoch != stateEpoch) {
                installedTarget = null
                mustClearSystemRegistration = true
                ignoredStaleEpoch = true
                if (retryAfterInvalidatedOperation) {
                    retryAfterInvalidatedOperation = false
                    shouldRetry = true
                } else {
                    shouldReconcile = true
                }
            } else if (succeeded) {
                when (operation.kind) {
                    OperationKind.REMOVE -> {
                        installedTarget = null
                        mustClearSystemRegistration = false
                        clearCache = true
                        if (desiredTarget == null) {
                            successfulCallbacks = takeWaitersForTargetLocked(null)
                            resetRetryStateLocked()
                        }
                        shouldReconcile = true
                    }
                    OperationKind.ADD -> {
                        installedTarget = operation.target
                        mustClearSystemRegistration = operation.target != desiredTarget
                        if (operation.target == desiredTarget) {
                            cacheTarget = operation.target?.destination
                            successfulCallbacks = takeWaitersForTargetLocked(operation.target)
                            resetRetryStateLocked()
                        }
                        shouldReconcile = operation.target != desiredTarget
                    }
                }
            } else {
                installedTarget = null
                mustClearSystemRegistration = true
                clearCache = true
                shouldRetry = true
            }
        }

        val store = DestinationStore(context)
        if (clearCache) store.clearRegisteredGeofenceDestination()
        cacheTarget?.let(store::setRegisteredGeofenceDestination)
        successfulCallbacks.forEach { it(true) }

        if (ignoredStaleEpoch) {
            AppDiagnostics.info("geofence_operation_invalidated", operation.kind.name)
        } else if (!succeeded) {
            val statusCode = (error as? ApiException)?.statusCode
            val detail = buildString {
                append("operation=")
                append(operation.kind.name)
                statusCode?.let {
                    append(", status=")
                    append(it)
                }
            }
            AppDiagnostics.warn("geofence_operation_failed", detail, error)
        }

        when {
            shouldRetry -> {
                GeofenceRecoveryScheduler.enqueue(context)
                scheduleRetry(context)
            }
            shouldReconcile -> reconcile(context)
        }
    }

    private fun scheduleRetry(context: Context) {
        var failedCallbacks: List<(Boolean) -> Unit> = emptyList()
        var scheduledDelay: Long? = null
        synchronized(registrationLock) {
            if (currentOperation != null || retryRunnable != null) return
            val key = retryKeyLocked()
            if (retryAttemptKey != key) {
                retryAttemptKey = key
                retryAttempt = 0
                exhaustedRetryKey = null
            }

            val delay = retryDelayMillis(retryAttempt)
            if (delay == null) {
                exhaustedRetryKey = key
                retryExhaustedAtElapsedRealtime = SystemClock.elapsedRealtime()
                failedCallbacks = takeWaitersForTargetLocked(desiredTarget)
                AppDiagnostics.warn("geofence_retry_exhausted", key)
                return@synchronized
            }

            retryAttempt += 1
            lateinit var retry: Runnable
            retry = Runnable {
                val shouldContinue = synchronized(registrationLock) {
                    if (retryRunnable !== retry) {
                        false
                    } else {
                        retryRunnable = null
                        retryKeyLocked() == key
                    }
                }
                if (shouldContinue) reconcile(context)
            }
            retryRunnable = retry
            scheduledDelay = delay
            mainHandler.postDelayed(retry, delay)
        }
        failedCallbacks.forEach { it(false) }
        scheduledDelay?.let {
            AppDiagnostics.info(
                "geofence_retry_scheduled",
                "attempt=$retryAttempt, delayMs=$it"
            )
        }
    }

    private fun retryKeyLocked(): String = desiredTarget?.retryKey ?: CLEAR_RETRY_KEY

    private fun cancelRetryLocked() {
        retryRunnable?.let(mainHandler::removeCallbacks)
        retryRunnable = null
    }

    private fun resetRetryStateLocked() {
        retryAttempt = 0
        retryAttemptKey = null
        exhaustedRetryKey = null
        retryExhaustedAtElapsedRealtime = 0L
    }

    private fun takeWaitersExceptLocked(
        target: RegistrationTarget?
    ): List<(Boolean) -> Unit> {
        val removed = completionWaiters.filter { it.target != target }
        completionWaiters.removeAll(removed.toSet())
        return removed.map { it.callback }
    }

    private fun takeWaitersForTargetLocked(
        target: RegistrationTarget?
    ): List<(Boolean) -> Unit> {
        val removed = completionWaiters.filter { it.target == target }
        completionWaiters.removeAll(removed.toSet())
        return removed.map { it.callback }
    }
}
