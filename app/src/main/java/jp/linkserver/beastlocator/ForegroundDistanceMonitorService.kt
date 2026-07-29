package jp.linkserver.beastlocator

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class ForegroundDistanceMonitorService : Service() {
    private lateinit var store: DestinationStore
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var distance114514SoundPlayed = false
    private var lastIntervalBucket: Int? = null
    private var previousDistanceMeters: Float? = null
    private var lastWidgetUpdateTimeMs: Long = 0L
    private var isForegroundStarted = false
    private var isRequestingLocationUpdates = false

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        4_000L
    ).setMinUpdateIntervalMillis(2_000L).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            if (store.isDebugDistanceOverrideEnabled()) {
                return
            }
            val current = Destination(location.latitude, location.longitude)
            store.setLastKnownLocationFromSystem(current.lat, current.lng)
            if (location.hasBearing()) {
                store.setLastKnownHeading(location.bearing)
            }

            val destination = store.getDestination()
            val distanceMeters = GeoUtils.distanceMeters(current, destination)

            if (!store.isDestinationAnswered()) {
                GeofenceHelper.registerDestinationGeofence(this@ForegroundDistanceMonitorService, destination)
            } else {
                GeofenceHelper.clearDestinationGeofence(this@ForegroundDistanceMonitorService)
            }

            if (store.isArrivalRearmRequired() && distanceMeters > ARRIVAL_THRESHOLD_METERS) {
                store.setArrivalRearmRequired(false)
            }
            handleArrivalByDistance(destination, distanceMeters)
            updateApproachLiveUpdate(distanceMeters)
            
            val now = System.currentTimeMillis()
            val isLiveUpdateRanged = store.isLiveUpdateEnabled() && 
                distanceMeters <= store.getLiveUpdateStartDistanceMeters()
            
            if (isLiveUpdateRanged || now - lastWidgetUpdateTimeMs >= 10 * 60 * 1000L) {
                DestinationWidgetProvider.refreshAllWidgets(this@ForegroundDistanceMonitorService)
                lastWidgetUpdateTimeMs = now
            }
            
            handleSoundTriggers(distanceMeters)
            previousDistanceMeters = distanceMeters
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = DestinationStore(this)
        createServiceChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BackgroundLocationUpdater.shouldRunForegroundMonitor(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isForegroundStarted) {
            val notification = buildServiceNotification()
            val foregroundStarted = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }.isSuccess
            if (!foregroundStarted) {
                stopSelf()
                return START_NOT_STICKY
            }
            isForegroundStarted = true
        }
        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        if (isRequestingLocationUpdates) {
            fusedClient.removeLocationUpdates(locationCallback)
            isRequestingLocationUpdates = false
        }
        if (isForegroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (isRequestingLocationUpdates || !hasLocationPermission()) return
        isRequestingLocationUpdates = true
        runCatching {
            fusedClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
                .addOnFailureListener {
                    isRequestingLocationUpdates = false
                    stopSelf()
                }
        }.onFailure {
            isRequestingLocationUpdates = false
            stopSelf()
        }
    }

    private fun handleSoundTriggers(distanceMeters: Float) {
        if (store.isDistance114514SoundEnabled() &&
            !distance114514SoundPlayed &&
            entered114514Range(previousDistanceMeters, distanceMeters)
        ) {
            distance114514SoundPlayed = true
            playSound(R.raw.distance_114514km)
        }

        if (store.isDistanceIntervalSoundEnabled()) {
            handleIntervalDistanceSound(distanceMeters)
        } else {
            lastIntervalBucket = null
        }
    }

    private fun handleArrivalByDistance(destination: Destination, distanceMeters: Float) {
        if (store.isDestinationAnswered()) return
        if (store.isArrivalRearmRequired()) return
        if (distanceMeters > ARRIVAL_THRESHOLD_METERS) return

        if (store.isArrivalSoundEnabled()) {
            playSound(R.raw.arrival_0km)
        }
        store.setDestinationAnswered(true)
        store.setArrivalDestinationName("${destination.lat}, ${destination.lng}")
        NotificationHelper.cancelApproachProgress(this)
        NotificationHelper.showDestinationReached(
            this,
            getString(R.string.notification_body, "${destination.lat}, ${destination.lng}")
        )
        GeofenceHelper.clearDestinationGeofence(this)

        Thread {
            val resolved = ReverseGeocoder.resolve(this, destination)
            if (!store.isDestinationAnswered() || store.getDestination() != destination) {
                return@Thread
            }
            store.setArrivalDestinationName(resolved)
            NotificationHelper.showDestinationReached(
                this,
                getString(R.string.notification_body, resolved)
            )
            DestinationWidgetProvider.refreshAllWidgets(this)
        }.start()
    }

    private fun entered114514Range(previousDistanceMeters: Float?, currentDistanceMeters: Float): Boolean {
        val previous = previousDistanceMeters ?: return false
        return previous > DISTANCE_114514_ENTER_THRESHOLD_METERS &&
            currentDistanceMeters <= DISTANCE_114514_ENTER_THRESHOLD_METERS
    }

    private fun playSound(rawResId: Int) {
        SoundEffectPlayer.play(this, rawResId)
    }

    private fun handleIntervalDistanceSound(distanceMeters: Float) {
        if (store.isDestinationAnswered()) {
            lastIntervalBucket = null
            return
        }
        val intervalMeters = store.getDistanceIntervalSoundMeters().coerceIn(100, 5000)
        val currentBucket = (distanceMeters / intervalMeters.toFloat()).toInt()
        val previousBucket = lastIntervalBucket
        lastIntervalBucket = currentBucket
        if (previousBucket == null) return

        // Play only when approaching destination and crossing an interval boundary.
        if (currentBucket < previousBucket) {
            playSound(R.raw.distance_interval_kankaku)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBackground = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBackground) return false
        }
        return true
    }

    private fun buildServiceNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            41,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = when {
            store.isArrivalSoundEnabled() &&
                store.isDistance114514SoundEnabled() &&
                store.isDistanceIntervalSoundEnabled() ->
                getString(R.string.sound_monitor_notification_all)
            store.isArrivalSoundEnabled() && store.isDistance114514SoundEnabled() ->
                getString(R.string.sound_monitor_notification_both)
            store.isArrivalSoundEnabled() && store.isDistanceIntervalSoundEnabled() ->
                getString(R.string.sound_monitor_notification_arrival_and_interval)
            store.isDistance114514SoundEnabled() && store.isDistanceIntervalSoundEnabled() ->
                getString(R.string.sound_monitor_notification_114514_and_interval)
            store.isArrivalSoundEnabled() ->
                getString(R.string.sound_monitor_notification_arrival_only)
            store.isDistance114514SoundEnabled() ->
                getString(R.string.sound_monitor_notification_114514_only)
            store.isDistanceIntervalSoundEnabled() ->
                getString(R.string.sound_monitor_notification_interval_only)
            else ->
                getString(R.string.sound_monitor_notification_background_only)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_arrow)
            .setContentTitle(getString(R.string.sound_monitor_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createServiceChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sound_monitor_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun updateApproachLiveUpdate(distanceMeters: Float) {
        if (!NotificationHelper.isLiveUpdateSupported()) {
            NotificationHelper.cancelApproachProgress(this)
            store.clearLiveUpdateAnchorDistanceMeters()
            return
        }
        if (!store.isLiveUpdateEnabled() || store.isDestinationAnswered()) {
            NotificationHelper.cancelApproachProgress(this)
            store.clearLiveUpdateAnchorDistanceMeters()
            return
        }
        val startDistanceMeters = store.getLiveUpdateStartDistanceMeters().coerceIn(200, 5000).toFloat()
        if (distanceMeters > startDistanceMeters || distanceMeters <= ARRIVAL_THRESHOLD_METERS) {
            NotificationHelper.cancelApproachProgress(this)
            store.clearLiveUpdateAnchorDistanceMeters()
            return
        }
        val anchorDistance = store.getLiveUpdateAnchorDistanceMeters()
            ?.takeIf { it > ARRIVAL_THRESHOLD_METERS } ?: distanceMeters.also {
            store.setLiveUpdateAnchorDistanceMeters(it)
        }
        val span = (anchorDistance - ARRIVAL_THRESHOLD_METERS).coerceAtLeast(1f)
        val progress = (((anchorDistance - distanceMeters) / span) * 100f).toInt().coerceIn(0, 100)
        NotificationHelper.showApproachProgress(this, distanceMeters, progress)
    }

    companion object {
        private const val CHANNEL_ID = "sound_monitor_channel"
        private const val NOTIFICATION_ID = 1514
        private const val ARRIVAL_THRESHOLD_METERS = 50f
        private const val DISTANCE_114514_METERS = 114_514f
        private const val DISTANCE_MATCH_TOLERANCE_METERS = 80f
        private const val DISTANCE_114514_ENTER_THRESHOLD_METERS =
            DISTANCE_114514_METERS + DISTANCE_MATCH_TOLERANCE_METERS

        fun start(context: Context) {
            val intent = Intent(context, ForegroundDistanceMonitorService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ForegroundDistanceMonitorService::class.java))
        }
    }
}
