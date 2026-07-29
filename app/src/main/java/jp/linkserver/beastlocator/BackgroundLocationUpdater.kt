package jp.linkserver.beastlocator

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices

object BackgroundLocationUpdater {
    const val ACTION_LOCATION_UPDATE = "jp.linkserver.beastlocator.ACTION_LOCATION_UPDATE"
    @Volatile
    private var isAppInForeground = false
    @Volatile
    private var hasRequestedLegacyCleanup = false
    private var legacyCleanupToken = 0L
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun setAppInForeground(context: Context, active: Boolean) {
        if (isAppInForeground == active) return
        isAppInForeground = active
        AppDiagnostics.info("app_visibility", if (active) "foreground" else "background")
        updateRegistration(context.applicationContext)
    }

    fun updateRegistration(context: Context) {
        if (shouldRunForegroundMonitor(context)) {
            ForegroundDistanceMonitorService.start(context)
        } else {
            ForegroundDistanceMonitorService.stop(context)
        }
        if (isGoogleLocationAvailable(context)) {
            stopLegacyPendingIntentUpdatesOnce(context)
        }
    }

    fun shouldRunForegroundMonitor(context: Context): Boolean {
        val store = DestinationStore(context)
        return !isAppInForeground &&
            !store.isDestinationAnswered() &&
            !store.isDebugDistanceOverrideEnabled() &&
            store.isBackgroundLocationUpdateActive() &&
            hasRequiredLocationPermissions(context) &&
            isGoogleLocationAvailable(context)
    }

    private fun stopLegacyPendingIntentUpdatesOnce(context: Context) {
        if (hasRequestedLegacyCleanup) return
        hasRequestedLegacyCleanup = true
        val token = ++legacyCleanupToken
        val timeout = Runnable {
            if (token == legacyCleanupToken && hasRequestedLegacyCleanup) {
                hasRequestedLegacyCleanup = false
                AppDiagnostics.warn("legacy_location_cleanup_timeout")
            }
        }
        mainHandler.postDelayed(timeout, LEGACY_CLEANUP_TIMEOUT_MILLIS)
        val task = runCatching {
            LocationServices.getFusedLocationProviderClient(context)
                .removeLocationUpdates(locationPendingIntent(context))
        }.getOrElse {
            mainHandler.removeCallbacks(timeout)
            hasRequestedLegacyCleanup = false
            AppDiagnostics.warn("legacy_location_cleanup_threw", error = it)
            return
        }
        task.addOnCompleteListener {
            if (token != legacyCleanupToken) return@addOnCompleteListener
            mainHandler.removeCallbacks(timeout)
            hasRequestedLegacyCleanup = it.isSuccessful
            if (!it.isSuccessful) {
                AppDiagnostics.warn("legacy_location_cleanup_failed", error = it.exception)
            }
        }
    }

    fun googleLocationAvailability(context: Context): Int = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
    }.onFailure {
        AppDiagnostics.warn("google_location_check_failed", error = it)
    }.getOrDefault(ConnectionResult.SERVICE_INVALID)

    fun isGoogleLocationAvailable(context: Context): Boolean =
        googleLocationAvailability(context) == ConnectionResult.SUCCESS

    fun hasRequiredLocationPermissions(context: Context): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBackground = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBackground) return false
        }
        return true
    }

    private fun locationPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BackgroundLocationReceiver::class.java).apply {
            action = ACTION_LOCATION_UPDATE
        }
        return PendingIntent.getBroadcast(
            context,
            31,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private const val LEGACY_CLEANUP_TIMEOUT_MILLIS = 8_000L
}
