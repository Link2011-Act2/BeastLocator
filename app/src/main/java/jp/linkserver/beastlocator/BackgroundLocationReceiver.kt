package jp.linkserver.beastlocator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.LocationResult

/**
 * Compatibility receiver for location PendingIntents created by older versions of the app.
 * New background tracking is service-based, but a queued legacy update can still arrive until
 * Google Play services confirms its removal.
 */
class BackgroundLocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BackgroundLocationUpdater.ACTION_LOCATION_UPDATE) return
        val result = LocationResult.extractResult(intent) ?: return
        val appContext = context.applicationContext
        val store = DestinationStore(appContext)

        if (store.isDebugDistanceOverrideEnabled()) {
            GeofenceHelper.clearDestinationGeofence(appContext)
            DestinationWidgetProvider.refreshAllWidgets(appContext)
            return
        }

        var lastAcceptedDistance: Float? = null
        var acceptedAnySample = false
        val sampleGate = LocationSampleGate().apply {
            reset(store.getLastKnownLocationSample())
        }
        for (location in result.locations) {
            val sample = LocationSampleFactory.fromAndroidLocation(
                location,
                LocationSampleSource.CONTINUOUS
            ) ?: continue
            if (!sampleGate.accept(sample)) continue
            acceptedAnySample = true
            store.setLastKnownLocation(sample)

            val destination = store.getDestination()
            val distanceMeters = GeoUtils.distanceMeters(sample.position, destination)
            lastAcceptedDistance = distanceMeters
            if (ArrivalCoordinator.observeLocation(
                    appContext,
                    store,
                    sample,
                    destination,
                    distanceMeters
                )
            ) {
                break
            }
        }

        if (!acceptedAnySample) {
            AppDiagnostics.info("legacy_location_batch_rejected")
            return
        }

        if (!store.isDestinationAnswered()) {
            GeofenceHelper.registerDestinationGeofence(appContext, store.getDestination())
            lastAcceptedDistance?.let {
                ApproachProgressController.update(appContext, store, it)
            }
        } else {
            GeofenceHelper.clearDestinationGeofence(appContext)
        }
        DestinationWidgetProvider.refreshAllWidgets(appContext)
    }

}
