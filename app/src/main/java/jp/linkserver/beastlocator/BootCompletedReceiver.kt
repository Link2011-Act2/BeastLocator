package jp.linkserver.beastlocator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val store = DestinationStore(context)
            store.clearRegisteredGeofenceDestination()
            if (!store.isDestinationAnswered() &&
                GeofenceHelper.canRegisterDestinationGeofence(context)
            ) {
                GeofenceHelper.registerDestinationGeofence(context, store.getDestination())
            }
            BackgroundLocationUpdater.updateRegistration(context)
        }
    }
}


