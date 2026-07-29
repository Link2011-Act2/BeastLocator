package jp.linkserver.beastlocator

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

object GeofenceHelper {
    const val ACTION_GEOFENCE = "jp.linkserver.beastlocator.ACTION_GEOFENCE_EVENT"
    private const val GEOFENCE_ID = "destination_geofence"
    private val registrationLock = Any()
    private var operationInFlight = false
    private var desiredDestination: Destination? = null
    private var hasAttemptedClearInProcess = false

    private fun geofencingClient(context: Context): GeofencingClient {
        return LocationServices.getGeofencingClient(context)
    }

    private fun geofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = ACTION_GEOFENCE
        }
        val mutabilityFlag = if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        ) {
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

    fun canRegisterDestinationGeofence(context: Context): Boolean {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation) {
            return false
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val hasBackgroundLocation = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBackgroundLocation) {
                return false
            }
        }

        return true
    }

    @SuppressLint("MissingPermission")
    fun registerDestinationGeofence(context: Context, destination: Destination) {
        val appContext = context.applicationContext
        if (!canRegisterDestinationGeofence(appContext)) {
            return
        }
        val store = DestinationStore(appContext)
        val shouldRegister = synchronized(registrationLock) {
            desiredDestination = destination
            if (operationInFlight || store.isSameRegisteredGeofenceDestination(destination)) {
                false
            } else {
                operationInFlight = true
                true
            }
        }
        if (!shouldRegister) {
            return
        }
        startRegistration(appContext, destination)
    }

    @SuppressLint("MissingPermission")
    private fun startRegistration(context: Context, destination: Destination) {
        val registrationTask = runCatching {
            val geofence = Geofence.Builder()
                .setRequestId(GEOFENCE_ID)
                .setCircularRegion(destination.lat, destination.lng, 50f)
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
            finishRegistration(context, destination, succeeded = false)
            return
        }

        registrationTask.addOnCompleteListener {
            finishRegistration(context, destination, succeeded = it.isSuccessful)
        }
    }

    fun clearDestinationGeofence(context: Context) {
        val appContext = context.applicationContext
        val store = DestinationStore(appContext)
        val shouldRemove = synchronized(registrationLock) {
            val needsLegacyStateCleanup = !hasAttemptedClearInProcess
            desiredDestination = null
            val hasStoredRegistration = store.getRegisteredGeofenceDestination() != null
            store.clearRegisteredGeofenceDestination()
            hasAttemptedClearInProcess = true
            if (operationInFlight || (!needsLegacyStateCleanup && !hasStoredRegistration)) {
                false
            } else {
                operationInFlight = true
                true
            }
        }
        if (!shouldRemove) return
        startRemoval(appContext)
    }

    private fun finishRegistration(
        context: Context,
        destination: Destination,
        succeeded: Boolean
    ) {
        var nextDestination: Destination? = null
        var shouldRemove = false
        synchronized(registrationLock) {
            operationInFlight = false
            val desired = desiredDestination
            if (succeeded && desired == destination) {
                DestinationStore(context).setRegisteredGeofenceDestination(destination)
            }
            when {
                desired == null -> {
                    DestinationStore(context).clearRegisteredGeofenceDestination()
                    operationInFlight = true
                    hasAttemptedClearInProcess = true
                    shouldRemove = true
                }
                desired != destination -> {
                    operationInFlight = true
                    nextDestination = desired
                }
            }
        }

        if (shouldRemove) {
            startRemoval(context)
        } else {
            nextDestination?.let { startRegistration(context, it) }
        }
    }

    private fun startRemoval(context: Context) {
        val removalTask = runCatching {
            geofencingClient(context).removeGeofences(geofencePendingIntent(context))
        }.getOrElse {
            finishRemoval(context, succeeded = false)
            return
        }
        removalTask.addOnCompleteListener {
            finishRemoval(context, succeeded = it.isSuccessful)
        }
    }

    private fun finishRemoval(context: Context, succeeded: Boolean) {
        var nextDestination: Destination? = null
        synchronized(registrationLock) {
            operationInFlight = false
            hasAttemptedClearInProcess = succeeded
            DestinationStore(context).clearRegisteredGeofenceDestination()
            desiredDestination?.let {
                operationInFlight = true
                nextDestination = it
            }
        }
        nextDestination?.let { startRegistration(context, it) }
    }
}

