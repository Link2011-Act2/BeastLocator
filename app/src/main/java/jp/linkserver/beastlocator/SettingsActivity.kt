package jp.linkserver.beastlocator

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class SettingsActivity : AppCompatActivity() {
    private lateinit var store: DestinationStore
    private lateinit var fixedDestinationValue: TextView
    private lateinit var debugRevisionValue: TextView
    private lateinit var debugDestinationValue: TextView
    private lateinit var debugSection: LinearLayout
    private lateinit var toggleDebugMenuButton: Button
    private lateinit var liveUpdateStartDistanceTitle: TextView
    private lateinit var liveUpdateStartDistanceHelp: TextView
    private lateinit var liveUpdateStartDistanceLabel: TextView
    private lateinit var liveUpdateStartDistanceSeek: SeekBar
    private var isSyncingBackgroundLocationUpdateSwitch = false
    private val reverseGeocodeGeneration = AtomicInteger(0)
    private var reverseGeocodeRequest: ReverseGeocoder.Request? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        store = DestinationStore(this)
        fixedDestinationValue = findViewById(R.id.fixedDestinationValue)
        debugRevisionValue = findViewById(R.id.debugRevisionValue)
        debugDestinationValue = findViewById(R.id.debugDestinationValue)
        liveUpdateStartDistanceTitle = findViewById(R.id.liveUpdateStartDistanceTitle)
        liveUpdateStartDistanceHelp = findViewById(R.id.liveUpdateStartDistanceHelp)
        liveUpdateStartDistanceLabel = findViewById(R.id.liveUpdateStartDistanceLabel)
        liveUpdateStartDistanceSeek = findViewById(R.id.liveUpdateStartDistanceSeek)

        val liveUpdateSwitch = findViewById<MaterialSwitch>(R.id.liveUpdateSwitch)
        val liveUpdateTitle = findViewById<TextView>(R.id.liveUpdateToggleTitle)
        val screenshotMaskSwitch = findViewById<MaterialSwitch>(R.id.screenshotMaskSwitch)
        val screenshotWarningSwitch = findViewById<MaterialSwitch>(R.id.screenshotWarningSwitch)
        val screenshotWarningTitle = findViewById<TextView>(R.id.screenshotWarningToggleTitle)
        val screenshotWarningHelp = findViewById<TextView>(R.id.screenshotWarningToggleHelp)
        val backgroundLocationUpdateSwitch = findViewById<MaterialSwitch>(R.id.backgroundLocationUpdateSwitch)
        val backgroundLocationUpdateTitle = findViewById<TextView>(R.id.backgroundLocationUpdateToggleTitle)
        val backgroundLocationUpdateHelp = findViewById<TextView>(R.id.backgroundLocationUpdateToggleHelp)
        val arrivalNotificationSwitch = findViewById<MaterialSwitch>(R.id.arrivalNotificationSwitch)
        val widgetBearingModeGroup = findViewById<RadioGroup>(R.id.widgetBearingModeGroup)
        val compassLegacyModeSwitch = findViewById<MaterialSwitch>(R.id.compassLegacyModeSwitch)
        
        compassLegacyModeSwitch.isChecked = store.isLegacyCompassModeEnabled()
        compassLegacyModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            store.setLegacyCompassModeEnabled(isChecked)
        }

        toggleDebugMenuButton = findViewById(R.id.toggleDebugMenuButton)
        debugSection = findViewById(R.id.debugSection)
        val debugApproachButton = findViewById<Button>(R.id.debugApproachButton)
        val debugSetDistanceButton = findViewById<Button>(R.id.debugSetDistanceButton)
        val debugResetDistanceButton = findViewById<Button>(R.id.debugResetDistanceButton)
        val debugEditDestinationButton = findViewById<Button>(R.id.debugEditDestinationButton)
        val debugResetDestinationButton = findViewById<Button>(R.id.debugResetDestinationButton)
        val currentVersionName = resolveAppVersionName()

        findViewById<TextView>(R.id.versionText).text =
            getString(R.string.version_format, currentVersionName)
        debugRevisionValue.text = getString(R.string.debug_revision_value, BuildConfig.BUILD_NUMBER)
        configureIntDevUpdateTesting(currentVersionName)

        refreshDestinationLabels()

        val initialLiveUpdateStartDistance = store.getLiveUpdateStartDistanceMeters()
            .coerceIn(LIVE_UPDATE_START_MIN_METERS, LIVE_UPDATE_START_MAX_METERS)
        liveUpdateStartDistanceSeek.progress = toLiveUpdateStartProgress(initialLiveUpdateStartDistance)
        updateLiveUpdateStartDistanceLabel(initialLiveUpdateStartDistance)
        liveUpdateStartDistanceSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val meters = fromLiveUpdateStartProgress(progress)
                store.setLiveUpdateStartDistanceMeters(meters)
                updateLiveUpdateStartDistanceLabel(meters)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        arrivalNotificationSwitch.isChecked = store.isArrivalNotificationEnabled()
        arrivalNotificationSwitch.setOnCheckedChangeListener { _, isChecked ->
            store.setArrivalNotificationEnabled(isChecked)
        }

        liveUpdateSwitch.isChecked = store.isLiveUpdateEnabled()
        applyLiveUpdateDistanceUiEnabled(liveUpdateSwitch.isChecked)
        liveUpdateSwitch.setOnCheckedChangeListener { _, isChecked ->
            store.setLiveUpdateEnabled(isChecked)
            applyLiveUpdateDistanceUiEnabled(isChecked)
            if (!isChecked) {
                NotificationHelper.cancelApproachProgress(this)
            }
        }

        screenshotMaskSwitch.isChecked = store.isDistanceMaskButtonVisible()
        screenshotMaskSwitch.setOnCheckedChangeListener { _, isChecked ->
            store.setDistanceMaskButtonVisible(isChecked)
            if (!isChecked) {
                store.setManualDistanceMaskEnabled(false)
            }
        }

        screenshotWarningSwitch.isChecked = store.isScreenshotWarningEnabled()
        screenshotWarningSwitch.setOnCheckedChangeListener { _, isChecked ->
            store.setScreenshotWarningEnabled(isChecked)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            screenshotWarningSwitch.isChecked = false
            store.setScreenshotWarningEnabled(false)
            screenshotWarningSwitch.isEnabled = false
            screenshotWarningHelp.text = getString(R.string.screenshot_warning_toggle_unsupported)
            screenshotWarningTitle.setTextColor(ContextCompat.getColor(this, R.color.expressive_outline))
            screenshotWarningHelp.setTextColor(ContextCompat.getColor(this, R.color.expressive_outline))
        }

        backgroundLocationUpdateSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingBackgroundLocationUpdateSwitch) return@setOnCheckedChangeListener
            if (store.isBackgroundLocationUpdateForcedBySound()) {
                syncBackgroundLocationUpdateToggleUi(
                    backgroundLocationUpdateSwitch,
                    backgroundLocationUpdateTitle,
                    backgroundLocationUpdateHelp
                )
                return@setOnCheckedChangeListener
            }
            if (isChecked && !store.isBackgroundLocationUpdateEnabled()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.background_location_update_warning_title)
                    .setMessage(R.string.background_location_update_warning_message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        store.setBackgroundLocationUpdateEnabled(true)
                        BackgroundLocationUpdater.updateRegistration(this)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        isSyncingBackgroundLocationUpdateSwitch = true
                        backgroundLocationUpdateSwitch.isChecked = false
                        isSyncingBackgroundLocationUpdateSwitch = false
                    }
                    .setOnCancelListener {
                        isSyncingBackgroundLocationUpdateSwitch = true
                        backgroundLocationUpdateSwitch.isChecked = false
                        isSyncingBackgroundLocationUpdateSwitch = false
                    }
                    .show()
                return@setOnCheckedChangeListener
            }

            store.setBackgroundLocationUpdateEnabled(isChecked)
            BackgroundLocationUpdater.updateRegistration(this)
        }
        syncBackgroundLocationUpdateToggleUi(
            backgroundLocationUpdateSwitch,
            backgroundLocationUpdateTitle,
            backgroundLocationUpdateHelp
        )

        applyWidgetBearingModeSelection(widgetBearingModeGroup, store.getWidgetBearingMode())
        widgetBearingModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.widgetBearingRelative -> WidgetBearingMode.RELATIVE
                else -> WidgetBearingMode.ABSOLUTE
            }
            store.setWidgetBearingMode(mode)
            DestinationWidgetProvider.refreshAllWidgets(this)
        }

        if (!NotificationHelper.isLiveUpdateSupported()) {
            liveUpdateSwitch.isChecked = false
            store.setLiveUpdateEnabled(false)
            liveUpdateSwitch.isEnabled = false
            liveUpdateTitle.text = getString(R.string.live_update_toggle_unsupported)
            liveUpdateTitle.setTextColor(ContextCompat.getColor(this, R.color.expressive_outline))
            applyLiveUpdateDistanceUiEnabled(false)
            liveUpdateStartDistanceTitle.setTextColor(ContextCompat.getColor(this, R.color.expressive_outline))
            liveUpdateStartDistanceHelp.text = getString(R.string.live_update_start_distance_unsupported)
            liveUpdateStartDistanceHelp.setTextColor(
                ContextCompat.getColor(this, R.color.expressive_outline)
            )
            debugApproachButton.isEnabled = false
            debugApproachButton.alpha = 0.5f
            debugApproachButton.text = getString(R.string.debug_start_approach_unsupported)
        }

        findViewById<LinearLayout>(R.id.experimentalSettingsCard).setOnClickListener {
            startActivity(android.content.Intent(this, ExperimentalSettingsActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.aboutAppCard).setOnClickListener {
            startActivity(android.content.Intent(this, AboutActivity::class.java))
        }

        applyDebugMenuAccessPolicy()



        debugEditDestinationButton.setOnClickListener {
            showDebugDestinationInputDialog()
        }

        debugResetDestinationButton.setOnClickListener {
            store.clearDebugDestinationOverride()
            store.setDestinationAnswered(false)
            val target = store.getDestination()
            GeofenceHelper.registerDestinationGeofence(this, target)
            NotificationHelper.cancelApproachProgress(this)
            DestinationWidgetProvider.refreshAllWidgets(this)
            refreshDestinationLabels()
            Toast.makeText(this, R.string.debug_destination_reset, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.debugReachedButton).setOnClickListener {
            val destination = store.getDestination()
            store.setDestinationAnswered(true)
            val coordinateText = "${destination.lat}, ${destination.lng}"
            store.setArrivalDestinationName(coordinateText, resolved = false)
            GeofenceHelper.clearDestinationGeofence(this)
            NotificationHelper.cancelApproachProgress(this)
            NotificationHelper.showDestinationReached(
                this,
                getString(R.string.notification_body, coordinateText)
            )
            DestinationWidgetProvider.refreshAllWidgets(this)
            resolveDebugArrivalName(destination)
        }

        debugApproachButton.setOnClickListener {
            val destination = store.getDestination()
            val current = store.getLastKnownLocation()
            if (current == null) {
                Toast.makeText(this, R.string.debug_missing_location, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val distance = GeoUtils.distanceMeters(current, destination)
            if (distance <= ARRIVAL_THRESHOLD_METERS) {
                Toast.makeText(this, R.string.debug_approach_out_of_range, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store.setLiveUpdateAnchorDistanceMeters(distance)
            NotificationHelper.showApproachProgress(this, distance, 0)
        }

        debugSetDistanceButton.setOnClickListener {
            showDebugDistanceInputDialog()
        }

        debugResetDistanceButton.setOnClickListener {
            store.clearDebugDistanceOverride()
            DestinationWidgetProvider.refreshAllWidgets(this)
            Toast.makeText(this, R.string.debug_reset_distance_done, Toast.LENGTH_SHORT).show()
        }

        findViewById<ImageButton>(R.id.settingsBackButton).setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        applyDebugMenuAccessPolicy()
        syncBackgroundLocationUpdateToggleUi(
            findViewById(R.id.backgroundLocationUpdateSwitch),
            findViewById(R.id.backgroundLocationUpdateToggleTitle),
            findViewById(R.id.backgroundLocationUpdateToggleHelp)
        )
    }

    override fun onDestroy() {
        reverseGeocodeGeneration.incrementAndGet()
        reverseGeocodeRequest?.cancel()
        reverseGeocodeRequest = null
        super.onDestroy()
    }

    private fun resolveDebugArrivalName(expectedDestination: Destination) {
        val generation = reverseGeocodeGeneration.incrementAndGet()
        reverseGeocodeRequest?.cancel()
        reverseGeocodeRequest = ReverseGeocoder.resolveAsync(
            applicationContext,
            expectedDestination
        ) { destinationText ->
            if (isFinishing || isDestroyed || generation != reverseGeocodeGeneration.get()) {
                return@resolveAsync
            }
            if (!store.isDestinationAnswered() || store.getDestination() != expectedDestination) {
                return@resolveAsync
            }
            val coordinateText = "${expectedDestination.lat}, ${expectedDestination.lng}"
            store.setArrivalDestinationName(
                destinationText,
                resolved = destinationText != coordinateText
            )
            NotificationHelper.showDestinationReached(
                this,
                getString(R.string.notification_body, destinationText)
            )
            DestinationWidgetProvider.refreshAllWidgets(this)
        }
    }

    private fun syncBackgroundLocationUpdateToggleUi(
        toggle: MaterialSwitch,
        title: TextView,
        help: TextView
    ) {
        val forced = store.isBackgroundLocationUpdateForcedBySound()
        isSyncingBackgroundLocationUpdateSwitch = true
        toggle.isChecked = if (forced) true else store.isBackgroundLocationUpdateEnabled()
        toggle.isEnabled = !forced
        isSyncingBackgroundLocationUpdateSwitch = false

        help.text = getString(
            if (forced) R.string.background_location_update_help_forced
            else R.string.background_location_update_help
        )
        title.setTextColor(ContextCompat.getColor(this, R.color.expressive_on_surface))
        BackgroundLocationUpdater.updateRegistration(this)
    }

    private fun showDebugDestinationInputDialog() {
        val currentDestination = store.getDestination()
        val latInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = getString(R.string.manual_destination_lat_hint)
            setText(formatCoordinate(currentDestination.lat))
            setSelection(text.length)
        }
        val lngInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = getString(R.string.manual_destination_lng_hint)
            setText(formatCoordinate(currentDestination.lng))
            setSelection(text.length)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = (24 * resources.displayMetrics.density).toInt()
            setPadding(horizontal, 8, horizontal, 0)
            addView(
                latInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                lngInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (10 * resources.displayMetrics.density).toInt() }
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.debug_destination_edit_title)
            .setMessage(R.string.debug_destination_edit_message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val lat = latInput.text.toString().trim().toDoubleOrNull()
                val lng = lngInput.text.toString().trim().toDoubleOrNull()
                if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite() ||
                    lat !in -90.0..90.0 || lng !in -180.0..180.0
                ) {
                    Toast.makeText(this, R.string.manual_destination_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val destination = Destination(lat, lng)
                store.setDebugDestinationOverride(destination)
                GeofenceHelper.registerDestinationGeofence(this, destination)
                NotificationHelper.cancelApproachProgress(this)
                DestinationWidgetProvider.refreshAllWidgets(this)
                refreshDestinationLabels()
                Toast.makeText(this, R.string.debug_destination_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDebugDistanceInputDialog() {
        val destination = store.getDestination()
        val current = store.getLastKnownLocation()
        val currentDistanceMeters = if (current != null) {
            GeoUtils.distanceMeters(current, destination).toInt()
        } else {
            1000
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.debug_set_distance_hint)
            setText(currentDistanceMeters.toString())
            setSelection(text.length)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = (24 * resources.displayMetrics.density).toInt()
            setPadding(horizontal, 8, horizontal, 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.debug_set_distance_title)
            .setMessage(R.string.debug_set_distance_message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val distanceMeters = input.text.toString().trim().toDoubleOrNull()
                if (distanceMeters == null || !distanceMeters.isFinite() ||
                    distanceMeters < 0.0 || distanceMeters > 20_000_000.0
                ) {
                    Toast.makeText(this, R.string.debug_set_distance_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val mockCurrent = buildCalibratedOffsetFromDestination(
                    destination,
                    targetDistanceMeters = distanceMeters.toFloat(),
                    bearingDegrees = 180.0
                )
                val previousDistanceMeters = store.getLastKnownLocation()?.let {
                    GeoUtils.distanceMeters(it, destination)
                }
                val appliedDistanceMeters = GeoUtils.distanceMeters(mockCurrent, destination)
                store.setDebugDistanceOverrideLocation(mockCurrent.lat, mockCurrent.lng)
                if (distanceMeters > ARRIVAL_THRESHOLD_METERS) {
                    store.setArrivalRearmRequired(false)
                    store.setDestinationAnswered(false)
                }
                triggerDebugDistanceSounds(previousDistanceMeters, appliedDistanceMeters)
                DestinationWidgetProvider.refreshAllWidgets(this)
                Toast.makeText(
                    this,
                    getString(R.string.debug_set_distance_done, distanceMeters.toInt()),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun offsetFromDestination(
        destination: Destination,
        distanceMeters: Double,
        bearingDegrees: Double
    ): Destination {
        val earthRadius = 6_371_000.0
        val angularDistance = distanceMeters / earthRadius
        val bearing = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(destination.lat)
        val lon1 = Math.toRadians(destination.lng)

        val lat2 = asin(
            sin(lat1) * cos(angularDistance) +
                cos(lat1) * sin(angularDistance) * cos(bearing)
        )
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )

        return Destination(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    private fun buildCalibratedOffsetFromDestination(
        destination: Destination,
        targetDistanceMeters: Float,
        bearingDegrees: Double
    ): Destination {
        if (targetDistanceMeters <= 0f) return destination

        var estimatedMeters = targetDistanceMeters.toDouble()
        var candidate = offsetFromDestination(destination, estimatedMeters, bearingDegrees)
        repeat(5) {
            val actualMeters = GeoUtils.distanceMeters(candidate, destination)
            val errorMeters = targetDistanceMeters - actualMeters
            if (kotlin.math.abs(errorMeters) < 1f) {
                return candidate
            }
            val safeActual = actualMeters.coerceAtLeast(1f)
            estimatedMeters = (estimatedMeters * (targetDistanceMeters / safeActual)).coerceAtLeast(0.0)
            candidate = offsetFromDestination(destination, estimatedMeters, bearingDegrees)
        }
        return candidate
    }

    private fun triggerDebugDistanceSounds(previousDistanceMeters: Float?, currentDistanceMeters: Float) {
        if (store.isDistance114514SoundEnabled() &&
            entered114514Range(previousDistanceMeters, currentDistanceMeters)
        ) {
            SoundEffectPlayer.play(this, R.raw.distance_114514km)
        }
        if (store.isDistanceIntervalSoundEnabled() &&
            crossedIntervalBoundary(previousDistanceMeters, currentDistanceMeters)
        ) {
            SoundEffectPlayer.play(this, R.raw.distance_interval_kankaku)
        }
    }

    private fun entered114514Range(previousDistanceMeters: Float?, currentDistanceMeters: Float): Boolean {
        val previous = previousDistanceMeters ?: return false
        return previous > DEBUG_114514_ENTER_THRESHOLD_METERS &&
            currentDistanceMeters <= DEBUG_114514_ENTER_THRESHOLD_METERS
    }

    private fun crossedIntervalBoundary(previousDistanceMeters: Float?, currentDistanceMeters: Float): Boolean {
        if (previousDistanceMeters == null) return false
        val intervalMeters = store.getDistanceIntervalSoundMeters().coerceIn(100, 5000).toFloat()
        val previousBucket = (previousDistanceMeters / intervalMeters).toInt()
        val currentBucket = (currentDistanceMeters / intervalMeters).toInt()
        return currentBucket < previousBucket
    }

    private fun refreshDestinationLabels() {
        val destination = store.getDestination()
        val latText = formatCoordinate(destination.lat)
        val lngText = formatCoordinate(destination.lng)
        fixedDestinationValue.text = getString(R.string.fixed_destination_value_format, latText, lngText)
        debugDestinationValue.text = if (store.isDebugDestinationOverrideEnabled()) {
            getString(R.string.debug_destination_mode_override, latText, lngText)
        } else {
            getString(R.string.debug_destination_mode_default, latText, lngText)
        }
    }

    private fun formatCoordinate(value: Double): String {
        return String.format(Locale.US, "%.6f", value)
    }

    private fun updateLiveUpdateStartDistanceLabel(distanceMeters: Int) {
        liveUpdateStartDistanceLabel.text = if (distanceMeters >= 1000) {
            getString(
                R.string.live_update_start_distance_value_km,
                distanceMeters / 1000f
            )
        } else {
            getString(R.string.live_update_start_distance_value_m, distanceMeters)
        }
    }

    private fun toLiveUpdateStartProgress(distanceMeters: Int): Int {
        return ((distanceMeters - LIVE_UPDATE_START_MIN_METERS) / LIVE_UPDATE_START_STEP_METERS)
            .coerceIn(0, LIVE_UPDATE_START_MAX_PROGRESS)
    }

    private fun fromLiveUpdateStartProgress(progress: Int): Int {
        val clamped = progress.coerceIn(0, LIVE_UPDATE_START_MAX_PROGRESS)
        return LIVE_UPDATE_START_MIN_METERS + (clamped * LIVE_UPDATE_START_STEP_METERS)
    }

    private fun applyLiveUpdateDistanceUiEnabled(enabled: Boolean) {
        liveUpdateStartDistanceSeek.isEnabled = enabled
        liveUpdateStartDistanceTitle.alpha = if (enabled) 1f else 0.5f
        liveUpdateStartDistanceHelp.alpha = if (enabled) 1f else 0.5f
        liveUpdateStartDistanceLabel.alpha = if (enabled) 1f else 0.5f
        liveUpdateStartDistanceSeek.alpha = if (enabled) 1f else 0.5f
    }

    private fun applyDebugMenuVisibility(
        debugSection: LinearLayout,
        toggleButton: Button,
        visible: Boolean
    ) {
        debugSection.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        toggleButton.text = getString(
            if (visible) R.string.debug_toggle_hide else R.string.debug_toggle_show
        )
    }

    private fun applyDebugMenuAccessPolicy() {
        val defaultDebugMenuEnabled = !isStableChannel()
        val canAccessDebugMenuToggle =
            defaultDebugMenuEnabled || store.isStableDebugMenuUnlockEnabled()
        if (canAccessDebugMenuToggle) {
            toggleDebugMenuButton.visibility = android.view.View.VISIBLE
            applyDebugMenuVisibility(debugSection, toggleDebugMenuButton, store.isDebugMenuVisible())
            toggleDebugMenuButton.setOnClickListener {
                val next = !store.isDebugMenuVisible()
                store.setDebugMenuVisible(next)
                applyDebugMenuVisibility(debugSection, toggleDebugMenuButton, next)
            }
        } else {
            store.setDebugMenuVisible(false)
            applyDebugMenuVisibility(debugSection, toggleDebugMenuButton, false)
            toggleDebugMenuButton.visibility = android.view.View.GONE
            toggleDebugMenuButton.setOnClickListener(null)
        }
    }

    private fun configureIntDevUpdateTesting(currentVersionName: String) {
        val container = findViewById<LinearLayout>(R.id.debugUpdateTestingContainer)
        val isIntDev = ReleaseChannelDetector.detect(currentVersionName) == ReleaseChannel.INTDEV
        container.visibility = if (isIntDev) android.view.View.VISIBLE else android.view.View.GONE
        if (!isIntDev) return

        val showLatestSwitch =
            findViewById<MaterialSwitch>(R.id.debugUpdateShowLatestSwitch)
        val versionOverrideEdit = findViewById<EditText>(R.id.debugUpdateCurrentVersionEdit)
        showLatestSwitch.isChecked =
            AppUpdateManager.isShowLatestReleaseForTestingEnabled(this)
        showLatestSwitch.setOnCheckedChangeListener { _, enabled ->
            AppUpdateManager.setShowLatestReleaseForTestingEnabled(this, enabled)
        }
        versionOverrideEdit.hint = currentVersionName
        versionOverrideEdit.setText(
            AppUpdateManager.getUpdateCurrentVersionOverrideForTesting(this)
        )
        versionOverrideEdit.doAfterTextChanged { value ->
            AppUpdateManager.setUpdateCurrentVersionOverrideForTesting(
                this,
                value?.toString().orEmpty()
            )
        }
        findViewById<TextView>(R.id.debugUpdateCurrentVersionHelp).text = getString(
            R.string.debug_update_current_version_help,
            currentVersionName
        )
    }

    private fun isStableChannel(): Boolean {
        // Unknown versions remain conservative: only channels recognized by the shared
        // schedulernittc-compatible detector expose debug controls automatically.
        return !ReleaseChannelDetector.detect(resolveAppVersionName())
            .exposesDebugControlsByDefault
    }

    private fun resolveAppVersionName(): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun applyWidgetBearingModeSelection(group: RadioGroup, mode: WidgetBearingMode) {
        val id = when (mode) {
            WidgetBearingMode.ABSOLUTE -> R.id.widgetBearingAbsolute
            WidgetBearingMode.RELATIVE -> R.id.widgetBearingRelative
        }
        group.check(id)
    }



    companion object {
        private const val ARRIVAL_THRESHOLD_METERS = 50f
        private const val LIVE_UPDATE_START_MIN_METERS = 200
        private const val LIVE_UPDATE_START_MAX_METERS = 5000
        private const val LIVE_UPDATE_START_STEP_METERS = 100
        private const val LIVE_UPDATE_START_MAX_PROGRESS =
            (LIVE_UPDATE_START_MAX_METERS - LIVE_UPDATE_START_MIN_METERS) / LIVE_UPDATE_START_STEP_METERS
        private const val DEBUG_TARGET_114514_METERS = 114_514f
        private const val DEBUG_DISTANCE_MATCH_TOLERANCE_METERS = 80f
        private const val DEBUG_114514_ENTER_THRESHOLD_METERS =
            DEBUG_TARGET_114514_METERS + DEBUG_DISTANCE_MATCH_TOLERANCE_METERS
    }
}
