package jp.linkserver.beastlocator

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Owns distance monitoring while an app Activity other than [MainActivity] is visible.
 * MainActivity keeps its UI-oriented location session; this monitor fills the otherwise
 * unmonitored Settings/About/Update window without starting a foreground service.
 */
object ForegroundAppLocationMonitor {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sampleGate = LocationSampleGate()
    private var appContext: Context? = null
    private var activeCallback: LocationCallback? = null
    private var operationGeneration = 0L
    private var requestStartTimeout: Runnable? = null
    private var lastWidgetRefreshElapsedRealtime = 0L
    private var lastWidgetDistanceMeters: Float? = null

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        val applicationContext = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { start(applicationContext) }
            return
        }
        if (activeCallback != null || !canMonitor(applicationContext)) return

        val store = DestinationStore(applicationContext)
        if (store.isDestinationAnswered() || store.isDebugDistanceOverrideEnabled()) return
        val destination = store.getDestination()
        if (!destination.isValidCoordinate()) return

        appContext = applicationContext
        sampleGate.reset(store.getLastKnownLocationSample())
        val generation = ++operationGeneration
        val client = LocationServices.getFusedLocationProviderClient(applicationContext)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (generation != operationGeneration || activeCallback !== this) return
                for (location in result.locations.sortedBy { it.time }) {
                    if (generation != operationGeneration || activeCallback !== this) break
                    val sample = LocationSampleFactory.fromAndroidLocation(
                        location,
                        LocationSampleSource.CONTINUOUS,
                    ) ?: continue
                    if (!sampleGate.accept(sample)) continue
                    processSample(applicationContext, sample)
                }
            }
        }
        activeCallback = callback

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_INTERVAL_MILLIS,
        )
            .setMinUpdateIntervalMillis(MIN_LOCATION_INTERVAL_MILLIS)
            .setMaxUpdateAgeMillis(0L)
            .build()

        val handled = AtomicBoolean(false)
        val timeout = Runnable {
            if (!handled.compareAndSet(false, true)) return@Runnable
            if (generation == operationGeneration && activeCallback === callback) {
                AppDiagnostics.warn("foreground_monitor_request_timeout")
                stopInternal()
            }
        }
        requestStartTimeout = timeout
        mainHandler.postDelayed(timeout, LOCATION_OPERATION_TIMEOUT_MILLIS)

        val task = runCatching {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }.getOrElse {
            handled.set(true)
            mainHandler.removeCallbacks(timeout)
            requestStartTimeout = null
            AppDiagnostics.warn("foreground_monitor_request_threw", error = it)
            stopInternal()
            return
        }
        task.addOnCompleteListener { completedTask ->
            if (handled.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeout)
                if (requestStartTimeout === timeout) requestStartTimeout = null
            }
            if (generation != operationGeneration || activeCallback !== callback) {
                removeUpdatesSafely(applicationContext, callback, "stale_request_completion")
                return@addOnCompleteListener
            }
            if (!completedTask.isSuccessful) {
                AppDiagnostics.warn(
                    "foreground_monitor_request_failed",
                    error = completedTask.exception,
                )
                stopInternal()
            }
        }
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::stop)
            return
        }
        stopInternal()
    }

    private fun stopInternal() {
        operationGeneration += 1L
        requestStartTimeout?.let(mainHandler::removeCallbacks)
        requestStartTimeout = null
        val context = appContext
        val callback = activeCallback
        activeCallback = null
        appContext = null
        sampleGate.reset()
        if (context != null && callback != null) {
            removeUpdatesSafely(context, callback, "monitor_stopped")
        }
    }

    private fun processSample(context: Context, sample: LocationSample) {
        val store = DestinationStore(context)
        if (store.isDestinationAnswered() || store.isDebugDistanceOverrideEnabled()) {
            stopInternal()
            return
        }
        val destination = store.getDestination()
        if (!destination.isValidCoordinate()) return

        store.setLastKnownLocation(sample)
        val distanceMeters = runCatching {
            GeoUtils.distanceMeters(sample.position, destination)
        }.getOrNull()?.takeIf { it.isFinite() } ?: return

        val arrived = ArrivalCoordinator.observeLocation(
            context = context,
            store = store,
            sample = sample,
            destination = destination,
            distanceMeters = distanceMeters,
        )
        if (arrived || store.isDestinationAnswered()) {
            ApproachProgressController.clear(context, store)
            refreshWidgets(context, distanceMeters, force = true)
            stopInternal()
            return
        }

        ApproachProgressController.update(context, store, distanceMeters)
        DistanceEventProcessor.process(context, store, distanceMeters)
        refreshWidgets(context, distanceMeters)
    }

    private fun refreshWidgets(
        context: Context,
        distanceMeters: Float,
        force: Boolean = false,
    ) {
        val now = SystemClock.elapsedRealtime()
        val previousDistance = lastWidgetDistanceMeters
        val changedEnough = previousDistance == null ||
            abs(distanceMeters - previousDistance) >= MIN_WIDGET_DISTANCE_DELTA_METERS
        val elapsed = now - lastWidgetRefreshElapsedRealtime
        if (!force && elapsed < MIN_WIDGET_REFRESH_INTERVAL_MILLIS) return
        if (!force && !changedEnough && elapsed < MAX_IDLE_WIDGET_REFRESH_INTERVAL_MILLIS) return
        DestinationWidgetProvider.refreshAllWidgets(context)
        lastWidgetRefreshElapsedRealtime = now
        lastWidgetDistanceMeters = distanceMeters
    }

    private fun canMonitor(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED &&
            BackgroundLocationUpdater.isGoogleLocationAvailable(context)

    @SuppressLint("MissingPermission")
    private fun removeUpdatesSafely(
        context: Context,
        callback: LocationCallback,
        reason: String,
        attempt: Int = 0,
    ) {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val handled = AtomicBoolean(false)
        val timeout = Runnable {
            if (!handled.compareAndSet(false, true)) return@Runnable
            retryRemoval(context, callback, reason, attempt)
        }
        mainHandler.postDelayed(timeout, LOCATION_OPERATION_TIMEOUT_MILLIS)
        val task = runCatching { client.removeLocationUpdates(callback) }.getOrElse {
            mainHandler.removeCallbacks(timeout)
            handled.set(true)
            AppDiagnostics.warn("foreground_monitor_remove_threw", "reason=$reason", it)
            retryRemoval(context, callback, reason, attempt)
            return
        }
        task.addOnCompleteListener { completedTask ->
            if (!handled.compareAndSet(false, true)) return@addOnCompleteListener
            mainHandler.removeCallbacks(timeout)
            if (!completedTask.isSuccessful) {
                AppDiagnostics.warn(
                    "foreground_monitor_remove_failed",
                    "reason=$reason",
                    completedTask.exception,
                )
                retryRemoval(context, callback, reason, attempt)
            }
        }
    }

    private fun retryRemoval(
        context: Context,
        callback: LocationCallback,
        reason: String,
        attempt: Int,
    ) {
        val delay = LOCATION_REMOVAL_RETRY_DELAYS_MILLIS.getOrNull(attempt) ?: return
        mainHandler.postDelayed(
            { removeUpdatesSafely(context, callback, reason, attempt + 1) },
            delay,
        )
    }

    private const val LOCATION_INTERVAL_MILLIS = 4_000L
    private const val MIN_LOCATION_INTERVAL_MILLIS = 2_000L
    private const val LOCATION_OPERATION_TIMEOUT_MILLIS = 8_000L
    private const val MIN_WIDGET_REFRESH_INTERVAL_MILLIS = 10_000L
    private const val MAX_IDLE_WIDGET_REFRESH_INTERVAL_MILLIS = 60_000L
    private const val MIN_WIDGET_DISTANCE_DELTA_METERS = 10f
    private val LOCATION_REMOVAL_RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 3_000L)
}

private fun Float.isFinite(): Boolean = !isNaN() && !isInfinite()
