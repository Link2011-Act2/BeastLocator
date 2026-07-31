package jp.linkserver.beastlocator

import android.annotation.SuppressLint
import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Typeface
import android.view.Choreographer
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.view.animation.LinearInterpolator
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {
    companion object {
        private const val ARROW_IMAGE_FORWARD_OFFSET_DEGREES = 45f
        private const val DISTANCE_MASK_STEP_KM = 100
        private const val LOCATION_TIMEOUT_MS = 30_000L
        private const val LOCATION_REQUEST_OPERATION_TIMEOUT_MILLIS = 8_000L
        private const val COMPASS_SMOOTHING_TIME_CONSTANT_SECONDS = 0.14f
        private const val LOW_ACCURACY_SMOOTHING_TIME_CONSTANT_SECONDS = 0.35f
        private const val SENSOR_FILTER_TIME_CONSTANT_SECONDS = 0.12f
        private const val MAX_RENDERED_ANGULAR_SPEED_DEGREES_PER_SECOND = 720f
        private const val ARROW_SMOOTHING_TIME_CONSTANT_SECONDS = 0.18f
        private const val MAX_ARROW_ANGULAR_SPEED_DEGREES_PER_SECOND = 360f
        private const val MAX_RAW_ANGULAR_SPEED_DEGREES_PER_SECOND = 1_080f
        private const val RAW_HEADING_JUMP_ALLOWANCE_DEGREES = 35f
        private const val MAX_SENSOR_SAMPLE_GAP_SECONDS = 1f
        private const val MAX_FRAME_DELTA_SECONDS = 0.05f
        private const val COMPASS_SETTLED_EPSILON_DEGREES = 0.05f
        private const val ARROW_SETTLED_EPSILON_DEGREES = 0.05f
        private const val HEADING_PERSIST_INTERVAL_MILLIS = 10_000L
        private const val COMPASS_FIRST_SAMPLE_TIMEOUT_MILLIS = 3_000L
        private const val MIN_WIDGET_REFRESH_INTERVAL_MILLIS = 10_000L
        private const val MAX_IDLE_WIDGET_REFRESH_INTERVAL_MILLIS = 60_000L
        private const val MIN_WIDGET_DISTANCE_DELTA_METERS = 10f
        private const val ARRIVAL_NAME_RETRY_INTERVAL_MILLIS = 5 * 60_000L
        private const val MAX_LEGACY_SAMPLE_SKEW_NANOS = 250_000_000L
        private val LOW_HEADING_ACCURACY_RADIANS = Math.toRadians(30.0).toFloat()
        private val REJECT_HEADING_ACCURACY_RADIANS = Math.toRadians(90.0).toFloat()
        private val LOCATION_REMOVAL_RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 3_000L)
    }

    private lateinit var store: DestinationStore
    private lateinit var arrowView: ImageView
    private lateinit var foregroundMonitorToggleButton: ImageButton
    private lateinit var distanceMaskToggleButton: ImageButton
    private lateinit var distanceView: TextView
    private lateinit var directionView: TextView
    private lateinit var centerContent: LinearLayout
    private lateinit var arrivalContent: LinearLayout
    private lateinit var arrivalNameView: TextView
    private lateinit var arrivalCoordsView: TextView
    private lateinit var fusedClient: com.google.android.gms.location.FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var loadingArrowAnimator: ObjectAnimator? = null

    private var headingDegrees: Float = 0f
    private var targetHeadingDegrees: Float = 0f
    private var hasHeadingSample = false
    private var isLatestHeadingLowAccuracy = false
    private var lastAcceptedHeadingTimestampNanos = 0L
    private var lastCompassFrameTimestampNanos = 0L
    private var isCompassFrameLoopRunning = false
    private var cachedDestinationBearingDegrees: Float? = null
    private var isDestinationBearingReliable = false
    private var renderedArrowRotationDegrees = 0f
    private var targetArrowRotationDegrees = 0f
    private var hasRenderedArrowRotation = false
    private var isCompassSmoothingEnabled = false
    private var isRequestingLocationUpdates = false
    private var activeLocationCallback: LocationCallback? = null
    private var locationRequestStartTimeoutRunnable: Runnable? = null
    private val locationSampleGate = LocationSampleGate()
    private var locationSessionGeneration = 0L
    private var hasReceivedFreshLocationThisSession = false
    private var currentLocationSample: LocationSample? = null
    private var lastWidgetRefreshElapsedRealtime = 0L
    private var lastWidgetDistanceMeters: Float? = null
    private var currentLocation: Destination? = null
    private var destination: Destination? = null
    private var isResolvingArrivalName = false
    private var arrivalNameRequest: ReverseGeocoder.Request? = null
    private var arrivalNameRequestGeneration = 0L
    private var lastArrivalNameAttemptElapsedRealtime = 0L
    private var isShowingPreciseLocationPermissionGuide = false
    private lateinit var backgroundPermissionGuide: BackgroundLocationPermissionGuide
    private var updateCheckOperation: AppUpdateManager.Operation? = null
    private var pendingUpdateInfo: AppUpdateInfo? = null
    private var hasStartedAutomaticUpdateCheck = false
    private lateinit var updateBannerCard: View
    private lateinit var updateBannerBody: TextView
    private var displayedUpdateTag: String? = null
    private var skipPermissionGuideOnce = false
    private var isScreenCaptureCallbackRegistered = false
    private var screenCaptureCallbackRef: Any? = null
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading  = FloatArray(3)
    private val rotationMatrix       = FloatArray(9)
    private val orientationAngles    = FloatArray(3)
    private val remappedRotationMatrix = FloatArray(9)
    private val rotationVector3 = FloatArray(3)
    private val rotationVector4 = FloatArray(4)
    private var hasAccelerometerSample = false
    private var hasMagnetometerSample = false
    private var accelerometerTimestampNanos = 0L
    private var magnetometerTimestampNanos = 0L
    private var magnetometerAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var lastPersistedHeadingElapsedRealtime = 0L
    private val compassFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isCompassFrameLoopRunning) return
            renderCompassFrame(frameTimeNanos)
            if (isCompassFrameLoopRunning) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }
    private val compassSensorWatchdogRunnable = Runnable {
        fallbackFromSilentRotationVector()
    }
    private val locationTimeoutRunnable = Runnable {
        if (!hasReceivedFreshLocationThisSession && !store.isDestinationAnswered()) {
            showLocationUnavailableState(R.string.location_timeout)
        }
    }

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        4_000
    ).setMinUpdateIntervalMillis(2_000).build()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            continueAfterPermissionFlow()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = DestinationStore(this)
        backgroundPermissionGuide = BackgroundLocationPermissionGuide(
            activity = this,
            store = store,
            onSettingsLaunched = { skipPermissionGuideOnce = true }
        )
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        arrowView = findViewById(R.id.arrowView)
        foregroundMonitorToggleButton = findViewById(R.id.foregroundMonitorToggleButton)
        distanceMaskToggleButton = findViewById(R.id.distanceMaskToggleButton)
        distanceView = findViewById(R.id.distanceText)
        directionView = findViewById(R.id.directionText)
        centerContent = findViewById(R.id.centerContent)
        arrivalContent = findViewById(R.id.arrivalContent)
        arrivalNameView = findViewById(R.id.arrivalNameText)
        arrivalCoordsView = findViewById(R.id.arrivalCoordsText)
        updateBannerCard = findViewById(R.id.updateBannerCard)
        updateBannerBody = findViewById(R.id.updateBannerBody)
        updateBannerCard.setOnClickListener { openPendingUpdateDetails() }
        findViewById<ImageButton>(R.id.updateBannerDismissButton).setOnClickListener {
            dismissPendingUpdateNotification()
        }
        arrowView.clearColorFilter()
        findViewById<Button>(R.id.createNextDestinationButton).setOnClickListener {
            resetDestinationProgress()
        }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        foregroundMonitorToggleButton.setOnClickListener {
            toggleForegroundMonitor()
        }
        distanceMaskToggleButton.setOnClickListener {
            val enabled = !store.isManualDistanceMaskEnabled()
            store.setManualDistanceMaskEnabled(enabled)
            applyDistanceMaskToggleButtonState()
            updateLocationUi(
                processLocationSideEffects = false,
                refreshWidgets = false
            )
        }
    }

    override fun onResume() {
        super.onResume()
        destination = store.getDestination()
        currentLocationSample = store.getLastKnownLocationSample()
        currentLocation = currentLocationSample?.position
        locationSampleGate.reset(currentLocationSample)
        hasReceivedFreshLocationThisSession = false
        isCompassSmoothingEnabled = store.isCompassSmoothingEnabled()
        store.getLastKnownHeading()?.let { cachedHeading ->
            headingDegrees = normalizeTo360(cachedHeading)
            targetHeadingDegrees = headingDegrees
        }
        applyDistanceMaskToggleButtonState()
        applyForegroundMonitorToggleButtonState()
        arrowView.clearColorFilter()
        updateArrivalUiIfNeeded()
        DestinationWidgetProvider.refreshAllWidgets(this)
        if (skipPermissionGuideOnce) {
            skipPermissionGuideOnce = false
            continueAfterPermissionFlow()
        } else {
            requestRuntimePermissionsIfNeeded()
        }
        syncDestinationGeofence()
        registerCompass()
        registerScreenCaptureCallbackIfSupported()
    }

    override fun onPostResume() {
        super.onPostResume()
        maybeShowUpdateBanner()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) maybeShowUpdateBanner()
    }

    override fun onPause() {
        super.onPause()
        stopCompassFrameLoop()
        stopForegroundLocationUpdates()
        distanceView.removeCallbacks(locationTimeoutRunnable)
        arrowView.removeCallbacks(compassSensorWatchdogRunnable)
        unregisterCompassSensors()
        stopLoadingArrowAnimation()
        unregisterScreenCaptureCallbackIfNeeded()
        arrivalNameRequest?.cancel()
        arrivalNameRequest = null
        isResolvingArrivalName = false
    }

    override fun onDestroy() {
        arrivalNameRequestGeneration += 1L
        arrivalNameRequest?.cancel()
        arrivalNameRequest = null
        if (::backgroundPermissionGuide.isInitialized) {
            backgroundPermissionGuide.dismiss()
        }
        if (::updateBannerCard.isInitialized) updateBannerCard.animate().cancel()
        updateCheckOperation?.cancel()
        updateCheckOperation = null
        super.onDestroy()
    }

    private fun requestRuntimePermissionsIfNeeded() {
        if (hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            // Ensure stale background registrations are stopped immediately
            // when precise location is no longer granted.
            startUpdatesIfPermitted()
            ensurePreciseLocationPermission()
            return
        }

        val required = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            required += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            required += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            required += Manifest.permission.POST_NOTIFICATIONS
        }

        if (required.isNotEmpty()) {
            permissionLauncher.launch(required.toTypedArray())
        } else {
            continueAfterPermissionFlow()
        }
    }

    private fun continueAfterPermissionFlow() {
        ensureBackgroundLocationPermission()
        if (maybeLaunchWelcomeScreen()) return
        startUpdatesIfPermitted()
        startAutomaticUpdateCheck()
    }

    private fun startAutomaticUpdateCheck() {
        if (hasStartedAutomaticUpdateCheck) return
        hasStartedAutomaticUpdateCheck = true
        updateCheckOperation = runCatching {
            AppUpdateManager.checkForUpdate(this) { result ->
                updateCheckOperation = null
                result.onSuccess { updateInfo ->
                    if (updateInfo != null &&
                        !AppUpdateManager.isUpdateNotificationDismissed(this, updateInfo.tagName)
                    ) {
                        pendingUpdateInfo = updateInfo
                        maybeShowUpdateBanner()
                    }
                }.onFailure {
                    AppDiagnostics.warn("automatic_update_check_failed", error = it)
                }
            }
        }.onFailure {
            AppDiagnostics.warn("automatic_update_check_start_failed", error = it)
        }.getOrNull()
    }

    private fun maybeShowUpdateBanner() {
        val updateInfo = pendingUpdateInfo ?: return
        if (isFinishing || isDestroyed || !hasWindowFocus()) return
        if (displayedUpdateTag == updateInfo.tagName &&
            updateBannerCard.visibility == View.VISIBLE
        ) {
            return
        }

        displayedUpdateTag = updateInfo.tagName
        updateBannerBody.text = getString(R.string.update_banner_body, updateInfo.tagName)
        updateBannerCard.animate().cancel()
        updateBannerCard.alpha = 0f
        updateBannerCard.visibility = View.VISIBLE
        updateBannerCard.post {
            if (pendingUpdateInfo?.tagName != updateInfo.tagName ||
                isFinishing || isDestroyed || !hasWindowFocus()
            ) {
                updateBannerCard.visibility = View.GONE
                displayedUpdateTag = null
                return@post
            }
            updateBannerCard.translationY =
                -(updateBannerCard.height + dpToPixels(16)).toFloat()
            updateBannerCard.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(220L)
                .start()
        }
    }

    private fun openPendingUpdateDetails() {
        val updateInfo = pendingUpdateInfo ?: return
        pendingUpdateInfo = null
        hideUpdateBanner {
            if (!isFinishing && !isDestroyed) {
                startActivity(UpdateActivity.createIntent(this, updateInfo))
            }
        }
    }

    private fun dismissPendingUpdateNotification() {
        val updateInfo = pendingUpdateInfo ?: return
        AppUpdateManager.dismissUpdateNotificationUntilNextVersion(this, updateInfo.tagName)
        pendingUpdateInfo = null
        hideUpdateBanner()
    }

    private fun hideUpdateBanner(onHidden: (() -> Unit)? = null) {
        if (!::updateBannerCard.isInitialized ||
            updateBannerCard.visibility != View.VISIBLE
        ) {
            displayedUpdateTag = null
            onHidden?.invoke()
            return
        }
        updateBannerCard.animate().cancel()
        updateBannerCard.animate()
            .translationY(-(updateBannerCard.height + dpToPixels(16)).toFloat())
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                updateBannerCard.visibility = View.GONE
                updateBannerCard.translationY = 0f
                updateBannerCard.alpha = 1f
                displayedUpdateTag = null
                onHidden?.invoke()
            }
            .start()
    }

    private fun dpToPixels(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun maybeLaunchWelcomeScreen(): Boolean {
        if (store.isWelcomeCompleted()) return false
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return false
        if (isShowingPreciseLocationPermissionGuide || backgroundPermissionGuide.isShowing) return false
        startActivity(Intent(this, WelcomeActivity::class.java))
        return true
    }

    private fun ensurePreciseLocationPermission() {
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            return
        }
        if (isShowingPreciseLocationPermissionGuide) {
            return
        }

        isShowingPreciseLocationPermissionGuide = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.precise_permission_guide_title)
            .setMessage(R.string.precise_permission_guide_message)
            .setCancelable(false)
            .setPositiveButton(R.string.precise_permission_guide_positive) { _, _ ->
                isShowingPreciseLocationPermissionGuide = false
                openAppPermissionSettings()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                isShowingPreciseLocationPermissionGuide = false
                startUpdatesIfPermitted()
            }
            .setOnDismissListener {
                isShowingPreciseLocationPermissionGuide = false
            }
            .show()
    }

    private fun ensureBackgroundLocationPermission() {
        backgroundPermissionGuide.showIfNeeded()
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun startUpdatesIfPermitted() {
        if (isRequestingLocationUpdates) return
        val sessionGeneration = ++locationSessionGeneration
        hasReceivedFreshLocationThisSession = false

        if (store.isDestinationAnswered()) {
            stopForegroundLocationUpdates(invalidateSession = false)
            distanceView.removeCallbacks(locationTimeoutRunnable)
            BackgroundLocationUpdater.updateRegistration(this)
            updateArrivalUiIfNeeded()
            return
        }

        if (store.isDebugDistanceOverrideEnabled()) {
            stopForegroundLocationUpdates(invalidateSession = false)
            currentLocationSample = store.getLastKnownLocationSample()
            currentLocation = currentLocationSample?.position
            ensureDestinationExists()
            updateLocationUi(processLocationSideEffects = true, refreshWidgets = true)
            return
        }

        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            stopForegroundLocationUpdates(invalidateSession = false)
            distanceView.removeCallbacks(locationTimeoutRunnable)
            currentLocationSample = null
            currentLocation = null
            locationSampleGate.reset()
            cachedDestinationBearingDegrees = null
            isDestinationBearingReliable = false
            startLoadingArrowAnimation()
            BackgroundLocationUpdater.updateRegistration(this)
            NotificationHelper.cancelApproachProgress(this)
            if (store.isDestinationAnswered()) {
                updateArrivalUiIfNeeded()
            } else {
                setArrivalStateVisible(false)
                distanceView.typeface = Typeface.DEFAULT
                distanceView.text = getString(
                    if (hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                        R.string.waiting_location
                    } else {
                        R.string.permission_needed
                    }
                )
                directionView.text = ""
                directionView.setTextColor(
                    ContextCompat.getColor(this, R.color.expressive_on_surface_variant)
                )
            }
            return
        }

        if (!isSystemLocationEnabled()) {
            stopForegroundLocationUpdates(invalidateSession = false)
            startLoadingArrowAnimation()
            showLocationUnavailableState(R.string.location_service_disabled)
            return
        }

        if (!BackgroundLocationUpdater.isGoogleLocationAvailable(this)) {
            stopForegroundLocationUpdates(invalidateSession = false)
            AppDiagnostics.warn("google_location_unavailable")
            showLocationUnavailableState(R.string.location_update_start_failed)
            return
        }

        startLoadingArrowAnimation()
        BackgroundLocationUpdater.updateRegistration(this)
        fusedClient.lastLocation
            .addOnSuccessListener(this) { last ->
                if (last != null && sessionGeneration == locationSessionGeneration) {
                    acceptLocation(
                        last,
                        LocationSampleSource.LAST_KNOWN,
                        sessionGeneration,
                        marksFreshSession = false
                    )
                }
            }
            .addOnFailureListener(this) {
                AppDiagnostics.warn("last_location_failed", error = it)
            }
        distanceView.removeCallbacks(locationTimeoutRunnable)
        distanceView.postDelayed(locationTimeoutRunnable, LOCATION_TIMEOUT_MS)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (sessionGeneration != locationSessionGeneration ||
                    activeLocationCallback !== this
                ) {
                    return
                }
                clearForegroundRequestStartTimeout()
                LocationBatchProcessor.processOldestFirst(
                    items = result.locations,
                    elapsedRealtimeNanos = Location::getElapsedRealtimeNanos,
                    wallTimeMillis = Location::getTime
                ) { location ->
                    acceptLocation(
                        location,
                        LocationSampleSource.CONTINUOUS,
                        sessionGeneration,
                        marksFreshSession = true
                    )
                    sessionGeneration == locationSessionGeneration &&
                        activeLocationCallback === this &&
                        !store.isDestinationAnswered()
                }
            }
        }
        activeLocationCallback = callback
        isRequestingLocationUpdates = true
        val requestTask = runCatching {
            fusedClient.requestLocationUpdates(locationRequest, callback, mainLooper)
        }.getOrElse {
            isRequestingLocationUpdates = false
            activeLocationCallback = null
            distanceView.removeCallbacks(locationTimeoutRunnable)
            AppDiagnostics.warn("foreground_location_request_threw", error = it)
            showLocationUnavailableState(R.string.location_update_start_failed)
            return
        }
        lateinit var requestStartTimeout: Runnable
        requestStartTimeout = Runnable {
            if (locationRequestStartTimeoutRunnable !== requestStartTimeout) return@Runnable
            locationRequestStartTimeoutRunnable = null
            if (sessionGeneration == locationSessionGeneration &&
                activeLocationCallback === callback
            ) {
                AppDiagnostics.warn("foreground_location_request_timeout")
                stopForegroundLocationUpdates()
                distanceView.removeCallbacks(locationTimeoutRunnable)
                showLocationUnavailableState(R.string.location_update_start_failed)
            }
        }
        locationRequestStartTimeoutRunnable = requestStartTimeout
        distanceView.postDelayed(requestStartTimeout, LOCATION_REQUEST_OPERATION_TIMEOUT_MILLIS)
        requestTask.addOnCompleteListener { task ->
            if (locationRequestStartTimeoutRunnable === requestStartTimeout) {
                distanceView.removeCallbacks(requestStartTimeout)
                locationRequestStartTimeoutRunnable = null
            }
            val stillActive = sessionGeneration == locationSessionGeneration &&
                activeLocationCallback === callback &&
                !isFinishing &&
                !isDestroyed
            if (!stillActive) {
                // Some OEM/GMS implementations can finish registration after onPause() already
                // issued its first removal. Remove again after completion to close that race.
                removeForegroundLocationCallback(callback, "stale_request_completion")
                return@addOnCompleteListener
            }
            if (!task.isSuccessful) {
                isRequestingLocationUpdates = false
                activeLocationCallback = null
                distanceView.removeCallbacks(locationTimeoutRunnable)
                AppDiagnostics.warn(
                    "foreground_location_request_failed",
                    error = task.exception
                )
                showLocationUnavailableState(R.string.location_update_start_failed)
            }
        }
    }

    private fun acceptLocation(
        location: android.location.Location,
        source: LocationSampleSource,
        sessionGeneration: Long,
        marksFreshSession: Boolean
    ) {
        if (sessionGeneration != locationSessionGeneration ||
            isFinishing ||
            isDestroyed ||
            store.isDebugDistanceOverrideEnabled()
        ) {
            return
        }
        val sample = LocationSampleFactory.fromAndroidLocation(location, source) ?: return
        if (!locationSampleGate.accept(sample)) {
            AppDiagnostics.info(
                "foreground_location_sample_rejected",
                "source=${sample.source}, accuracy=${sample.accuracyMeters}"
            )
            return
        }
        currentLocationSample = sample
        currentLocation = sample.position
        store.setLastKnownLocation(sample)
        if (marksFreshSession &&
            sample.source == LocationSampleSource.CONTINUOUS &&
            sample.ageMillis <= LocationSample.MAX_ARRIVAL_AGE_MILLIS
        ) {
            hasReceivedFreshLocationThisSession = true
            distanceView.removeCallbacks(locationTimeoutRunnable)
        }
        ensureDestinationExists()
        updateLocationUi(processLocationSideEffects = true, refreshWidgets = true)
    }

    private fun stopForegroundLocationUpdates(invalidateSession: Boolean = true) {
        if (invalidateSession) locationSessionGeneration += 1L
        val callback = activeLocationCallback
        activeLocationCallback = null
        isRequestingLocationUpdates = false
        clearForegroundRequestStartTimeout()
        if (callback != null) {
            removeForegroundLocationCallback(callback, "session_stop")
        }
    }

    private fun removeForegroundLocationCallback(
        callback: LocationCallback,
        reason: String,
        attempt: Int = 0
    ) {
        val task = runCatching {
            fusedClient.removeLocationUpdates(callback)
        }.getOrElse {
            AppDiagnostics.warn(
                "foreground_location_remove_threw",
                "reason=$reason",
                it
            )
            retryForegroundLocationRemoval(callback, reason, attempt)
            return
        }
        val handled = AtomicBoolean(false)
        lateinit var timeout: Runnable
        timeout = Runnable {
            if (!handled.compareAndSet(false, true)) return@Runnable
            AppDiagnostics.warn(
                "foreground_location_remove_timeout",
                "reason=$reason, attempt=$attempt"
            )
            retryForegroundLocationRemoval(callback, reason, attempt)
        }
        distanceView.postDelayed(timeout, LOCATION_REQUEST_OPERATION_TIMEOUT_MILLIS)
        task.addOnCompleteListener { completedTask ->
            if (!handled.compareAndSet(false, true)) return@addOnCompleteListener
            distanceView.removeCallbacks(timeout)
            if (!completedTask.isSuccessful) {
                AppDiagnostics.warn(
                    "foreground_location_remove_failed",
                    "reason=$reason",
                    completedTask.exception
                )
                retryForegroundLocationRemoval(callback, reason, attempt)
            }
        }
    }

    private fun retryForegroundLocationRemoval(
        callback: LocationCallback,
        reason: String,
        attempt: Int
    ) {
        val delay = LOCATION_REMOVAL_RETRY_DELAYS_MILLIS.getOrNull(attempt) ?: return
        distanceView.postDelayed(
            { removeForegroundLocationCallback(callback, reason, attempt + 1) },
            delay
        )
    }

    private fun clearForegroundRequestStartTimeout() {
        locationRequestStartTimeoutRunnable?.let(distanceView::removeCallbacks)
        locationRequestStartTimeoutRunnable = null
    }

    private fun showLocationUnavailableState(messageResId: Int, detailMessageResId: Int? = null) {
        setArrivalStateVisible(false)
        distanceView.typeface = Typeface.DEFAULT
        distanceView.text = getString(messageResId)
        directionView.text = detailMessageResId?.let(::getString).orEmpty()
        directionView.setTextColor(
            ContextCompat.getColor(this, R.color.expressive_on_surface_variant)
        )
    }

    private fun isSystemLocationEnabled(): Boolean {
        val manager = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun ensureDestinationExists() {
        destination = store.getDestination()
        syncDestinationGeofence()
    }

    private fun syncDestinationGeofence() {
        val target = destination ?: return
        if (!store.isDestinationAnswered() && GeofenceHelper.canRegisterDestinationGeofence(this)) {
            GeofenceHelper.registerDestinationGeofence(this, target)
        } else {
            GeofenceHelper.clearDestinationGeofence(this)
        }
    }

    private fun updateLocationUi(
        processLocationSideEffects: Boolean,
        refreshWidgets: Boolean
    ) {
        val current = currentLocation ?: return
        val target = destination ?: return
        if (!isValidDestination(current) || !isValidDestination(target)) return

        stopLoadingArrowAnimation()
        if (store.isDestinationAnswered()) {
            updateArrivalUiIfNeeded()
            if (refreshWidgets) {
                refreshWidgetsIfNeeded(force = true)
            }
            return
        }

        val (distance, bearing) = runCatching {
            GeoUtils.distanceMeters(current, target) to GeoUtils.bearingDegrees(current, target)
        }.getOrElse {
            return
        }
        if (!distance.isFinite() || !bearing.isFinite()) {
            return
        }
        val sampleAccuracyMeters = currentLocationSample
            ?.refreshedAge()
            ?.accuracyMeters
            ?: LocationSample.MAX_DISPLAY_ACCURACY_METERS
        isDestinationBearingReliable = BearingPolicy.isReliable(
            distanceMeters = distance,
            accuracyMeters = sampleAccuracyMeters
        )
        if (isDestinationBearingReliable) {
            cachedDestinationBearingDegrees = bearing
        } else {
            AppDiagnostics.info(
                "destination_bearing_held_for_accuracy",
                "distance=$distance, accuracy=$sampleAccuracyMeters"
            )
        }

        setArrivalStateVisible(false)
        updateArrowTarget()
        updateArrowConfidence()
        if (hasHeadingSample && cachedDestinationBearingDegrees != null) {
            startCompassFrameLoop()
        }
        distanceView.typeface = Typeface.MONOSPACE
        distanceView.text = formatDistanceForMainScreen(distance)
        directionView.setTextColor(
            ContextCompat.getColor(this, R.color.expressive_on_surface_variant)
        )
        directionView.text = if (store.isManualDistanceMaskEnabled() ||
            !isDestinationBearingReliable
        ) {
            getString(R.string.direction_placeholder)
        } else {
            getString(
                R.string.direction_label,
                GeoUtils.cardinalFromBearing(bearing)
            )
        }

        if (!processLocationSideEffects) {
            return
        }

        updateApproachLiveUpdate(distance)
        val activityReference = WeakReference(this)
        val arrived = currentLocationSample?.let { sample ->
            ArrivalCoordinator.observeLocation(this, store, sample, target, distance) { resolved ->
                val activity = activityReference.get() ?: return@observeLocation
                if (!activity.isFinishing &&
                    !activity.isDestroyed &&
                    activity.store.isDestinationAnswered()
                ) {
                    activity.arrivalNameView.text = resolved
                }
            }
        } ?: false
        if (arrived) {
            distanceView.removeCallbacks(locationTimeoutRunnable)
            stopForegroundLocationUpdates()
            updateArrivalUiIfNeeded()
        } else {
            DistanceEventProcessor.process(this, store, distance)
        }
        if (refreshWidgets) {
            refreshWidgetsIfNeeded(distanceMeters = distance)
        }
    }

    private fun refreshWidgetsIfNeeded(
        distanceMeters: Float? = null,
        force: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        val previousDistance = lastWidgetDistanceMeters
        val changedEnough = distanceMeters != null &&
            (previousDistance == null || abs(distanceMeters - previousDistance) >= MIN_WIDGET_DISTANCE_DELTA_METERS)
        if (!force) {
            val elapsed = now - lastWidgetRefreshElapsedRealtime
            if (elapsed < MIN_WIDGET_REFRESH_INTERVAL_MILLIS) return
            if (!changedEnough && elapsed < MAX_IDLE_WIDGET_REFRESH_INTERVAL_MILLIS) return
        }
        DestinationWidgetProvider.refreshAllWidgets(this)
        lastWidgetRefreshElapsedRealtime = now
        if (distanceMeters != null) lastWidgetDistanceMeters = distanceMeters
    }

    private fun updateArrivalUiIfNeeded() {
        if (!store.isDestinationAnswered()) {
            setArrivalStateVisible(false)
            if (currentLocation == null) {
                startLoadingArrowAnimation()
            }
            return
        }
        stopLoadingArrowAnimation()
        setArrivalStateVisible(true)
        val target = destination
        if (target != null) {
            arrivalCoordsView.visibility = android.view.View.VISIBLE
            arrivalCoordsView.text = getString(
                R.string.arrival_coords_format,
                target.lat,
                target.lng
            )
        } else {
            arrivalCoordsView.visibility = android.view.View.GONE
        }
        val arrivalName = store.getArrivalDestinationName()
        if (arrivalName.isNullOrBlank()) {
            arrivalNameView.text = getString(R.string.arrival_name_placeholder)
            if (target != null) {
                resolveArrivalNameIfNeeded(target)
            }
            return
        }
        arrivalNameView.text = arrivalName
        if (target != null && !store.isArrivalDestinationNameResolved()) {
            resolveArrivalNameIfNeeded(target)
        }
    }

    private fun resolveArrivalNameIfNeeded(
        target: Destination,
        shouldNotifyWhenResolved: Boolean = false
    ) {
        if (isResolvingArrivalName) return
        if (!store.isDestinationAnswered()) return
        if (store.isArrivalDestinationNameResolved()) return
        if (ArrivalCoordinator.isResolvingName(store.getDestinationGeneration())) return
        val now = SystemClock.elapsedRealtime()
        if (lastArrivalNameAttemptElapsedRealtime != 0L &&
            now - lastArrivalNameAttemptElapsedRealtime < ARRIVAL_NAME_RETRY_INTERVAL_MILLIS
        ) {
            return
        }

        isResolvingArrivalName = true
        lastArrivalNameAttemptElapsedRealtime = now
        val requestGeneration = ++arrivalNameRequestGeneration
        arrivalNameRequest?.cancel()
        arrivalNameRequest = ReverseGeocoder.resolveAsync(this, target) { resolved ->
            if (requestGeneration != arrivalNameRequestGeneration || isFinishing || isDestroyed) {
                return@resolveAsync
            }
            isResolvingArrivalName = false
            arrivalNameRequest = null
            val currentTarget = destination
            if (!store.isDestinationAnswered() ||
                currentTarget == null ||
                !sameDestination(currentTarget, target)
            ) {
                return@resolveAsync
            }
            val fallback = "${target.lat}, ${target.lng}"
            store.setArrivalDestinationName(resolved, resolved = resolved != fallback)
            arrivalNameView.text = resolved
            if (shouldNotifyWhenResolved) {
                NotificationHelper.updateDestinationReached(
                    this,
                    getString(R.string.notification_body, resolved)
                )
            }
        }
    }

    private fun sameDestination(a: Destination, b: Destination): Boolean {
        return a.lat == b.lat && a.lng == b.lng
    }

    private fun setArrivalStateVisible(visible: Boolean) {
        arrivalContent.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        centerContent.visibility = if (visible) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun resetDestinationProgress() {
        val fixedDestination = store.getDestination()
        destination = fixedDestination
        store.setDestinationAnswered(false)
        store.setArrivalRearmRequired(true)
        store.advanceDestinationGeneration()
        DistanceEventProcessor.reset(store.getDestinationGeneration())
        lastArrivalNameAttemptElapsedRealtime = 0L
        GeofenceHelper.registerDestinationGeofence(this, fixedDestination)
        NotificationHelper.cancelApproachProgress(this)
        setArrivalStateVisible(false)
        if (currentLocation != null) {
            updateLocationUi(
                processLocationSideEffects = true,
                refreshWidgets = true
            )
        } else {
            DestinationWidgetProvider.refreshAllWidgets(this)
        }
        startUpdatesIfPermitted()
    }

    private fun updateApproachLiveUpdate(distanceMeters: Float) {
        ApproachProgressController.update(this, store, distanceMeters)
    }

    private fun toggleForegroundMonitor() {
        if (store.isBackgroundLocationUpdateForcedBySound()) {
            Toast.makeText(
                this,
                R.string.foreground_monitor_button_forced_message,
                Toast.LENGTH_SHORT,
            ).show()
            applyForegroundMonitorToggleButtonState()
            return
        }

        if (store.isBackgroundLocationUpdateEnabled()) {
            store.setBackgroundLocationUpdateEnabled(false)
            BackgroundLocationUpdater.updateRegistration(this)
            applyForegroundMonitorToggleButtonState()
            Toast.makeText(
                this,
                R.string.foreground_monitor_button_disabled_message,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.foreground_monitor_button_enable_dialog_title)
            .setMessage(R.string.foreground_monitor_button_enable_dialog_message)
            .setPositiveButton(R.string.foreground_monitor_button_enable_dialog_positive) { _, _ ->
                store.setBackgroundLocationUpdateEnabled(true)
                BackgroundLocationUpdater.updateRegistration(this)
                applyForegroundMonitorToggleButtonState()
                ensureBackgroundLocationPermission()
                Toast.makeText(
                    this,
                    R.string.foreground_monitor_button_enabled_message,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyForegroundMonitorToggleButtonState() {
        val forced = store.isBackgroundLocationUpdateForcedBySound()
        val enabled = store.isBackgroundLocationUpdateActive()
        foregroundMonitorToggleButton.isActivated = enabled
        foregroundMonitorToggleButton.alpha = 1f
        foregroundMonitorToggleButton.imageTintList =
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (enabled) R.color.expressive_on_primary
                    else R.color.expressive_on_surface,
                ),
            )
        foregroundMonitorToggleButton.contentDescription = getString(
            when {
                forced -> R.string.foreground_monitor_button_content_description_forced
                enabled -> R.string.foreground_monitor_button_content_description_on
                else -> R.string.foreground_monitor_button_content_description_off
            },
        )
    }

    private fun normalizeRotation(value: Float): Float {
        return AngleMath.shortestDelta(0f, value)
    }

    private fun applyDistanceMaskToggleButtonState() {
        val visible = store.isDistanceMaskButtonVisible()
        distanceMaskToggleButton.visibility = if (visible) View.VISIBLE else View.GONE

        val wasMaskEnabled = store.isManualDistanceMaskEnabled()
        if (!visible && wasMaskEnabled) {
            store.setManualDistanceMaskEnabled(false)
            if (!store.isDestinationAnswered()) {
                updateLocationUi(
                    processLocationSideEffects = false,
                    refreshWidgets = false
                )
            }
        }

        val enabled = store.isManualDistanceMaskEnabled()
        distanceMaskToggleButton.setImageResource(
            if (enabled) R.drawable.ic_visibility
            else R.drawable.ic_visibility_off
        )
        distanceMaskToggleButton.contentDescription = getString(
            if (enabled) R.string.distance_mask_button_content_description_on
            else R.string.distance_mask_button_content_description_off
        )
        distanceMaskToggleButton.alpha = if (enabled) 1f else 0.68f
    }

    private fun registerScreenCaptureCallbackIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            isScreenCaptureCallbackRegistered
        ) {
            return
        }
        val registered = runCatching {
            registerScreenCaptureCallbackApi34()
        }.isSuccess
        isScreenCaptureCallbackRegistered = registered
    }

    private fun unregisterScreenCaptureCallbackIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            !isScreenCaptureCallbackRegistered
        ) {
            return
        }
        runCatching {
            unregisterScreenCaptureCallbackApi34()
        }
        isScreenCaptureCallbackRegistered = false
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerScreenCaptureCallbackApi34() {
        val callback = Activity.ScreenCaptureCallback {
            onMainScreenCaptured()
        }
        registerScreenCaptureCallback(mainExecutor, callback)
        screenCaptureCallbackRef = callback
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun unregisterScreenCaptureCallbackApi34() {
        val callback = screenCaptureCallbackRef as? Activity.ScreenCaptureCallback ?: return
        unregisterScreenCaptureCallback(callback)
        screenCaptureCallbackRef = null
    }

    private fun onMainScreenCaptured() {
        if (store.isScreenshotWarningEnabled()) {
            Toast.makeText(this, R.string.screenshot_privacy_warning, Toast.LENGTH_LONG).show()
        }
    }

    private fun formatDistanceForMainScreen(distanceMeters: Float): String {
        if (!store.isManualDistanceMaskEnabled()) {
            return GeoUtils.formatDistance(distanceMeters)
        }
        val distanceKm = distanceMeters / 1000f
        val maskedDistanceKm = if (distanceKm <= DISTANCE_MASK_STEP_KM.toFloat()) {
            DISTANCE_MASK_STEP_KM
        } else {
            (ceil(distanceKm / DISTANCE_MASK_STEP_KM).toInt()) * DISTANCE_MASK_STEP_KM
        }
        return getString(R.string.distance_masked_format_km, maskedDistanceKm)
    }

    private fun startLoadingArrowAnimation() {
        if (loadingArrowAnimator?.isRunning == true) {
            return
        }
        hasRenderedArrowRotation = false
        targetArrowRotationDegrees = 0f
        arrowView.alpha = 1f
        loadingArrowAnimator = ObjectAnimator.ofFloat(arrowView, View.ROTATION, 0f, 360f).apply {
            duration = 1400L
            interpolator = LinearInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopLoadingArrowAnimation() {
        val animator = loadingArrowAnimator ?: run {
            updateArrowConfidence()
            return
        }
        animator.cancel()
        loadingArrowAnimator = null
        hasRenderedArrowRotation = false
        targetArrowRotationDegrees = 0f
        updateArrowConfidence()
    }

    private fun openAppPermissionSettings(): Boolean {
        val intents = listOf(
            Intent("android.settings.APP_PERMISSION_SETTINGS").apply {
                putExtra("android.provider.extra.APP_PACKAGE", packageName)
                putExtra("android.provider.extra.PERMISSION_NAME", Manifest.permission.ACCESS_FINE_LOCATION)
                putExtra("android.provider.extra.PERMISSION_GROUP_NAME", "android.permission-group.LOCATION")
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
        for (intent in intents) {
            val launched = runCatching {
                startActivity(intent)
            }.isSuccess
            if (launched) {
                skipPermissionGuideOnce = true
                return true
            }
        }
        skipPermissionGuideOnce = false
        return false
    }

    private fun registerCompass() {
        arrowView.removeCallbacks(compassSensorWatchdogRunnable)
        unregisterCompassSensors()
        resetCompassTracking()

        var registered = false
        var rotationVectorPrimary = false
        if (!store.isLegacyCompassModeEnabled()) {
            registered = registerRotationVectorSensor()
            rotationVectorPrimary = registered
        }
        if (!registered) {
            registered = registerLegacyCompassSensors()
            if (!registered && store.isLegacyCompassModeEnabled()) {
                registered = registerRotationVectorSensor()
                rotationVectorPrimary = registered
            }
        }
        if (!registered) {
            AppDiagnostics.warn("compass_sensor_unavailable")
            arrowView.alpha = 0.35f
        } else if (rotationVectorPrimary) {
            arrowView.postDelayed(
                compassSensorWatchdogRunnable,
                COMPASS_FIRST_SAMPLE_TIMEOUT_MILLIS
            )
        }
    }

    private fun registerRotationVectorSensor(): Boolean {
        val sensor = runCatching {
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        }.onFailure {
            AppDiagnostics.warn("rotation_vector_lookup_failed", error = it)
        }.getOrNull() ?: return false
        return runCatching {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }.onFailure {
            AppDiagnostics.warn("rotation_vector_registration_failed", error = it)
        }.getOrDefault(false)
    }

    private fun registerLegacyCompassSensors(): Boolean {
        val accelerometer = runCatching {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }.getOrNull()
        val magnetometer = runCatching {
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        }.getOrNull()
        if (accelerometer == null || magnetometer == null) return false

        val accelerometerRegistered = runCatching {
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_UI
            )
        }.getOrDefault(false)
        val magnetometerRegistered = runCatching {
            sensorManager.registerListener(
                this,
                magnetometer,
                SensorManager.SENSOR_DELAY_UI
            )
        }.getOrDefault(false)
        if (!accelerometerRegistered || !magnetometerRegistered) {
            unregisterCompassSensors()
            AppDiagnostics.warn(
                "legacy_compass_registration_incomplete",
                "accelerometer=$accelerometerRegistered, magnetometer=$magnetometerRegistered"
            )
            return false
        }
        return true
    }

    private fun fallbackFromSilentRotationVector() {
        if (hasHeadingSample || isFinishing || isDestroyed) return
        AppDiagnostics.warn("rotation_vector_first_sample_timeout")
        unregisterCompassSensors()
        resetCompassTracking()
        if (!registerLegacyCompassSensors()) {
            AppDiagnostics.warn("compass_fallback_unavailable")
            arrowView.alpha = 0.35f
        }
    }

    private fun unregisterCompassSensors() {
        runCatching { sensorManager.unregisterListener(this) }
            .onFailure {
                AppDiagnostics.warn("compass_unregister_failed", error = it)
            }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val estimatedAccuracy = event.values.getOrNull(4)
                    ?.takeIf { it.isFinite() && it >= 0f }
                if (estimatedAccuracy != null &&
                    estimatedAccuracy > REJECT_HEADING_ACCURACY_RADIANS
                ) {
                    return
                }
                val heading = calculateHeadingFromRotationVector(event.values) ?: return
                val lowAccuracy = event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                    (estimatedAccuracy != null && estimatedAccuracy > LOW_HEADING_ACCURACY_RADIANS)
                acceptHeadingSample(heading, event.timestamp, lowAccuracy)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                applyLowPassFilter(
                    input = event.values,
                    output = accelerometerReading,
                    previousTimestampNanos = accelerometerTimestampNanos,
                    currentTimestampNanos = event.timestamp,
                    initialized = hasAccelerometerSample
                )
                hasAccelerometerSample = true
                accelerometerTimestampNanos = event.timestamp
                updateHeadingFromLegacyOrientation()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                applyLowPassFilter(
                    input = event.values,
                    output = magnetometerReading,
                    previousTimestampNanos = magnetometerTimestampNanos,
                    currentTimestampNanos = event.timestamp,
                    initialized = hasMagnetometerSample
                )
                hasMagnetometerSample = true
                magnetometerTimestampNanos = event.timestamp
                magnetometerAccuracy = event.accuracy
                updateHeadingFromLegacyOrientation()
            }
        }
    }

    private fun updateHeadingFromLegacyOrientation() {
        if (!hasAccelerometerSample || !hasMagnetometerSample) return
        if (abs(accelerometerTimestampNanos - magnetometerTimestampNanos) >
            MAX_LEGACY_SAMPLE_SKEW_NANOS
        ) {
            return
        }
        val success = SensorManager.getRotationMatrix(
            rotationMatrix, null,
            accelerometerReading,
            magnetometerReading
        )
        if (!success) return
        if (!remapRotationMatrixForDisplay()) return

        SensorManager.getOrientation(remappedRotationMatrix, orientationAngles)

        val azimuthRad = orientationAngles[0]
        if (!azimuthRad.isFinite()) return

        val heading = normalizeTo360(Math.toDegrees(azimuthRad.toDouble()).toFloat())
        acceptHeadingSample(
            heading = heading,
            timestampNanos = maxOf(accelerometerTimestampNanos, magnetometerTimestampNanos),
            lowAccuracy = magnetometerAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
        )
    }

    private fun applyLowPassFilter(
        input: FloatArray,
        output: FloatArray,
        previousTimestampNanos: Long,
        currentTimestampNanos: Long,
        initialized: Boolean
    ) {
        if (!initialized) {
            for (i in 0 until minOf(input.size, output.size)) {
                output[i] = input[i]
            }
            return
        }
        val deltaSeconds = ((currentTimestampNanos - previousTimestampNanos) / 1_000_000_000f)
            .takeIf { it > 0f && it <= MAX_SENSOR_SAMPLE_GAP_SECONDS }
        val alpha = deltaSeconds?.let {
            1f - exp(-it / SENSOR_FILTER_TIME_CONSTANT_SECONDS)
        } ?: 1f
        for (i in 0 until minOf(input.size, output.size)) {
            output[i] = output[i] + alpha * (input[i] - output[i])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magnetometerAccuracy = accuracy
        }
    }

    private fun calculateHeadingFromRotationVector(values: FloatArray): Float? {
        if (values.size < 3) return null
        val componentCount = minOf(values.size, 4)
        for (i in 0 until componentCount) {
            if (!values[i].isFinite()) return null
        }
        val safeValues = if (componentCount == 4) {
            var normSquared = 0f
            for (i in 0 until 4) {
                normSquared += values[i] * values[i]
            }
            if (!normSquared.isFinite() || normSquared !in 0.25f..2.25f) return null
            val inverseNorm = 1f / sqrt(normSquared)
            for (i in 0 until 4) {
                rotationVector4[i] = values[i] * inverseNorm
            }
            rotationVector4
        } else {
            val vectorNormSquared =
                values[0] * values[0] + values[1] * values[1] + values[2] * values[2]
            if (!vectorNormSquared.isFinite() || vectorNormSquared > 1.05f) return null
            for (i in 0 until 3) {
                rotationVector3[i] = values[i]
            }
            rotationVector3
        }

        return runCatching {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, safeValues)
            if (!remapRotationMatrixForDisplay()) return null
            SensorManager.getOrientation(remappedRotationMatrix, orientationAngles)
            val heading = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (!heading.isFinite()) return null
            normalizeTo360(heading)
        }.getOrNull()
    }

    private fun remapRotationMatrixForDisplay(): Boolean {
        val xAxis: Int
        val yAxis: Int
        when (getDisplayRotation()) {
            android.view.Surface.ROTATION_90 -> {
                xAxis = SensorManager.AXIS_Y
                yAxis = SensorManager.AXIS_MINUS_X
            }
            android.view.Surface.ROTATION_180 -> {
                xAxis = SensorManager.AXIS_MINUS_X
                yAxis = SensorManager.AXIS_MINUS_Y
            }
            android.view.Surface.ROTATION_270 -> {
                xAxis = SensorManager.AXIS_MINUS_Y
                yAxis = SensorManager.AXIS_X
            }
            else -> {
                xAxis = SensorManager.AXIS_X
                yAxis = SensorManager.AXIS_Y
            }
        }
        return SensorManager.remapCoordinateSystem(
            rotationMatrix,
            xAxis,
            yAxis,
            remappedRotationMatrix
        )
    }

    private fun acceptHeadingSample(
        heading: Float,
        timestampNanos: Long,
        lowAccuracy: Boolean
    ) {
        if (!heading.isFinite()) return
        val effectiveTimestampNanos = if (timestampNanos > lastAcceptedHeadingTimestampNanos) {
            timestampNanos
        } else {
            maxOf(
                SystemClock.elapsedRealtimeNanos(),
                lastAcceptedHeadingTimestampNanos + 1L
            )
        }
        if (hasHeadingSample &&
            lastAcceptedHeadingTimestampNanos > 0L &&
            effectiveTimestampNanos > lastAcceptedHeadingTimestampNanos
        ) {
            val deltaSeconds =
                (effectiveTimestampNanos - lastAcceptedHeadingTimestampNanos) / 1_000_000_000f
            if (deltaSeconds <= MAX_SENSOR_SAMPLE_GAP_SECONDS) {
                val maximumDelta =
                    MAX_RAW_ANGULAR_SPEED_DEGREES_PER_SECOND * deltaSeconds +
                        RAW_HEADING_JUMP_ALLOWANCE_DEGREES
                if (abs(normalizeRotation(heading - targetHeadingDegrees)) > maximumDelta) {
                    return
                }
            }
        }

        targetHeadingDegrees = heading
        isLatestHeadingLowAccuracy = lowAccuracy
        lastAcceptedHeadingTimestampNanos = effectiveTimestampNanos
        arrowView.removeCallbacks(compassSensorWatchdogRunnable)
        if (!hasHeadingSample) {
            headingDegrees = heading
            hasHeadingSample = true
        }
        updateArrowConfidence()
        val now = SystemClock.elapsedRealtime()
        if (lastPersistedHeadingElapsedRealtime == 0L ||
            now - lastPersistedHeadingElapsedRealtime >= HEADING_PERSIST_INTERVAL_MILLIS
        ) {
            store.setLastKnownHeading(heading)
            lastPersistedHeadingElapsedRealtime = now
        }
        startCompassFrameLoop()
    }

    private fun resetCompassTracking() {
        hasHeadingSample = false
        targetHeadingDegrees = headingDegrees
        lastAcceptedHeadingTimestampNanos = 0L
        lastCompassFrameTimestampNanos = 0L
        isLatestHeadingLowAccuracy = false
        hasAccelerometerSample = false
        hasMagnetometerSample = false
        accelerometerTimestampNanos = 0L
        magnetometerTimestampNanos = 0L
        magnetometerAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        hasRenderedArrowRotation = false
        targetArrowRotationDegrees = 0f
        arrowView.alpha = 0.35f
    }

    private fun startCompassFrameLoop() {
        if (isCompassFrameLoopRunning) return
        isCompassFrameLoopRunning = true
        lastCompassFrameTimestampNanos = 0L
        Choreographer.getInstance().postFrameCallback(compassFrameCallback)
    }

    private fun stopCompassFrameLoop() {
        if (!isCompassFrameLoopRunning) return
        isCompassFrameLoopRunning = false
        lastCompassFrameTimestampNanos = 0L
        Choreographer.getInstance().removeFrameCallback(compassFrameCallback)
    }

    private fun renderCompassFrame(frameTimeNanos: Long) {
        if (!hasHeadingSample) return
        if (lastCompassFrameTimestampNanos == 0L) {
            lastCompassFrameTimestampNanos = frameTimeNanos
            updateArrowTarget()
            return
        }

        val deltaSeconds = ((frameTimeNanos - lastCompassFrameTimestampNanos) / 1_000_000_000f)
            .coerceIn(0f, MAX_FRAME_DELTA_SECONDS)
        lastCompassFrameTimestampNanos = frameTimeNanos
        if (deltaSeconds <= 0f) return

        if (isCompassSmoothingEnabled) {
            val timeConstant = if (isLatestHeadingLowAccuracy) {
                LOW_ACCURACY_SMOOTHING_TIME_CONSTANT_SECONDS
            } else {
                COMPASS_SMOOTHING_TIME_CONSTANT_SECONDS
            }
            val delta = normalizeRotation(targetHeadingDegrees - headingDegrees)
            val alpha = 1f - exp(-deltaSeconds / timeConstant)
            val maximumStep = MAX_RENDERED_ANGULAR_SPEED_DEGREES_PER_SECOND * deltaSeconds
            val step = (delta * alpha).coerceIn(-maximumStep, maximumStep)
            headingDegrees = if (abs(delta) < 0.01f) {
                targetHeadingDegrees
            } else {
                normalizeTo360(headingDegrees + step)
            }
        } else {
            headingDegrees = targetHeadingDegrees
        }
        val remainingDelta = abs(normalizeRotation(targetHeadingDegrees - headingDegrees))
        val headingSettled = !isCompassSmoothingEnabled ||
            remainingDelta <= COMPASS_SETTLED_EPSILON_DEGREES
        if (headingSettled) {
            headingDegrees = targetHeadingDegrees
        }
        updateArrowTarget()
        val arrowSettled = renderArrowStep(deltaSeconds)
        if (headingSettled && arrowSettled) {
            isCompassFrameLoopRunning = false
            lastCompassFrameTimestampNanos = 0L
        }
    }

    private fun updateArrowTarget() {
        if (loadingArrowAnimator != null || store.isDestinationAnswered() || !hasHeadingSample) return
        val bearing = cachedDestinationBearingDegrees ?: return
        if (!bearing.isFinite() || !headingDegrees.isFinite()) return

        val desiredRotation = normalizeTo360(
            bearing - headingDegrees - ARROW_IMAGE_FORWARD_OFFSET_DEGREES
        )
        if (hasRenderedArrowRotation) {
            targetArrowRotationDegrees = renderedArrowRotationDegrees + normalizeRotation(
                desiredRotation - normalizeTo360(renderedArrowRotationDegrees)
            )
        } else {
            hasRenderedArrowRotation = true
            renderedArrowRotationDegrees = desiredRotation
            targetArrowRotationDegrees = desiredRotation
            arrowView.rotation = renderedArrowRotationDegrees
        }
    }

    private fun renderArrowStep(deltaSeconds: Float): Boolean {
        if (!hasRenderedArrowRotation) return true
        val delta = targetArrowRotationDegrees - renderedArrowRotationDegrees
        if (abs(delta) <= ARROW_SETTLED_EPSILON_DEGREES) {
            renderedArrowRotationDegrees = targetArrowRotationDegrees
            arrowView.rotation = renderedArrowRotationDegrees
            return true
        }
        val alpha = 1f - exp(-deltaSeconds / ARROW_SMOOTHING_TIME_CONSTANT_SECONDS)
        val maximumStep = MAX_ARROW_ANGULAR_SPEED_DEGREES_PER_SECOND * deltaSeconds
        val step = (delta * alpha).coerceIn(-maximumStep, maximumStep)
        renderedArrowRotationDegrees += step
        arrowView.rotation = renderedArrowRotationDegrees
        return abs(targetArrowRotationDegrees - renderedArrowRotationDegrees) <=
            ARROW_SETTLED_EPSILON_DEGREES
    }

    private fun updateArrowConfidence() {
        if (loadingArrowAnimator != null) {
            arrowView.alpha = 1f
            return
        }
        arrowView.alpha = when {
            !hasHeadingSample || !isDestinationBearingReliable -> 0.35f
            isLatestHeadingLowAccuracy -> 0.65f
            else -> 1f
        }
    }

    private fun Float.isFinite(): Boolean {
        return !isNaN() && !isInfinite()
    }

    private fun getDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: android.view.Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    }

    private fun normalizeTo360(value: Float): Float {
        return AngleMath.normalize360(value)
    }

    private fun isValidDestination(destination: Destination): Boolean {
        if (!destination.lat.isFinite() || !destination.lng.isFinite()) return false
        if (destination.lat !in -90.0..90.0) return false
        if (destination.lng !in -180.0..180.0) return false
        return true
    }
}
