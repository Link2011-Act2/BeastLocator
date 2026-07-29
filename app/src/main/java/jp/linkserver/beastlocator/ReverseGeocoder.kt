package jp.linkserver.beastlocator

import android.content.Context
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

object ReverseGeocoder {
    private val executor = ThreadPoolExecutor(
        2,
        2,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_QUEUED_REQUESTS),
        { runnable -> Thread(runnable, "beastlocator-geocoder").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val mainHandler = Handler(Looper.getMainLooper())

    class Request internal constructor(
        private val completed: AtomicBoolean,
        private val timeoutRunnable: Runnable,
        private val callbackRef: AtomicReference<((String) -> Unit)?>,
        private val futureRef: AtomicReference<Future<*>?>
    ) {
        fun cancel() {
            completed.set(true)
            callbackRef.set(null)
            cancelFuture(futureRef.getAndSet(null))
            mainHandler.removeCallbacks(timeoutRunnable)
        }
    }

    fun resolve(context: Context, destination: Destination): String {
        return resolveWithAndroidGeocoder(context, destination)
    }

    fun resolveAsync(
        context: Context,
        destination: Destination,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onResult: (String) -> Unit
    ): Request {
        val appContext = context.applicationContext
        val completed = AtomicBoolean(false)
        val callbackRef = AtomicReference<((String) -> Unit)?>(onResult)
        val futureRef = AtomicReference<Future<*>?>(null)
        val fallback = fallbackLatLng(destination)
        lateinit var timeoutRunnable: Runnable

        fun complete(value: String) {
            if (!completed.compareAndSet(false, true)) return
            mainHandler.removeCallbacks(timeoutRunnable)
            cancelFuture(futureRef.getAndSet(null))
            mainHandler.post {
                val callback = callbackRef.getAndSet(null) ?: return@post
                runCatching { callback(value) }
                    .onFailure {
                        AppDiagnostics.warn("geocoder_callback_failed", error = it)
                    }
            }
        }

        timeoutRunnable = Runnable { complete(fallback) }
        mainHandler.postDelayed(timeoutRunnable, timeoutMillis.coerceIn(1_000L, 15_000L))

        if (!Geocoder.isPresent()) {
            complete(fallback)
            return Request(completed, timeoutRunnable, callbackRef, futureRef)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                Geocoder(appContext, Locale.JAPAN).getFromLocation(
                    destination.lat,
                    destination.lng,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<android.location.Address>) {
                            complete(
                                addresses.firstOrNull()
                                    ?.getAddressLine(0)
                                    ?.takeIf { it.isNotBlank() }
                                    ?: fallback
                            )
                        }

                        override fun onError(errorMessage: String?) {
                            complete(fallback)
                        }
                    }
                )
            }.onFailure {
                AppDiagnostics.warn("geocoder_start_failed", error = it)
                complete(fallback)
            }
        } else {
            val future = runCatching {
                executor.submit {
                    complete(resolveWithAndroidGeocoder(appContext, destination))
                }
            }.onFailure {
                AppDiagnostics.warn("geocoder_queue_rejected", error = it)
            }.getOrNull()
            if (future == null) {
                complete(fallback)
            } else {
                futureRef.set(future)
                if (completed.get()) {
                    cancelFuture(futureRef.getAndSet(null))
                }
            }
        }
        return Request(completed, timeoutRunnable, callbackRef, futureRef)
    }

    private fun resolveWithAndroidGeocoder(context: Context, destination: Destination): String {
        return try {
            val geocoder = Geocoder(context, Locale.JAPAN)
            @Suppress("DEPRECATION")
            val address = geocoder.getFromLocation(destination.lat, destination.lng, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
                ?.takeIf { it.isNotBlank() }
            address ?: fallbackLatLng(destination)
        } catch (_: Exception) {
            fallbackLatLng(destination)
        }
    }

    private fun fallbackLatLng(destination: Destination): String {
        return "${destination.lat}, ${destination.lng}"
    }

    private fun cancelFuture(future: Future<*>?) {
        if (future == null) return
        future.cancel(true)
        executor.purge()
    }

    private const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    private const val MAX_QUEUED_REQUESTS = 8
}


