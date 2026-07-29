package jp.linkserver.beastlocator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        val finished = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        lateinit var timeout: Runnable

        fun finishOnce() {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacks(timeout)
            runCatching { pendingResult.finish() }
                .onFailure {
                    AppDiagnostics.warn("boot_receiver_finish_failed", error = it)
                }
        }

        timeout = Runnable {
            AppDiagnostics.warn("boot_geofence_registration_timeout", "action=$action")
            finishOnce()
        }
        handler.postDelayed(timeout, RECEIVER_TIMEOUT_MILLIS)
        GeofenceRecoveryScheduler.enqueue(appContext, delayMillis = 0L)

        runCatching {
            val store = DestinationStore(appContext)
            GeofenceHelper.invalidateSystemRegistration(appContext)
            BackgroundLocationUpdater.updateRegistration(appContext)

            if (!store.isDestinationAnswered() &&
                GeofenceHelper.canRegisterDestinationGeofence(appContext)
            ) {
                GeofenceHelper.registerDestinationGeofence(
                    appContext,
                    store.getDestination()
                ) { succeeded ->
                    if (!succeeded) {
                        AppDiagnostics.warn("boot_geofence_registration_failed")
                    }
                    finishOnce()
                }
            } else {
                GeofenceHelper.clearDestinationGeofence(appContext)
                finishOnce()
            }
        }.onFailure {
            AppDiagnostics.warn("boot_recovery_failed", "action=$action", it)
            finishOnce()
        }
    }

    companion object {
        private const val RECEIVER_TIMEOUT_MILLIS = 8_500L
    }
}


