package jp.linkserver.beastlocator

import android.content.Context

object ArrivalCoordinator {
    @Volatile
    private var resolvingGeneration: Long? = null

    fun isResolvingName(destinationGeneration: Long): Boolean =
        resolvingGeneration == destinationGeneration

    fun observeLocation(
        context: Context,
        store: DestinationStore,
        sample: LocationSample,
        destination: Destination,
        distanceMeters: Float,
        soundPlayer: ((Int) -> Unit)? = null,
        stopBackgroundMonitor: Boolean = true,
        onNameResolved: ((String) -> Unit)? = null
    ): Boolean {
        if (store.isDestinationAnswered()) return false

        val currentSample = sample.refreshedAge()
        val destinationGeneration = store.getDestinationGeneration()
        if (store.isArrivalRearmRequired()) {
            if (currentSample.isEligibleForArrival() &&
                SharedArrivalConfirmation.shouldClearRearm(distanceMeters)
            ) {
                store.setArrivalRearmRequired(false)
            }
            return false
        }

        val observation = SharedArrivalConfirmation.observe(
            sample = currentSample,
            distanceMeters = distanceMeters,
            destinationGeneration = destinationGeneration,
            arrivalRearmRequired = false
        )
        if (observation != ArrivalObservation.CONFIRMED) return false
        return completeArrival(
            context,
            store,
            destination,
            destinationGeneration,
            soundPlayer,
            stopBackgroundMonitor,
            onNameResolved
        )
    }

    @Synchronized
    fun completeArrival(
        context: Context,
        store: DestinationStore,
        destination: Destination,
        destinationGeneration: Long,
        soundPlayer: ((Int) -> Unit)? = null,
        stopBackgroundMonitor: Boolean = true,
        onNameResolved: ((String) -> Unit)? = null
    ): Boolean {
        if (store.isDestinationAnswered() || store.isArrivalRearmRequired()) return false
        if (store.getDestinationGeneration() != destinationGeneration) return false
        if (store.getDestination() != destination) return false

        val appContext = context.applicationContext
        val coordinateText = "${destination.lat}, ${destination.lng}"
        val stateSaved = runCatching {
            store.markDestinationAnswered(coordinateText, resolved = false)
        }.onFailure {
            AppDiagnostics.warn("arrival_state_save_failed", error = it)
        }.isSuccess
        if (!stateSaved) return false

        runSideEffect("arrival_progress_cancel_failed") {
            NotificationHelper.cancelApproachProgress(appContext)
        }
        if (store.isArrivalSoundEnabled()) {
            runSideEffect("arrival_sound_start_failed") {
                val playSound = soundPlayer ?: { rawResId: Int ->
                    SoundEffectPlayer.play(appContext, rawResId)
                }
                playSound(R.raw.arrival_0km)
            }
        }
        runSideEffect("arrival_notification_failed") {
            NotificationHelper.showDestinationReached(
                appContext,
                appContext.getString(R.string.notification_body, coordinateText)
            )
        }
        runSideEffect("arrival_geofence_clear_failed") {
            GeofenceHelper.clearDestinationGeofence(appContext)
        }
        runSideEffect("arrival_widget_refresh_failed") {
            DestinationWidgetProvider.refreshAllWidgets(appContext)
        }
        if (stopBackgroundMonitor) {
            runSideEffect("arrival_background_monitor_stop_failed") {
                BackgroundLocationUpdater.updateRegistration(appContext)
            }
        }

        resolvingGeneration = destinationGeneration
        runCatching {
            ReverseGeocoder.resolveAsync(appContext, destination) { resolved ->
                if (resolvingGeneration == destinationGeneration) {
                    resolvingGeneration = null
                }
                if (!store.isDestinationAnswered() ||
                    store.getDestinationGeneration() != destinationGeneration ||
                    store.getDestination() != destination
                ) {
                    return@resolveAsync
                }
                runSideEffect("arrival_name_save_failed") {
                    store.setArrivalDestinationName(resolved, resolved = resolved != coordinateText)
                }
                runSideEffect("arrival_name_callback_failed") {
                    onNameResolved?.invoke(resolved)
                }
                runSideEffect("arrival_resolved_notification_failed") {
                    NotificationHelper.showDestinationReached(
                        appContext,
                        appContext.getString(R.string.notification_body, resolved)
                    )
                }
                runSideEffect("arrival_resolved_widget_refresh_failed") {
                    DestinationWidgetProvider.refreshAllWidgets(appContext)
                }
            }
        }.onFailure {
            if (resolvingGeneration == destinationGeneration) {
                resolvingGeneration = null
            }
            AppDiagnostics.warn("arrival_geocoder_start_failed", error = it)
        }
        return true
    }

    private inline fun runSideEffect(event: String, block: () -> Unit) {
        runCatching(block).onFailure {
            AppDiagnostics.warn(event, error = it)
        }
    }
}
