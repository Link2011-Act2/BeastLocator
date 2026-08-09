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
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.atomic.AtomicBoolean

class ForegroundDistanceMonitorService : Service() {
    private lateinit var store: DestinationStore
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var lastWidgetUpdateTimeMs: Long = 0L
    private var isForegroundStarted = false
    private var isRequestingLocationUpdates = false
    private var isSwitchingLocationRequest = false
    private var serviceDestroyed = false
    private var requestMode = RequestMode.BALANCED
    private var locationRequestGeneration = 0L
    private var requestSwitchToken = 0L
    private var activeLocationCallback: LocationCallback? = null
    private var locationRequestStartTimeoutRunnable: Runnable? = null
    private val locationSampleGate = LocationSampleGate()
    private val mainHandler by lazy { Handler(mainLooper) }
    private var localSoundPlayer: MediaPlayer? = null
    private var localSoundPriority = 0
    private var localAudioFocusRequest: AudioFocusRequest? = null
    private var stopAfterLocalSound = false
    private val playbackAudioAttributes by lazy {
        AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
    }
    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN ->
                localSoundPlayer?.runCatching { setVolume(1f, 1f) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                localSoundPlayer?.runCatching {
                    setVolume(LOCAL_SOUND_DUCK_VOLUME, LOCAL_SOUND_DUCK_VOLUME)
                }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> finishLocalSound()
        }
    }

    private fun createLocationCallback(requestGeneration: Long): LocationCallback =
        object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (serviceDestroyed ||
                requestGeneration != locationRequestGeneration ||
                activeLocationCallback !== this
            ) {
                return
            }
            clearLocationRequestStartTimeout()
            if (store.isDebugDistanceOverrideEnabled()) {
                stopSelf()
                return
            }

            var latestAcceptedDistanceMeters: Float? = null
            LocationBatchProcessor.processOldestFirst(
                items = result.locations,
                elapsedRealtimeNanos = Location::getElapsedRealtimeNanos,
                wallTimeMillis = Location::getTime
            ) { location ->
                processContinuousLocation(location)?.let { distanceMeters ->
                    latestAcceptedDistanceMeters = distanceMeters
                }
                !serviceDestroyed &&
                    requestGeneration == locationRequestGeneration &&
                    activeLocationCallback === this &&
                    !store.isDestinationAnswered()
            }

            if (!serviceDestroyed &&
                requestGeneration == locationRequestGeneration &&
                activeLocationCallback === this &&
                !store.isDestinationAnswered()
            ) {
                latestAcceptedDistanceMeters?.let(::updateRequestModeForDistance)
            }
        }
    }

    private fun processContinuousLocation(location: Location): Float? {
        val sample = LocationSampleFactory.fromAndroidLocation(
            location,
            LocationSampleSource.CONTINUOUS,
            allowMockForDevelopment = store.isMockLocationAllowedForTesting()
        ) ?: return null
        if (!locationSampleGate.accept(sample)) {
            AppDiagnostics.info(
                "background_location_sample_rejected",
                "accuracy=${sample.accuracyMeters}"
            )
            return null
        }
        val current = sample.position
        store.setLastKnownLocation(sample)

        val destination = store.getDestination()
        val distanceMeters = runCatching {
            GeoUtils.distanceMeters(current, destination)
        }.getOrNull()?.takeIf { it.isFinite() } ?: return null

        if (!store.isDestinationAnswered()) {
            GeofenceHelper.registerDestinationGeofence(this, destination)
        } else {
            GeofenceHelper.clearDestinationGeofence(this)
        }

        val arrived = ArrivalCoordinator.observeLocation(
            this,
            store,
            sample,
            destination,
            distanceMeters,
            soundPlayer = ::playLocalSound,
            stopBackgroundMonitor = false
        )
        if (arrived) {
            stopTrackingAfterArrival()
            return null
        }
        if (store.isDestinationAnswered()) {
            stopSelf()
            return null
        }
        updateApproachLiveUpdate(distanceMeters)

        val now = SystemClock.elapsedRealtime()
        val isLiveUpdateRanged = NotificationHelper.isLiveUpdateSupported() &&
            store.isLiveUpdateEnabled() &&
            distanceMeters <= store.getLiveUpdateStartDistanceMeters()

        val liveUpdateDue = isLiveUpdateRanged &&
            (lastWidgetUpdateTimeMs == 0L ||
                now - lastWidgetUpdateTimeMs >= MIN_LIVE_WIDGET_UPDATE_INTERVAL_MILLIS)
        if (liveUpdateDue ||
            lastWidgetUpdateTimeMs == 0L ||
            now - lastWidgetUpdateTimeMs >= 10 * 60 * 1000L
        ) {
            DestinationWidgetProvider.refreshAllWidgets(this)
            lastWidgetUpdateTimeMs = now
        }

        DistanceEventProcessor.process(
            this,
            store,
            distanceMeters,
            soundPlayer = ::playLocalSound
        )
        return distanceMeters
    }

    override fun onCreate() {
        super.onCreate()
        serviceDestroyed = false
        store = DestinationStore(this)
        locationSampleGate.reset(store.getLastKnownLocationSample())
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
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }.onFailure {
                AppDiagnostics.warn("location_fgs_start_foreground_failed", error = it)
            }.isSuccess
            if (!foregroundStarted) {
                stopSelf()
                return START_NOT_STICKY
            }
            isForegroundStarted = true
        } else {
            runCatching {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    buildServiceNotification()
                )
            }.onFailure {
                AppDiagnostics.warn("location_fgs_notification_update_failed", error = it)
            }
        }
        if (!isRequestingLocationUpdates && !isSwitchingLocationRequest) {
            requestMode = preferredInitialRequestMode()
        }
        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        serviceDestroyed = true
        locationRequestGeneration += 1L
        requestSwitchToken += 1L
        val callback = activeLocationCallback
        activeLocationCallback = null
        isRequestingLocationUpdates = false
        isSwitchingLocationRequest = false
        clearLocationRequestStartTimeout()
        callback?.let { removeLocationUpdatesSafely(it, "service_destroy") }
        releaseLocalSoundResources()
        if (isForegroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (isRequestingLocationUpdates || isSwitchingLocationRequest) return
        if (!hasLocationPermission()) {
            AppDiagnostics.warn("background_location_permission_lost")
            stopSelf()
            return
        }
        val generation = ++locationRequestGeneration
        val callback = createLocationCallback(generation)
        activeLocationCallback = callback
        isRequestingLocationUpdates = true
        val request = buildLocationRequest(requestMode)
        val requestTask = runCatching {
            fusedClient.requestLocationUpdates(request, callback, mainLooper)
        }.getOrElse {
            isRequestingLocationUpdates = false
            activeLocationCallback = null
            AppDiagnostics.warn("background_location_request_threw", error = it)
            stopSelf()
            return
        }
        lateinit var startTimeout: Runnable
        startTimeout = Runnable {
            if (locationRequestStartTimeoutRunnable !== startTimeout) return@Runnable
            locationRequestStartTimeoutRunnable = null
            if (!serviceDestroyed &&
                generation == locationRequestGeneration &&
                activeLocationCallback === callback
            ) {
                AppDiagnostics.warn("background_location_request_timeout")
                stopSelf()
            }
        }
        locationRequestStartTimeoutRunnable = startTimeout
        mainHandler.postDelayed(startTimeout, LOCATION_OPERATION_TIMEOUT_MILLIS)
        requestTask.addOnCompleteListener { task ->
            if (locationRequestStartTimeoutRunnable === startTimeout) {
                mainHandler.removeCallbacks(startTimeout)
                locationRequestStartTimeoutRunnable = null
            }
            val stillActive = !serviceDestroyed &&
                generation == locationRequestGeneration &&
                activeLocationCallback === callback
            if (!stillActive) {
                // Close the add-after-remove race seen with delayed OEM/GMS Tasks.
                removeLocationUpdatesSafely(callback, "stale_request_completion")
                return@addOnCompleteListener
            }
            if (!task.isSuccessful) {
                isRequestingLocationUpdates = false
                activeLocationCallback = null
                AppDiagnostics.warn("background_location_request_failed", error = task.exception)
                stopSelf()
            }
        }
    }

    private fun preferredInitialRequestMode(): RequestMode {
        val last = store.getLastKnownLocationSample()?.position ?: return RequestMode.BALANCED
        val target = store.getDestination()
        val distance = runCatching { GeoUtils.distanceMeters(last, target) }.getOrNull()
            ?: return RequestMode.BALANCED
        return if (distance <= highAccuracyEnterDistanceMeters()) {
            RequestMode.HIGH_ACCURACY
        } else {
            RequestMode.BALANCED
        }
    }

    private fun updateRequestModeForDistance(distanceMeters: Float) {
        val enterDistance = highAccuracyEnterDistanceMeters()
        val desired = when (requestMode) {
            RequestMode.BALANCED -> if (distanceMeters <= enterDistance) {
                RequestMode.HIGH_ACCURACY
            } else {
                RequestMode.BALANCED
            }
            RequestMode.HIGH_ACCURACY -> if (distanceMeters >= enterDistance * 1.5f) {
                RequestMode.BALANCED
            } else {
                RequestMode.HIGH_ACCURACY
            }
        }
        if (desired == requestMode || isSwitchingLocationRequest) return
        if (!isRequestingLocationUpdates) {
            requestMode = desired
            startLocationUpdates()
            return
        }

        isSwitchingLocationRequest = true
        val callback = activeLocationCallback
        if (callback == null) {
            isSwitchingLocationRequest = false
            isRequestingLocationUpdates = false
            requestMode = desired
            startLocationUpdates()
            return
        }
        val switchToken = ++requestSwitchToken
        val timeout = Runnable {
            if (!serviceDestroyed &&
                switchToken == requestSwitchToken &&
                isSwitchingLocationRequest
            ) {
                requestSwitchToken += 1L
                locationRequestGeneration += 1L
                activeLocationCallback = null
                isRequestingLocationUpdates = false
                isSwitchingLocationRequest = false
                AppDiagnostics.warn("background_location_mode_switch_timeout")
                removeLocationUpdatesSafely(callback, "request_mode_switch_timeout")
                stopSelf()
            }
        }
        mainHandler.postDelayed(timeout, LOCATION_OPERATION_TIMEOUT_MILLIS)
        removeLocationUpdatesSafely(callback, "request_mode_switch") { removed ->
            if (serviceDestroyed || switchToken != requestSwitchToken) {
                return@removeLocationUpdatesSafely
            }
            mainHandler.removeCallbacks(timeout)
            isSwitchingLocationRequest = false
            if (!removed) {
                // Keep the known request authoritative and retry the switch on a later sample.
                return@removeLocationUpdatesSafely
            }
            locationRequestGeneration += 1L
            if (activeLocationCallback === callback) {
                activeLocationCallback = null
                isRequestingLocationUpdates = false
            }
            requestMode = desired
            if (BackgroundLocationUpdater.shouldRunForegroundMonitor(this)) {
                startLocationUpdates()
            }
        }
    }

    private fun removeLocationUpdatesSafely(
        callback: LocationCallback,
        reason: String,
        attempt: Int = 0,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val task = runCatching {
            fusedClient.removeLocationUpdates(callback)
        }.getOrElse {
            AppDiagnostics.warn("background_location_remove_threw", "reason=$reason", it)
            retryLocationRemovalOrComplete(callback, reason, onComplete, attempt)
            return
        }
        val handled = AtomicBoolean(false)
        lateinit var timeout: Runnable
        timeout = Runnable {
            if (!handled.compareAndSet(false, true)) return@Runnable
            AppDiagnostics.warn(
                "background_location_remove_timeout",
                "reason=$reason, attempt=$attempt"
            )
            retryLocationRemovalOrComplete(callback, reason, onComplete, attempt)
        }
        mainHandler.postDelayed(timeout, LOCATION_OPERATION_TIMEOUT_MILLIS)
        task.addOnCompleteListener { completedTask ->
            if (!handled.compareAndSet(false, true)) return@addOnCompleteListener
            mainHandler.removeCallbacks(timeout)
            if (!completedTask.isSuccessful) {
                AppDiagnostics.warn(
                    "background_location_remove_failed",
                    "reason=$reason",
                    completedTask.exception
                )
                retryLocationRemovalOrComplete(callback, reason, onComplete, attempt)
            } else {
                onComplete?.invoke(true)
            }
        }
    }

    private fun retryLocationRemovalOrComplete(
        callback: LocationCallback,
        reason: String,
        onComplete: ((Boolean) -> Unit)?,
        attempt: Int
    ) {
        val delay = LOCATION_REMOVAL_RETRY_DELAYS_MILLIS.getOrNull(attempt)
        if (delay == null) {
            onComplete?.invoke(false)
            return
        }
        mainHandler.postDelayed(
            {
                removeLocationUpdatesSafely(
                    callback,
                    reason,
                    attempt + 1,
                    onComplete
                )
            },
            delay
        )
    }

    private fun clearLocationRequestStartTimeout() {
        locationRequestStartTimeoutRunnable?.let(mainHandler::removeCallbacks)
        locationRequestStartTimeoutRunnable = null
    }

    private fun stopTrackingAfterArrival() {
        locationRequestGeneration += 1L
        requestSwitchToken += 1L
        val callback = activeLocationCallback
        activeLocationCallback = null
        isRequestingLocationUpdates = false
        isSwitchingLocationRequest = false
        callback?.let { removeLocationUpdatesSafely(it, "arrival_completed") }
        if (localSoundPlayer != null) {
            stopAfterLocalSound = true
        } else {
            stopSelf()
        }
    }

    private fun playLocalSound(rawResId: Int) {
        if (rawResId == 0 || serviceDestroyed || !isForegroundStarted) return
        val priority = SoundEffectPlayer.priorityFor(rawResId)
        if (localSoundPlayer != null && priority <= localSoundPriority) return

        releaseLocalSoundResources()
        if (!updateForegroundServiceTypes(includeMediaPlayback = true)) return
        if (!requestLocalAudioFocus()) {
            updateForegroundServiceTypes(includeMediaPlayback = false)
            return
        }

        val created = runCatching {
            MediaPlayer.create(this, rawResId, playbackAudioAttributes, 0)
        }.onFailure {
            AppDiagnostics.warn("background_sound_create_failed", error = it)
        }.getOrNull() ?: run {
            finishLocalSound()
            return
        }
        localSoundPriority = priority
        localSoundPlayer = created
        created.setOnCompletionListener { completedPlayer ->
            if (localSoundPlayer === completedPlayer) finishLocalSound()
        }
        created.setOnErrorListener { failedPlayer, what, extra ->
            AppDiagnostics.warn(
                "background_sound_player_error",
                "what=$what, extra=$extra"
            )
            if (localSoundPlayer === failedPlayer) finishLocalSound()
            true
        }
        runCatching { created.start() }
            .onFailure {
                AppDiagnostics.warn("background_sound_start_failed", error = it)
                if (localSoundPlayer === created) finishLocalSound()
            }
    }

    private fun requestLocalAudioFocus(): Boolean {
        val manager = audioManager ?: return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(playbackAudioAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
        val granted = runCatching {
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.onFailure {
            AppDiagnostics.warn("background_sound_focus_failed", error = it)
        }.getOrDefault(false)
        if (granted) localAudioFocusRequest = request
        return granted
    }

    private fun finishLocalSound() {
        releaseLocalSoundResources()
        if (stopAfterLocalSound) {
            stopAfterLocalSound = false
            stopSelf()
        } else if (!serviceDestroyed && isForegroundStarted) {
            updateForegroundServiceTypes(includeMediaPlayback = false)
        }
    }

    private fun releaseLocalSoundResources() {
        val player = localSoundPlayer
        localSoundPlayer = null
        localSoundPriority = 0
        player?.runCatching {
            setOnCompletionListener(null)
            setOnErrorListener(null)
            if (isPlaying) stop()
        }
        player?.runCatching { release() }

        val request = localAudioFocusRequest
        localAudioFocusRequest = null
        val manager = audioManager
        if (manager != null && request != null) {
            runCatching { manager.abandonAudioFocusRequest(request) }
                .onFailure {
                    AppDiagnostics.warn("background_sound_focus_release_failed", error = it)
                }
        }
    }

    private fun updateForegroundServiceTypes(includeMediaPlayback: Boolean): Boolean {
        if (!isForegroundStarted) return false
        return runCatching {
            val notification = buildServiceNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    if (includeMediaPlayback) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    } else {
                        0
                    }
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            AppDiagnostics.warn(
                "location_fgs_type_update_failed",
                "mediaPlayback=$includeMediaPlayback",
                it
            )
        }.isSuccess
    }

    private fun buildLocationRequest(mode: RequestMode): LocationRequest {
        return when (mode) {
            RequestMode.HIGH_ACCURACY -> LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                HIGH_ACCURACY_INTERVAL_MILLIS
            )
                .setMinUpdateIntervalMillis(HIGH_ACCURACY_MIN_INTERVAL_MILLIS)
                .setMinUpdateDistanceMeters(HIGH_ACCURACY_MIN_DISTANCE_METERS)
                .build()
            RequestMode.BALANCED -> LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                BALANCED_INTERVAL_MILLIS
            )
                .setMinUpdateIntervalMillis(BALANCED_MIN_INTERVAL_MILLIS)
                .setMinUpdateDistanceMeters(BALANCED_MIN_DISTANCE_METERS)
                .build()
        }
    }

    private fun highAccuracyEnterDistanceMeters(): Float =
        maxOf(MIN_HIGH_ACCURACY_DISTANCE_METERS, store.getLiveUpdateStartDistanceMeters().toFloat())

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
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
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
        ApproachProgressController.update(this, store, distanceMeters)
    }

    companion object {
        private const val CHANNEL_ID = "sound_monitor_channel"
        private const val NOTIFICATION_ID = 1514
        fun start(context: Context) {
            val intent = Intent(context, ForegroundDistanceMonitorService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                AppDiagnostics.warn("location_fgs_start_failed", error = it)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ForegroundDistanceMonitorService::class.java))
        }

        private const val HIGH_ACCURACY_INTERVAL_MILLIS = 5_000L
        private const val HIGH_ACCURACY_MIN_INTERVAL_MILLIS = 2_500L
        private const val HIGH_ACCURACY_MIN_DISTANCE_METERS = 5f
        private const val BALANCED_INTERVAL_MILLIS = 60_000L
        private const val BALANCED_MIN_INTERVAL_MILLIS = 30_000L
        private const val BALANCED_MIN_DISTANCE_METERS = 25f
        private const val MIN_HIGH_ACCURACY_DISTANCE_METERS = 1_500f
        private const val MIN_LIVE_WIDGET_UPDATE_INTERVAL_MILLIS = 10_000L
        private const val LOCATION_OPERATION_TIMEOUT_MILLIS = 8_000L
        private val LOCATION_REMOVAL_RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 3_000L)
        private const val LOCAL_SOUND_DUCK_VOLUME = 0.2f
    }

    private enum class RequestMode {
        BALANCED,
        HIGH_ACCURACY
    }
}
