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
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {
    companion object {
        private const val ARROW_IMAGE_FORWARD_OFFSET_DEGREES = 45f
        private const val ARRIVAL_THRESHOLD_METERS = 50f
        private const val DISTANCE_MASK_STEP_KM = 100
        private const val LOCATION_TIMEOUT_MS = 30_000L
        private const val COMPASS_SMOOTHING_TIME_CONSTANT_SECONDS = 0.14f
        private const val LOW_ACCURACY_SMOOTHING_TIME_CONSTANT_SECONDS = 0.35f
        private const val SENSOR_FILTER_TIME_CONSTANT_SECONDS = 0.12f
        private const val MAX_RENDERED_ANGULAR_SPEED_DEGREES_PER_SECOND = 720f
        private const val MAX_RAW_ANGULAR_SPEED_DEGREES_PER_SECOND = 1_080f
        private const val RAW_HEADING_JUMP_ALLOWANCE_DEGREES = 35f
        private const val MAX_SENSOR_SAMPLE_GAP_SECONDS = 1f
        private const val MAX_FRAME_DELTA_SECONDS = 0.05f
        private const val MAX_LEGACY_SAMPLE_SKEW_NANOS = 250_000_000L
        private val LOW_HEADING_ACCURACY_RADIANS = Math.toRadians(30.0).toFloat()
        private val REJECT_HEADING_ACCURACY_RADIANS = Math.toRadians(90.0).toFloat()
    }

    private lateinit var store: DestinationStore
    private lateinit var arrowView: ImageView
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
    private var renderedArrowRotationDegrees = 0f
    private var hasRenderedArrowRotation = false
    private var isCompassSmoothingEnabled = false
    private var isRequestingLocationUpdates = false
    private var currentLocation: Destination? = null
    private var destination: Destination? = null
    private var hasShownInAppArrival = false
    private var isResolvingArrivalName = false
    private var isShowingPreciseLocationPermissionGuide = false
    private var isShowingBackgroundPermissionGuide = false
    private var backgroundPermissionGuideDialog: AlertDialog? = null
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
    private val compassFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isCompassFrameLoopRunning) return
            renderCompassFrame(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private val locationTimeoutRunnable = Runnable {
        if (currentLocation == null && !store.isDestinationAnswered()) {
            showLocationUnavailableState(R.string.location_timeout)
        }
    }

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        4_000
    ).setMinUpdateIntervalMillis(2_000).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val last = result.lastLocation ?: return
            if (store.isDebugDistanceOverrideEnabled()) return
            distanceView.removeCallbacks(locationTimeoutRunnable)
            currentLocation = Destination(last.latitude, last.longitude)
            store.setLastKnownLocationFromSystem(last.latitude, last.longitude)
            store.setLastKnownHeading(headingDegrees)
            ensureDestinationExists()
            updateLocationUi(
                processLocationSideEffects = true,
                refreshWidgets = true
            )
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            continueAfterPermissionFlow()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = DestinationStore(this)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        arrowView = findViewById(R.id.arrowView)
        distanceMaskToggleButton = findViewById(R.id.distanceMaskToggleButton)
        distanceView = findViewById(R.id.distanceText)
        directionView = findViewById(R.id.directionText)
        centerContent = findViewById(R.id.centerContent)
        arrivalContent = findViewById(R.id.arrivalContent)
        arrivalNameView = findViewById(R.id.arrivalNameText)
        arrivalCoordsView = findViewById(R.id.arrivalCoordsText)
        arrowView.clearColorFilter()
        findViewById<Button>(R.id.createNextDestinationButton).setOnClickListener {
            resetDestinationProgress()
        }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
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
        BackgroundLocationUpdater.setForegroundClientActive(this, true)
        destination = store.getDestination()
        currentLocation = store.getLastKnownLocation()
        isCompassSmoothingEnabled = store.isCompassSmoothingEnabled()
        hasShownInAppArrival = store.isDestinationAnswered()
        applyDistanceMaskToggleButtonState()
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
        startCompassFrameLoop()
        registerScreenCaptureCallbackIfSupported()
    }

    override fun onPause() {
        super.onPause()
        stopCompassFrameLoop()
        stopForegroundLocationUpdates()
        BackgroundLocationUpdater.setForegroundClientActive(this, false)
        distanceView.removeCallbacks(locationTimeoutRunnable)
        sensorManager.unregisterListener(this)
        stopLoadingArrowAnimation()
        unregisterScreenCaptureCallbackIfNeeded()
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
    }

    private fun maybeLaunchWelcomeScreen(): Boolean {
        if (store.isWelcomeCompleted()) return false
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return false
        if (isShowingPreciseLocationPermissionGuide || isShowingBackgroundPermissionGuide) return false
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) &&
            store.isBackgroundLocationUpdateActive() &&
            !store.isBackgroundPermissionGuideShown()
        ) {
            if (isShowingBackgroundPermissionGuide ||
                backgroundPermissionGuideDialog?.isShowing == true
            ) {
                return
            }
            isShowingBackgroundPermissionGuide = true
            backgroundPermissionGuideDialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.background_permission_guide_title)
                .setMessage(R.string.background_permission_guide_message)
                .setCancelable(false)
                .setPositiveButton(R.string.background_permission_guide_positive) { _, _ ->
                    store.setBackgroundPermissionGuideShown(true)
                    isShowingBackgroundPermissionGuide = false
                    openAppPermissionSettings()
                }
                .setOnDismissListener {
                    isShowingBackgroundPermissionGuide = false
                    backgroundPermissionGuideDialog = null
                }
                .show()
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun startUpdatesIfPermitted() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            stopForegroundLocationUpdates()
            distanceView.removeCallbacks(locationTimeoutRunnable)
            currentLocation = null
            cachedDestinationBearingDegrees = null
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
            stopForegroundLocationUpdates()
            startLoadingArrowAnimation()
            showLocationUnavailableState(R.string.location_service_disabled)
            return
        }

        startLoadingArrowAnimation()
        BackgroundLocationUpdater.updateRegistration(this)
        fusedClient.lastLocation
            .addOnSuccessListener { last ->
                if (last != null &&
                    currentLocation == null &&
                    !store.isDebugDistanceOverrideEnabled()
                ) {
                    currentLocation = Destination(last.latitude, last.longitude)
                    store.setLastKnownLocationFromSystem(last.latitude, last.longitude)
                    store.setLastKnownHeading(headingDegrees)
                    ensureDestinationExists()
                    updateLocationUi(
                        processLocationSideEffects = true,
                        refreshWidgets = true
                    )
                }
            }
        distanceView.removeCallbacks(locationTimeoutRunnable)
        distanceView.postDelayed(locationTimeoutRunnable, LOCATION_TIMEOUT_MS)
        if (isRequestingLocationUpdates) return

        isRequestingLocationUpdates = true
        runCatching {
            fusedClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
                .addOnFailureListener {
                    isRequestingLocationUpdates = false
                    distanceView.removeCallbacks(locationTimeoutRunnable)
                    showLocationUnavailableState(R.string.location_update_start_failed)
                }
        }.onFailure {
            isRequestingLocationUpdates = false
            distanceView.removeCallbacks(locationTimeoutRunnable)
            showLocationUnavailableState(R.string.location_update_start_failed)
        }
    }

    private fun stopForegroundLocationUpdates() {
        if (!isRequestingLocationUpdates) return
        isRequestingLocationUpdates = false
        fusedClient.removeLocationUpdates(locationCallback)
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
                DestinationWidgetProvider.refreshAllWidgets(this)
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
        cachedDestinationBearingDegrees = bearing

        setArrivalStateVisible(false)
        updateArrowRotationOnly()
        distanceView.typeface = Typeface.MONOSPACE
        distanceView.text = formatDistanceForMainScreen(distance)
        directionView.setTextColor(
            ContextCompat.getColor(this, R.color.expressive_on_surface_variant)
        )
        directionView.text = if (store.isManualDistanceMaskEnabled()) {
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

        if (store.isArrivalRearmRequired()) {
            if (distance > ARRIVAL_THRESHOLD_METERS) {
                store.setArrivalRearmRequired(false)
            }
        }

        if (!store.isArrivalRearmRequired() &&
            distance <= ARRIVAL_THRESHOLD_METERS &&
            !hasShownInAppArrival
        ) {
            hasShownInAppArrival = true
            if (store.isArrivalSoundEnabled()) {
                SoundEffectPlayer.play(this, R.raw.arrival_0km)
            }
            store.setDestinationAnswered(true)
            store.setArrivalDestinationName("${target.lat}, ${target.lng}")
            NotificationHelper.cancelApproachProgress(this)
            resolveArrivalNameIfNeeded(target, shouldNotifyWhenResolved = true)
            updateArrivalUiIfNeeded()
        }
        if (refreshWidgets) {
            DestinationWidgetProvider.refreshAllWidgets(this)
        }
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
        if (target != null && arrivalName.contains(",")) {
            resolveArrivalNameIfNeeded(target)
        }
    }

    private fun resolveArrivalNameIfNeeded(
        target: Destination,
        shouldNotifyWhenResolved: Boolean = false
    ) {
        if (isResolvingArrivalName) return
        if (!store.isDestinationAnswered()) return
        val currentName = store.getArrivalDestinationName()
        if (!currentName.isNullOrBlank() && !currentName.contains(",")) return

        isResolvingArrivalName = true
        Thread {
            val resolved = ReverseGeocoder.resolve(this, target)
            runOnUiThread {
                isResolvingArrivalName = false
                val currentTarget = destination
                if (!store.isDestinationAnswered() || currentTarget == null || !sameDestination(currentTarget, target)) {
                    return@runOnUiThread
                }
                store.setArrivalDestinationName(resolved)
                arrivalNameView.text = resolved
                if (shouldNotifyWhenResolved) {
                    NotificationHelper.showDestinationReached(
                        this,
                        getString(R.string.notification_body, resolved)
                    )
                }
            }
        }.start()
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
        hasShownInAppArrival = false
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

        if (distanceMeters > startDistanceMeters) {
            NotificationHelper.cancelApproachProgress(this)
            store.clearLiveUpdateAnchorDistanceMeters()
            return
        }

        if (distanceMeters <= ARRIVAL_THRESHOLD_METERS) {
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

    private fun normalizeRotation(value: Float): Float {
        if (!value.isFinite()) return 0f
        var normalized = value % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return normalized
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
        loadingArrowAnimator = ObjectAnimator.ofFloat(arrowView, View.ROTATION, 0f, 360f).apply {
            duration = 1400L
            interpolator = LinearInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopLoadingArrowAnimation() {
        loadingArrowAnimator?.cancel()
        loadingArrowAnimator = null
        hasRenderedArrowRotation = false
    }

    private fun openAppPermissionSettings() {
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
            if (intent.resolveActivity(packageManager) == null) continue
            skipPermissionGuideOnce = true
            val launched = runCatching {
                startActivity(intent)
            }.isSuccess
            if (launched) {
                return
            }
            skipPermissionGuideOnce = false
        }
    }

    private fun registerCompass() {
        sensorManager.unregisterListener(this)
        resetCompassTracking()

        if (!store.isLegacyCompassModeEnabled()) {
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (sensor != null) {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
        } else {
            val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val mag   = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            if (accel != null && mag != null) {
                sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
                sensorManager.registerListener(this, mag,   SensorManager.SENSOR_DELAY_UI)
            } else {
                val fallback = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                if (fallback != null) {
                    sensorManager.registerListener(this, fallback, SensorManager.SENSOR_DELAY_UI)
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val estimatedAccuracy = event.values.getOrNull(4)
                    ?.takeIf { it.isFinite() && it >= 0f }
                if (hasHeadingSample &&
                    estimatedAccuracy != null &&
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
        if (hasHeadingSample &&
            lastAcceptedHeadingTimestampNanos > 0L &&
            timestampNanos > lastAcceptedHeadingTimestampNanos
        ) {
            val deltaSeconds =
                (timestampNanos - lastAcceptedHeadingTimestampNanos) / 1_000_000_000f
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
        lastAcceptedHeadingTimestampNanos = timestampNanos
        if (!hasHeadingSample) {
            headingDegrees = heading
            hasHeadingSample = true
        }
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
            updateArrowRotationOnly()
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
        updateArrowRotationOnly()
    }

    private fun updateArrowRotationOnly() {
        if (loadingArrowAnimator != null || store.isDestinationAnswered()) return
        val bearing = cachedDestinationBearingDegrees ?: return
        if (!bearing.isFinite() || !headingDegrees.isFinite()) return

        val desiredRotation = normalizeTo360(
            bearing - headingDegrees - ARROW_IMAGE_FORWARD_OFFSET_DEGREES
        )
        renderedArrowRotationDegrees = if (hasRenderedArrowRotation) {
            renderedArrowRotationDegrees + normalizeRotation(
                desiredRotation - normalizeTo360(renderedArrowRotationDegrees)
            )
        } else {
            hasRenderedArrowRotation = true
            desiredRotation
        }
        arrowView.rotation = renderedArrowRotationDegrees
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
        if (!value.isFinite()) return 0f
        val mod = value % 360f
        return if (mod < 0f) mod + 360f else mod
    }

    private fun isValidDestination(destination: Destination): Boolean {
        if (!destination.lat.isFinite() || !destination.lng.isFinite()) return false
        if (destination.lat !in -90.0..90.0) return false
        if (destination.lng !in -180.0..180.0) return false
        return true
    }
}
