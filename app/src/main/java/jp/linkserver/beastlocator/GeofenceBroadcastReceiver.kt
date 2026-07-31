package jp.linkserver.beastlocator

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.atomic.AtomicBoolean

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    private enum class ConfirmationMode {
        ENTER,
        EXIT
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GeofenceHelper.ACTION_GEOFENCE) return
        val appContext = context.applicationContext
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            AppDiagnostics.warn("geofence_event_missing")
            return
        }

        val store = DestinationStore(appContext)
        if (store.isDebugDistanceOverrideEnabled()) {
            GeofenceHelper.clearDestinationGeofence(appContext)
            AppDiagnostics.info("geofence_event_ignored_for_debug_distance")
            return
        }
        if (event.hasError()) {
            handleGeofenceErrorPersistently(appContext, event.errorCode)
            return
        }

        val destinationGeneration = store.getDestinationGeneration()
        val hasCurrentGeneration = event.triggeringGeofences.orEmpty().any {
            GeofenceHelper.generationFromRequestId(it.requestId) == destinationGeneration
        }
        if (!hasCurrentGeneration) {
            AppDiagnostics.info(
                "geofence_event_stale_generation",
                "expectedGeneration=$destinationGeneration"
            )
            return
        }

        val transition = event.geofenceTransition
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER &&
            transition != Geofence.GEOFENCE_TRANSITION_EXIT
        ) {
            AppDiagnostics.warn("geofence_transition_unsupported", "transition=$transition")
            return
        }

        val destination = store.getDestination()
        val triggeringSample = event.triggeringLocation?.let {
            LocationSampleFactory.fromAndroidLocation(it, LocationSampleSource.GEOFENCE)
        }?.takeIf { it.isEligibleForArrival() }

        if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            if (!store.isArrivalRearmRequired()) return
            triggeringSample?.let {
                forwardSampleIfNew(
                    appContext,
                    store,
                    it,
                    destination,
                    destinationGeneration
                )
            }
            if (store.isArrivalRearmRequired()) {
                requestFreshSamples(
                    appContext,
                    destination,
                    destinationGeneration,
                    ConfirmationMode.EXIT
                )
            }
            return
        }

        if (store.isDestinationAnswered() || store.isArrivalRearmRequired()) return
        if (triggeringSample == null) {
            AppDiagnostics.info("geofence_trigger_location_rejected")
        } else if (forwardSampleIfNew(
                appContext,
                store,
                triggeringSample,
                destination,
                destinationGeneration
            )
        ) {
            return
        }

        // ENTER at 150 m is only a best-effort wake-up signal. This bounded session samples the
        // current position; it does not try to follow the whole 150 m -> 50 m journey. Continuous
        // Activity/foreground-service monitoring remains the reliable path to the 50 m decision.
        requestFreshSamples(
            appContext,
            destination,
            destinationGeneration,
            ConfirmationMode.ENTER
        )
    }

    private fun handleGeofenceErrorPersistently(context: Context, errorCode: Int) {
        val pendingResult = goAsync()
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        lateinit var timeout: Runnable

        fun finishOnce() {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacks(timeout)
            runCatching { pendingResult.finish() }
                .onFailure {
                    AppDiagnostics.warn("geofence_error_finish_failed", error = it)
                }
        }

        timeout = Runnable {
            AppDiagnostics.warn("geofence_recovery_enqueue_timeout")
            finishOnce()
        }
        handler.postDelayed(timeout, RECEIVER_TIMEOUT_MILLIS)
        runCatching {
            GeofenceHelper.handleGeofenceError(context, errorCode) { enqueued ->
                if (!enqueued) {
                    AppDiagnostics.warn("geofence_recovery_not_persisted")
                }
                finishOnce()
            }
        }.onFailure {
            AppDiagnostics.warn("geofence_error_handling_failed", error = it)
            finishOnce()
        }
    }

    private fun forwardSampleIfNew(
        context: Context,
        store: DestinationStore,
        sample: LocationSample,
        destination: Destination,
        destinationGeneration: Long
    ): Boolean {
        if (!sample.isEligibleForArrival() ||
            store.getDestinationGeneration() != destinationGeneration ||
            store.getDestination() != destination ||
            !markSampleTimestamp(destinationGeneration, sample)
        ) {
            return false
        }
        store.setLastKnownLocation(sample)
        val distanceMeters = GeoUtils.distanceMeters(sample.position, destination)
        return ArrivalCoordinator.observeLocation(
            context,
            store,
            sample,
            destination,
            distanceMeters
        )
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshSamples(
        context: Context,
        destination: Destination,
        destinationGeneration: Long,
        mode: ConfirmationMode
    ) {
        if (!GeofenceHelper.canRegisterDestinationGeofence(context)) {
            AppDiagnostics.warn("geofence_confirmation_permission_missing")
            return
        }
        if (!confirmationInFlight.compareAndSet(false, true)) {
            AppDiagnostics.info("geofence_confirmation_already_running")
            return
        }

        val pendingResult = goAsync()
        val finished = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val client = LocationServices.getFusedLocationProviderClient(context)
        var callback: LocationCallback? = null
        lateinit var timeout: Runnable

        fun finishOnce() {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacks(timeout)
            callback?.let { locationCallback ->
                removeFreshLocationUpdatesSafely(
                    client,
                    locationCallback,
                    handler,
                    reason = "confirmation_finished"
                )
            }
            confirmationInFlight.set(false)
            runCatching { pendingResult.finish() }
                .onFailure {
                    AppDiagnostics.warn("geofence_confirmation_finish_failed", error = it)
                }
        }

        timeout = Runnable {
            AppDiagnostics.info("geofence_confirmation_timeout", "mode=${mode.name}")
            finishOnce()
        }

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (finished.get()) return
                // Do not mix boot-relative nanoseconds with epoch milliseconds: several OEMs
                // intermittently report elapsedRealtimeNanos=0 for otherwise valid fixes.
                val orderedLocations = result.locations.sortedBy { it.time }
                for (location in orderedLocations) {
                    val currentStore = DestinationStore(context)
                    if (currentStore.isDebugDistanceOverrideEnabled() ||
                        currentStore.getDestinationGeneration() != destinationGeneration ||
                        currentStore.getDestination() != destination
                    ) {
                        finishOnce()
                        return
                    }

                    val sample = LocationSampleFactory.fromAndroidLocation(
                        location,
                        LocationSampleSource.GEOFENCE
                    )?.takeIf { it.isEligibleForArrival() } ?: continue
                    val completed = forwardSampleIfNew(
                        context,
                        currentStore,
                        sample,
                        destination,
                        destinationGeneration
                    )
                    when (mode) {
                        ConfirmationMode.ENTER -> {
                            if (completed || currentStore.isDestinationAnswered()) {
                                finishOnce()
                                return
                            }
                        }
                        ConfirmationMode.EXIT -> {
                            if (!currentStore.isArrivalRearmRequired()) {
                                finishOnce()
                                return
                            }
                        }
                    }
                }
            }
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            FRESH_SAMPLE_INTERVAL_MILLIS
        )
            .setMinUpdateIntervalMillis(FRESH_SAMPLE_MIN_INTERVAL_MILLIS)
            .setMaxUpdateAgeMillis(0L)
            .setDurationMillis(FRESH_SESSION_DURATION_MILLIS)
            .setMaxUpdates(MAX_FRESH_UPDATES)
            .setWaitForAccurateLocation(true)
            .build()

        handler.postDelayed(timeout, RECEIVER_TIMEOUT_MILLIS)
        val task = runCatching {
            client.requestLocationUpdates(
                request,
                checkNotNull(callback),
                Looper.getMainLooper()
            )
        }.getOrElse {
            AppDiagnostics.warn("geofence_confirmation_start_failed", error = it)
            finishOnce()
            return
        }
        task.addOnCompleteListener { completedTask ->
            if (!completedTask.isSuccessful) {
                AppDiagnostics.warn(
                    "geofence_confirmation_failed",
                    error = completedTask.exception
                )
                finishOnce()
            } else if (finished.get()) {
                // A delayed OEM/GMS registration can complete after the receiver timeout and
                // after the first removal. Remove again to close that add-after-remove race.
                callback?.let { locationCallback ->
                    removeFreshLocationUpdatesSafely(
                        client,
                        locationCallback,
                        handler,
                        reason = "stale_request_completion"
                    )
                }
            }
        }
    }

    private fun removeFreshLocationUpdatesSafely(
        client: FusedLocationProviderClient,
        callback: LocationCallback,
        handler: Handler,
        reason: String,
        attempt: Int = 0
    ) {
        val task = runCatching {
            client.removeLocationUpdates(callback)
        }.getOrElse {
            AppDiagnostics.warn(
                "geofence_confirmation_remove_threw",
                "reason=$reason, attempt=$attempt",
                it
            )
            retryFreshLocationRemoval(client, callback, handler, reason, attempt)
            return
        }
        val handled = AtomicBoolean(false)
        lateinit var removalTimeout: Runnable
        removalTimeout = Runnable {
            if (!handled.compareAndSet(false, true)) return@Runnable
            AppDiagnostics.warn(
                "geofence_confirmation_remove_timeout",
                "reason=$reason, attempt=$attempt"
            )
            retryFreshLocationRemoval(client, callback, handler, reason, attempt)
        }
        handler.postDelayed(removalTimeout, LOCATION_REMOVAL_OPERATION_TIMEOUT_MILLIS)
        task.addOnCompleteListener { completedTask ->
            if (!handled.compareAndSet(false, true)) return@addOnCompleteListener
            handler.removeCallbacks(removalTimeout)
            if (!completedTask.isSuccessful) {
                AppDiagnostics.warn(
                    "geofence_confirmation_remove_failed",
                    "reason=$reason, attempt=$attempt",
                    completedTask.exception
                )
                retryFreshLocationRemoval(client, callback, handler, reason, attempt)
            }
        }
    }

    private fun retryFreshLocationRemoval(
        client: FusedLocationProviderClient,
        callback: LocationCallback,
        handler: Handler,
        reason: String,
        attempt: Int
    ) {
        val delay = LOCATION_REMOVAL_RETRY_DELAYS_MILLIS.getOrNull(attempt) ?: return
        handler.postDelayed(
            {
                removeFreshLocationUpdatesSafely(
                    client,
                    callback,
                    handler,
                    reason,
                    attempt + 1
                )
            },
            delay
        )
    }

    companion object {
        private const val RECEIVER_TIMEOUT_MILLIS = 8_000L
        private const val FRESH_SESSION_DURATION_MILLIS = 7_500L
        private const val FRESH_SAMPLE_INTERVAL_MILLIS = 1_000L
        private const val FRESH_SAMPLE_MIN_INTERVAL_MILLIS = 500L
        private const val MAX_FRESH_UPDATES = 6
        private const val LOCATION_REMOVAL_OPERATION_TIMEOUT_MILLIS = 8_000L
        private val LOCATION_REMOVAL_RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 3_000L)

        private val confirmationInFlight = AtomicBoolean(false)
        private val timestampLock = Any()
        private var lastForwardedGeneration = Long.MIN_VALUE
        private var lastForwardedTimestamp = Long.MIN_VALUE

        private fun markSampleTimestamp(
            destinationGeneration: Long,
            sample: LocationSample
        ): Boolean = synchronized(timestampLock) {
            if (lastForwardedGeneration != destinationGeneration) {
                lastForwardedGeneration = destinationGeneration
                lastForwardedTimestamp = Long.MIN_VALUE
            }
            val timestamp = sample.wallTimeMillis
            if (timestamp <= lastForwardedTimestamp) {
                false
            } else {
                lastForwardedTimestamp = timestamp
                true
            }
        }
    }
}
