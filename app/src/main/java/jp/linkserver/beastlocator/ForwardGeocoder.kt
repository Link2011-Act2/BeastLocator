package jp.linkserver.beastlocator

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class PlaceSearchResult(
    val label: String,
    val destination: Destination
)

sealed class PlaceSearchOutcome {
    data class Success(val places: List<PlaceSearchResult>) : PlaceSearchOutcome()
    data object Unavailable : PlaceSearchOutcome()
    data object Failed : PlaceSearchOutcome()
}

object ForwardGeocoder {
    private val executor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_QUEUED_REQUESTS),
        { runnable -> Thread(runnable, "beastlocator-place-search").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val mainHandler = Handler(Looper.getMainLooper())

    class Request internal constructor(
        private val completed: AtomicBoolean,
        private val timeoutRunnable: Runnable,
        private val callbackRef: AtomicReference<((PlaceSearchOutcome) -> Unit)?>,
        private val futureRef: AtomicReference<Future<*>?>
    ) {
        fun cancel() {
            completed.set(true)
            callbackRef.set(null)
            cancelFuture(futureRef.getAndSet(null))
            mainHandler.removeCallbacks(timeoutRunnable)
        }
    }

    fun searchAsync(
        context: Context,
        query: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onResult: (PlaceSearchOutcome) -> Unit
    ): Request {
        val appContext = context.applicationContext
        val normalizedQuery = query.trim()
        val completed = AtomicBoolean(false)
        val callbackRef = AtomicReference<((PlaceSearchOutcome) -> Unit)?>(onResult)
        val futureRef = AtomicReference<Future<*>?>(null)
        lateinit var timeoutRunnable: Runnable

        fun complete(outcome: PlaceSearchOutcome) {
            if (!completed.compareAndSet(false, true)) return
            mainHandler.removeCallbacks(timeoutRunnable)
            cancelFuture(futureRef.getAndSet(null))
            mainHandler.post {
                val callback = callbackRef.getAndSet(null) ?: return@post
                runCatching { callback(outcome) }
                    .onFailure { AppDiagnostics.warn("place_search_callback_failed", error = it) }
            }
        }

        timeoutRunnable = Runnable { complete(PlaceSearchOutcome.Failed) }
        mainHandler.postDelayed(timeoutRunnable, timeoutMillis.coerceIn(1_000L, 15_000L))

        if (normalizedQuery.isEmpty()) {
            complete(PlaceSearchOutcome.Success(emptyList()))
            return Request(completed, timeoutRunnable, callbackRef, futureRef)
        }
        if (!Geocoder.isPresent()) {
            complete(PlaceSearchOutcome.Unavailable)
            return Request(completed, timeoutRunnable, callbackRef, futureRef)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                Geocoder(appContext, Locale.getDefault()).getFromLocationName(
                    normalizedQuery,
                    MAX_RESULTS,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            complete(PlaceSearchOutcome.Success(addresses.toSearchResults()))
                        }

                        override fun onError(errorMessage: String?) {
                            complete(PlaceSearchOutcome.Failed)
                        }
                    }
                )
            }.onFailure {
                AppDiagnostics.warn("place_search_start_failed", error = it)
                complete(PlaceSearchOutcome.Failed)
            }
        } else {
            val future = runCatching {
                executor.submit {
                    val outcome = try {
                        @Suppress("DEPRECATION")
                        val addresses = Geocoder(appContext, Locale.getDefault())
                            .getFromLocationName(normalizedQuery, MAX_RESULTS).orEmpty()
                        PlaceSearchOutcome.Success(addresses.toSearchResults())
                    } catch (_: Exception) {
                        PlaceSearchOutcome.Failed
                    }
                    complete(outcome)
                }
            }.onFailure {
                AppDiagnostics.warn("place_search_queue_rejected", error = it)
            }.getOrNull()
            if (future == null) {
                complete(PlaceSearchOutcome.Failed)
            } else {
                futureRef.set(future)
                if (completed.get()) cancelFuture(futureRef.getAndSet(null))
            }
        }

        return Request(completed, timeoutRunnable, callbackRef, futureRef)
    }

    private fun List<Address>.toSearchResults(): List<PlaceSearchResult> =
        mapNotNull { address ->
            if (!address.hasLatitude() || !address.hasLongitude()) return@mapNotNull null
            val destination = Destination(address.latitude, address.longitude)
            if (!destination.isValidCoordinate()) return@mapNotNull null
            val label = address.getAddressLine(0)
                ?.takeIf { it.isNotBlank() }
                ?: address.featureName?.takeIf { it.isNotBlank() }
                ?: "${destination.lat}, ${destination.lng}"
            PlaceSearchResult(label, destination)
        }.distinctBy { it.destination }

    private fun cancelFuture(future: Future<*>?) {
        if (future == null) return
        future.cancel(true)
        executor.purge()
    }

    private const val DEFAULT_TIMEOUT_MILLIS = 7_000L
    private const val MAX_RESULTS = 5
    private const val MAX_QUEUED_REQUESTS = 4
}
