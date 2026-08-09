package jp.linkserver.beastlocator

import android.content.Context

enum class DestinationUpdateResult {
    UPDATED,
    UNCHANGED,
    INVALID,
    DISABLED
}

/** Applies a user destination and keeps every destination consumer in sync. */
object DestinationChangeCoordinator {
    fun setUserDestination(
        context: Context,
        destination: Destination
    ): DestinationUpdateResult {
        val appContext = context.applicationContext
        val store = DestinationStore(appContext)
        if (!store.isExperimentalDestinationEditingEnabled()) {
            return DestinationUpdateResult.DISABLED
        }
        if (!destination.isValidCoordinate()) return DestinationUpdateResult.INVALID

        val configuredDestination = store.getUserDestination() ?: store.getDefaultDestination()
        if (configuredDestination == destination) return DestinationUpdateResult.UNCHANGED

        val effectiveDestinationChanged = store.setUserDestination(destination)
        if (effectiveDestinationChanged) {
            synchronizeConsumers(appContext, store)
        }
        return DestinationUpdateResult.UPDATED
    }

    fun resetUserDestination(context: Context): DestinationUpdateResult {
        val appContext = context.applicationContext
        val store = DestinationStore(appContext)
        if (!store.isExperimentalDestinationEditingEnabled()) {
            return DestinationUpdateResult.DISABLED
        }
        if (!store.hasUserDestination()) return DestinationUpdateResult.UNCHANGED

        val effectiveDestinationChanged = store.clearUserDestination()
        if (effectiveDestinationChanged) {
            synchronizeConsumers(appContext, store)
        }
        return DestinationUpdateResult.UPDATED
    }

    private fun synchronizeConsumers(context: Context, store: DestinationStore) {
        NotificationHelper.cancelApproachProgress(context)
        NotificationHelper.cancelDestinationReached(context)

        val destination = store.getDestination()
        if (!store.isDestinationAnswered() &&
            !store.isDebugDistanceOverrideEnabled() &&
            GeofenceHelper.canRegisterDestinationGeofence(context)
        ) {
            GeofenceHelper.registerDestinationGeofence(context, destination)
        } else {
            GeofenceHelper.clearDestinationGeofence(context)
        }

        BackgroundLocationUpdater.updateRegistration(context)
        DestinationWidgetProvider.refreshAllWidgets(context)
    }
}
