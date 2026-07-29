package jp.linkserver.beastlocator

import android.content.Context
import android.content.SharedPreferences

data class Destination(val lat: Double, val lng: Double)

enum class WidgetBearingMode(val prefValue: String) {
    ABSOLUTE("absolute"),
    RELATIVE("relative");

    companion object {
        fun fromPref(value: String?): WidgetBearingMode {
            return entries.firstOrNull { it.prefValue == value } ?: ABSOLUTE
        }
    }
}



class DestinationStore(context: Context) {
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    private val prefs = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
    private val runtimePrefs = context.getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migratePreferencesIfNeeded()
    }

    fun getRadiusKm(): Float = prefs.getFloat(KEY_RADIUS_KM, 5f)

    fun setRadiusKm(value: Float) {
        prefs.edit().putFloat(KEY_RADIUS_KM, value).apply()
    }

    fun getDestination(): Destination {
        if (isDebugDestinationOverrideEnabled() &&
            runtimePrefs.contains(KEY_DEBUG_DEST_OVERRIDE_LAT) &&
            runtimePrefs.contains(KEY_DEBUG_DEST_OVERRIDE_LNG)
        ) {
            return Destination(
                java.lang.Double.longBitsToDouble(runtimePrefs.getLong(KEY_DEBUG_DEST_OVERRIDE_LAT, 0L)),
                java.lang.Double.longBitsToDouble(runtimePrefs.getLong(KEY_DEBUG_DEST_OVERRIDE_LNG, 0L))
            )
        }
        return getDefaultDestination()
    }

    fun setDestination(destination: Destination) {
        // Keep this method for compatibility with older call sites.
        // Destination editing is intended to be exposed only via debug UI.
        setDebugDestinationOverride(destination)
    }

    fun setDebugDestinationOverride(destination: Destination) {
        if (!destination.isValidCoordinate()) return
        runtimePrefs.edit()
            .putLong(KEY_DEBUG_DEST_OVERRIDE_LAT, java.lang.Double.doubleToRawLongBits(destination.lat))
            .putLong(KEY_DEBUG_DEST_OVERRIDE_LNG, java.lang.Double.doubleToRawLongBits(destination.lng))
            .putBoolean(KEY_DEBUG_DEST_OVERRIDE_ENABLED, true)
            .putBoolean(KEY_DEST_ANSWERED, false)
            .putBoolean(KEY_ARRIVAL_REARM_REQUIRED, false)
            .remove(KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS)
            .remove(KEY_ARRIVAL_DESTINATION_NAME)
            .remove(KEY_ARRIVAL_DESTINATION_NAME_RESOLVED)
            .putLong(KEY_DESTINATION_GENERATION, getDestinationGeneration() + 1L)
            .apply()
        SharedArrivalConfirmation.reset()
    }

    fun clearDestination() {
        clearDebugDestinationOverride()
    }

    fun clearDebugDestinationOverride() {
        runtimePrefs.edit()
            .remove(KEY_DEBUG_DEST_OVERRIDE_LAT)
            .remove(KEY_DEBUG_DEST_OVERRIDE_LNG)
            .putBoolean(KEY_DEBUG_DEST_OVERRIDE_ENABLED, false)
            .remove(KEY_DEST_ANSWERED)
            .putBoolean(KEY_ARRIVAL_REARM_REQUIRED, false)
            .remove(KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS)
            .remove(KEY_ARRIVAL_DESTINATION_NAME)
            .remove(KEY_ARRIVAL_DESTINATION_NAME_RESOLVED)
            .putLong(KEY_DESTINATION_GENERATION, getDestinationGeneration() + 1L)
            .apply()
        SharedArrivalConfirmation.reset()
    }

    fun isDebugDestinationOverrideEnabled(): Boolean =
        runtimePrefs.getBoolean(KEY_DEBUG_DEST_OVERRIDE_ENABLED, false)

    fun getDefaultDestination(): Destination = Destination(DEFAULT_DEST_LAT, DEFAULT_DEST_LNG)

    fun isDestinationAnswered(): Boolean = runtimePrefs.getBoolean(KEY_DEST_ANSWERED, false)

    fun setDestinationAnswered(answered: Boolean) {
        runtimePrefs.edit().putBoolean(KEY_DEST_ANSWERED, answered).apply()
        clearLiveUpdateAnchorDistanceMeters()
        if (!answered) {
            runtimePrefs.edit()
                .remove(KEY_ARRIVAL_DESTINATION_NAME)
                .remove(KEY_ARRIVAL_DESTINATION_NAME_RESOLVED)
                .apply()
            SharedArrivalConfirmation.reset()
            DistanceEventProcessor.reset(getDestinationGeneration())
        }
    }

    fun markDestinationAnswered(name: String, resolved: Boolean) {
        runtimePrefs.edit()
            .putBoolean(KEY_DEST_ANSWERED, true)
            .putString(KEY_ARRIVAL_DESTINATION_NAME, name)
            .putBoolean(KEY_ARRIVAL_DESTINATION_NAME_RESOLVED, resolved)
            .remove(KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS)
            .apply()
    }

    fun getArrivalDestinationName(): String? = runtimePrefs.getString(KEY_ARRIVAL_DESTINATION_NAME, null)

    fun setArrivalDestinationName(name: String, resolved: Boolean = true) {
        runtimePrefs.edit()
            .putString(KEY_ARRIVAL_DESTINATION_NAME, name)
            .putBoolean(KEY_ARRIVAL_DESTINATION_NAME_RESOLVED, resolved)
            .apply()
    }

    fun isArrivalDestinationNameResolved(): Boolean =
        runtimePrefs.getBoolean(KEY_ARRIVAL_DESTINATION_NAME_RESOLVED, false)

    fun isArrivalRearmRequired(): Boolean =
        runtimePrefs.getBoolean(KEY_ARRIVAL_REARM_REQUIRED, false)

    fun setArrivalRearmRequired(required: Boolean) {
        runtimePrefs.edit().putBoolean(KEY_ARRIVAL_REARM_REQUIRED, required).apply()
        if (required) SharedArrivalConfirmation.reset()
    }

    fun setLastKnownLocation(lat: Double, lng: Double) {
        if (!Destination(lat, lng).isValidCoordinate()) return
        runtimePrefs.edit()
            .putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(KEY_LAST_LNG, java.lang.Double.doubleToRawLongBits(lng))
            .putFloat(KEY_LAST_LOCATION_ACCURACY, LocationSample.MAX_DISPLAY_ACCURACY_METERS)
            .putLong(KEY_LAST_LOCATION_TIME, System.currentTimeMillis())
            .apply()
    }

    fun setLastKnownLocation(sample: LocationSample, force: Boolean = false): Boolean {
        if (!sample.isUsableForDisplay()) return false
        val previousStoredTime = runtimePrefs.getLong(KEY_LAST_LOCATION_TIME, 0L)
        if (!force &&
            previousStoredTime > 0L &&
            previousStoredTime <= System.currentTimeMillis() + 10_000L &&
            sample.wallTimeMillis <= previousStoredTime
        ) {
            return false
        }
        val previous = getLastKnownLocationSample()
        if (!force && previous != null) {
            val elapsed = sample.wallTimeMillis - previous.wallTimeMillis
            val movedMeters = runCatching {
                GeoUtils.distanceMeters(previous.position, sample.position)
            }.getOrDefault(Float.MAX_VALUE)
            if (elapsed in 0 until MIN_LOCATION_PERSIST_INTERVAL_MILLIS &&
                movedMeters < MIN_LOCATION_PERSIST_DISTANCE_METERS
            ) {
                return false
            }
        }
        runtimePrefs.edit()
            .putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(sample.position.lat))
            .putLong(KEY_LAST_LNG, java.lang.Double.doubleToRawLongBits(sample.position.lng))
            .putFloat(KEY_LAST_LOCATION_ACCURACY, sample.accuracyMeters)
            .putLong(KEY_LAST_LOCATION_TIME, sample.wallTimeMillis)
            .apply()
        return true
    }

    fun setLastKnownLocationFromSystem(lat: Double, lng: Double): Boolean {
        if (isDebugDistanceOverrideEnabled()) {
            return false
        }
        setLastKnownLocation(lat, lng)
        return true
    }

    fun clearLastKnownLocation() {
        runtimePrefs.edit()
            .remove(KEY_LAST_LAT)
            .remove(KEY_LAST_LNG)
            .remove(KEY_LAST_LOCATION_ACCURACY)
            .remove(KEY_LAST_LOCATION_TIME)
            .remove(KEY_LAST_HEADING)
            .remove(KEY_LAST_HEADING_TIME)
            .apply()
    }

    fun isDebugDistanceOverrideEnabled(): Boolean =
        runtimePrefs.getBoolean(KEY_DEBUG_DISTANCE_OVERRIDE_ENABLED, false)

    fun setDebugDistanceOverrideLocation(lat: Double, lng: Double) {
        if (!Destination(lat, lng).isValidCoordinate()) return
        runtimePrefs.edit()
            .putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(KEY_LAST_LNG, java.lang.Double.doubleToRawLongBits(lng))
            .putFloat(KEY_LAST_LOCATION_ACCURACY, 0f)
            .putLong(KEY_LAST_LOCATION_TIME, System.currentTimeMillis())
            .putBoolean(KEY_DEBUG_DISTANCE_OVERRIDE_ENABLED, true)
            .apply()
    }

    fun clearDebugDistanceOverride() {
        runtimePrefs.edit()
            .remove(KEY_LAST_LAT)
            .remove(KEY_LAST_LNG)
            .remove(KEY_LAST_LOCATION_ACCURACY)
            .remove(KEY_LAST_LOCATION_TIME)
            .remove(KEY_LAST_HEADING)
            .remove(KEY_LAST_HEADING_TIME)
            .putBoolean(KEY_DEBUG_DISTANCE_OVERRIDE_ENABLED, false)
            .apply()
    }

    fun setLastKnownHeading(headingDegrees: Float) {
        if (!headingDegrees.isFinite()) return
        runtimePrefs.edit()
            .putFloat(KEY_LAST_HEADING, normalizeHeading(headingDegrees))
            .putLong(KEY_LAST_HEADING_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getLastKnownHeading(): Float? {
        if (!runtimePrefs.contains(KEY_LAST_HEADING) || !runtimePrefs.contains(KEY_LAST_HEADING_TIME)) {
            return null
        }
        val timestamp = runtimePrefs.getLong(KEY_LAST_HEADING_TIME, 0L)
        val age = System.currentTimeMillis() - timestamp
        if (age !in 0L..MAX_HEADING_AGE_MILLIS) return null
        return runtimePrefs.getFloat(KEY_LAST_HEADING, 0f).takeIf { it.isFinite() }
    }

    fun isLiveUpdateEnabled(): Boolean = prefs.getBoolean(KEY_LIVE_UPDATE_ENABLED, true)

    fun setLiveUpdateEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE_UPDATE_ENABLED, enabled).apply()
        if (!enabled) {
            clearLiveUpdateAnchorDistanceMeters()
        }
    }

    fun getLiveUpdateStartDistanceMeters(): Int =
        prefs.getInt(KEY_LIVE_UPDATE_START_DISTANCE_METERS, DEFAULT_LIVE_UPDATE_START_DISTANCE_METERS)

    fun setLiveUpdateStartDistanceMeters(distanceMeters: Int) {
        val clamped = distanceMeters.coerceIn(
            MIN_LIVE_UPDATE_START_DISTANCE_METERS,
            MAX_LIVE_UPDATE_START_DISTANCE_METERS
        )
        prefs.edit().putInt(KEY_LIVE_UPDATE_START_DISTANCE_METERS, clamped).apply()
    }

    fun getLiveUpdateAnchorDistanceMeters(): Float? {
        if (!runtimePrefs.contains(KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS)) return null
        return runtimePrefs.getFloat(KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS, 0f)
    }

    fun setLiveUpdateAnchorDistanceMeters(distanceMeters: Float) {
        val clamped = distanceMeters.coerceAtLeast(0f)
        if (getLiveUpdateAnchorDistanceMeters() == clamped) return
        runtimePrefs.edit()
            .putFloat(KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS, clamped)
            .apply()
    }

    fun clearLiveUpdateAnchorDistanceMeters() {
        if (!runtimePrefs.contains(KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS)) return
        runtimePrefs.edit().remove(KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS).apply()
    }

    fun isArrivalNotificationEnabled(): Boolean =
        prefs.getBoolean(KEY_ARRIVAL_NOTIFICATION_ENABLED, true)

    fun setArrivalNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ARRIVAL_NOTIFICATION_ENABLED, enabled).apply()
    }

    fun isBackgroundLocationUpdateEnabled(): Boolean =
        prefs.getBoolean(KEY_WIDGET_BACKGROUND_UPDATE_ENABLED, false)

    fun setBackgroundLocationUpdateEnabled(enabled: Boolean) {
        val wasEnabled = isBackgroundLocationUpdateEnabled()
        prefs.edit().putBoolean(KEY_WIDGET_BACKGROUND_UPDATE_ENABLED, enabled).apply()
        if (enabled && !wasEnabled) {
            setBackgroundPermissionGuideShown(false)
        }
    }

    fun getWidgetBearingMode(): WidgetBearingMode {
        return WidgetBearingMode.fromPref(prefs.getString(KEY_WIDGET_BEARING_MODE, null))
    }

    fun setWidgetBearingMode(mode: WidgetBearingMode) {
        prefs.edit().putString(KEY_WIDGET_BEARING_MODE, mode.prefValue).apply()
    }

    fun isLegacyCompassModeEnabled(): Boolean {
        return prefs.getBoolean(KEY_LEGACY_COMPASS_MODE_ENABLED, false)
    }

    fun setLegacyCompassModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LEGACY_COMPASS_MODE_ENABLED, enabled).apply()
    }

    fun isLandOnlyDestinationEnabled(): Boolean =
        prefs.getBoolean(KEY_LAND_ONLY_DESTINATION_ENABLED, false)

    fun setLandOnlyDestinationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LAND_ONLY_DESTINATION_ENABLED, enabled).apply()
    }

    fun isDistanceMaskButtonVisible(): Boolean =
        prefs.getBoolean(KEY_DISTANCE_MASK_BUTTON_VISIBLE, true)

    fun setDistanceMaskButtonVisible(visible: Boolean) {
        prefs.edit().putBoolean(KEY_DISTANCE_MASK_BUTTON_VISIBLE, visible).apply()
    }

    fun isManualDistanceMaskEnabled(): Boolean =
        prefs.getBoolean(KEY_MANUAL_DISTANCE_MASK_ENABLED, false)

    fun setManualDistanceMaskEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MANUAL_DISTANCE_MASK_ENABLED, enabled).apply()
    }

    fun isScreenshotWarningEnabled(): Boolean =
        prefs.getBoolean(KEY_SCREENSHOT_WARNING_ENABLED, true)

    fun setScreenshotWarningEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCREENSHOT_WARNING_ENABLED, enabled).apply()
    }

    fun isArrivalSoundEnabled(): Boolean =
        prefs.getBoolean(KEY_ARRIVAL_SOUND_ENABLED, false)

    fun setArrivalSoundEnabled(enabled: Boolean) {
        val wasEnabled = isArrivalSoundEnabled()
        prefs.edit().putBoolean(KEY_ARRIVAL_SOUND_ENABLED, enabled).apply()
        DistanceEventProcessor.reset(getDestinationGeneration())
        if (enabled && !wasEnabled) setBackgroundPermissionGuideShown(false)
    }

    fun isDistance114514SoundEnabled(): Boolean =
        prefs.getBoolean(KEY_DISTANCE_114514_SOUND_ENABLED, false)

    fun setDistance114514SoundEnabled(enabled: Boolean) {
        val wasEnabled = isDistance114514SoundEnabled()
        prefs.edit().putBoolean(KEY_DISTANCE_114514_SOUND_ENABLED, enabled).apply()
        DistanceEventProcessor.reset(getDestinationGeneration())
        if (enabled && !wasEnabled) setBackgroundPermissionGuideShown(false)
    }

    fun isDistanceIntervalSoundEnabled(): Boolean =
        prefs.getBoolean(KEY_DISTANCE_INTERVAL_SOUND_ENABLED, false)

    fun setDistanceIntervalSoundEnabled(enabled: Boolean) {
        val wasEnabled = isDistanceIntervalSoundEnabled()
        prefs.edit().putBoolean(KEY_DISTANCE_INTERVAL_SOUND_ENABLED, enabled).apply()
        DistanceEventProcessor.reset(getDestinationGeneration())
        if (enabled && !wasEnabled) setBackgroundPermissionGuideShown(false)
    }

    fun isCompassSmoothingEnabled(): Boolean =
        prefs.getBoolean(KEY_COMPASS_SMOOTHING_ENABLED, true)

    fun setCompassSmoothingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COMPASS_SMOOTHING_ENABLED, enabled).apply()
    }

    fun getDistanceIntervalSoundMeters(): Int =
        prefs.getInt(KEY_DISTANCE_INTERVAL_SOUND_METERS, DEFAULT_DISTANCE_INTERVAL_SOUND_METERS)

    fun setDistanceIntervalSoundMeters(valueMeters: Int) {
        val clamped = valueMeters.coerceIn(
            MIN_DISTANCE_INTERVAL_SOUND_METERS,
            MAX_DISTANCE_INTERVAL_SOUND_METERS
        )
        prefs.edit().putInt(KEY_DISTANCE_INTERVAL_SOUND_METERS, clamped).apply()
        DistanceEventProcessor.reset(getDestinationGeneration())
    }

    fun isSoundForegroundMonitorEnabled(): Boolean {
        return isArrivalSoundEnabled() ||
            isDistance114514SoundEnabled() ||
            isDistanceIntervalSoundEnabled()
    }

    fun isBackgroundLocationUpdateForcedBySound(): Boolean =
        isSoundForegroundMonitorEnabled()

    fun isBackgroundLocationUpdateActive(): Boolean =
        isBackgroundLocationUpdateEnabled() || isBackgroundLocationUpdateForcedBySound()

    fun isDebugMenuVisible(): Boolean = prefs.getBoolean(KEY_DEBUG_MENU_VISIBLE, false)

    fun setDebugMenuVisible(visible: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_MENU_VISIBLE, visible).apply()
    }

    fun isStableDebugMenuUnlockEnabled(): Boolean =
        prefs.getBoolean(KEY_STABLE_DEBUG_MENU_UNLOCK_ENABLED, false)

    fun setStableDebugMenuUnlockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STABLE_DEBUG_MENU_UNLOCK_ENABLED, enabled).apply()
    }

    fun isNonJapaneseLanguageEnabled(): Boolean =
        prefs.getBoolean(KEY_NON_JAPANESE_LANGUAGE_ENABLED, DEFAULT_NON_JAPANESE_LANGUAGE_ENABLED)

    fun setNonJapaneseLanguageEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NON_JAPANESE_LANGUAGE_ENABLED, enabled).apply()
    }

    fun isBackgroundPermissionGuideShown(): Boolean {
        val shownAt = runtimePrefs.getLong(KEY_BACKGROUND_PERMISSION_GUIDE_SHOWN_AT, 0L)
        val age = System.currentTimeMillis() - shownAt
        return shownAt > 0L && age in 0L..BACKGROUND_PERMISSION_GUIDE_COOLDOWN_MILLIS
    }

    fun setBackgroundPermissionGuideShown(shown: Boolean) {
        val editor = runtimePrefs.edit()
        if (shown) {
            editor.putBoolean(KEY_BACKGROUND_PERMISSION_GUIDE_SHOWN, true)
                .putLong(KEY_BACKGROUND_PERMISSION_GUIDE_SHOWN_AT, System.currentTimeMillis())
        } else {
            editor.remove(KEY_BACKGROUND_PERMISSION_GUIDE_SHOWN)
                .remove(KEY_BACKGROUND_PERMISSION_GUIDE_SHOWN_AT)
        }
        editor.apply()
    }

    fun isWelcomeCompleted(): Boolean =
        prefs.getBoolean(KEY_WELCOME_COMPLETED, false)

    fun setWelcomeCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_WELCOME_COMPLETED, completed).apply()
    }

    fun getLastKnownLocation(): Destination? {
        return getLastKnownLocationSample()?.position
    }

    fun getLastKnownLocationSample(): LocationSample? {
        if (!runtimePrefs.contains(KEY_LAST_LAT) ||
            !runtimePrefs.contains(KEY_LAST_LNG) ||
            !runtimePrefs.contains(KEY_LAST_LOCATION_ACCURACY) ||
            !runtimePrefs.contains(KEY_LAST_LOCATION_TIME)
        ) {
            return null
        }
        if (isDebugDistanceOverrideEnabled()) {
            val now = System.currentTimeMillis()
            return LocationSample(
                position = Destination(
                    java.lang.Double.longBitsToDouble(runtimePrefs.getLong(KEY_LAST_LAT, 0L)),
                    java.lang.Double.longBitsToDouble(runtimePrefs.getLong(KEY_LAST_LNG, 0L))
                ),
                accuracyMeters = 0f,
                wallTimeMillis = now,
                elapsedRealtimeNanos = 0L,
                ageMillis = 0L,
                isMock = false,
                source = LocationSampleSource.PERSISTED
            ).takeIf { it.position.isValidCoordinate() }
        }
        return LocationSampleFactory.fromPersisted(
            position = Destination(
                java.lang.Double.longBitsToDouble(runtimePrefs.getLong(KEY_LAST_LAT, 0L)),
                java.lang.Double.longBitsToDouble(runtimePrefs.getLong(KEY_LAST_LNG, 0L))
            ),
            accuracyMeters = runtimePrefs.getFloat(
                KEY_LAST_LOCATION_ACCURACY,
                LocationSample.MAX_DISPLAY_ACCURACY_METERS
            ),
            wallTimeMillis = runtimePrefs.getLong(KEY_LAST_LOCATION_TIME, 0L)
        )
    }

    fun getRegisteredGeofenceDestination(): Destination? {
        if (!runtimePrefs.contains(KEY_REGISTERED_GEOFENCE_LAT) ||
            !runtimePrefs.contains(KEY_REGISTERED_GEOFENCE_LNG)
        ) {
            return null
        }
        return Destination(
            java.lang.Double.longBitsToDouble(runtimePrefs.getLong(KEY_REGISTERED_GEOFENCE_LAT, 0L)),
            java.lang.Double.longBitsToDouble(runtimePrefs.getLong(KEY_REGISTERED_GEOFENCE_LNG, 0L))
        )
    }

    fun setRegisteredGeofenceDestination(destination: Destination) {
        if (isSameRegisteredGeofenceDestination(destination)) return
        runtimePrefs.edit()
            .putLong(
                KEY_REGISTERED_GEOFENCE_LAT,
                java.lang.Double.doubleToRawLongBits(destination.lat)
            )
            .putLong(
                KEY_REGISTERED_GEOFENCE_LNG,
                java.lang.Double.doubleToRawLongBits(destination.lng)
            )
            .apply()
    }

    fun clearRegisteredGeofenceDestination() {
        if (!runtimePrefs.contains(KEY_REGISTERED_GEOFENCE_LAT) &&
            !runtimePrefs.contains(KEY_REGISTERED_GEOFENCE_LNG)
        ) {
            return
        }
        runtimePrefs.edit()
            .remove(KEY_REGISTERED_GEOFENCE_LAT)
            .remove(KEY_REGISTERED_GEOFENCE_LNG)
            .apply()
    }

    fun isSameRegisteredGeofenceDestination(destination: Destination): Boolean {
        val registered = getRegisteredGeofenceDestination() ?: return false
        return registered.lat == destination.lat && registered.lng == destination.lng
    }

    fun getDestinationGeneration(): Long =
        runtimePrefs.getLong(KEY_DESTINATION_GENERATION, 0L)

    fun advanceDestinationGeneration(): Long {
        val next = getDestinationGeneration() + 1L
        runtimePrefs.edit()
            .putLong(KEY_DESTINATION_GENERATION, next)
            .remove(KEY_REGISTERED_GEOFENCE_LAT)
            .remove(KEY_REGISTERED_GEOFENCE_LNG)
            .apply()
        SharedArrivalConfirmation.reset()
        return next
    }

    private fun migratePreferencesIfNeeded() {
        synchronized(MIGRATION_LOCK) {
            if (runtimePrefs.getInt(KEY_RUNTIME_SCHEMA_VERSION, 0) < RUNTIME_SCHEMA_VERSION) {
                val oldValues = legacyPrefs.all
                val runtimeEditor = runtimePrefs.edit()
                val legacyEditor = legacyPrefs.edit()
                for (key in LEGACY_RUNTIME_KEYS) {
                    val value = oldValues[key] ?: continue
                    putPreferenceValue(runtimeEditor, key, value)
                    legacyEditor.remove(key)
                }
                if (oldValues[KEY_DEBUG_DISTANCE_OVERRIDE_ENABLED] == true &&
                    oldValues.containsKey(KEY_LAST_LAT) &&
                    oldValues.containsKey(KEY_LAST_LNG)
                ) {
                    runtimeEditor.putFloat(KEY_LAST_LOCATION_ACCURACY, 0f)
                        .putLong(KEY_LAST_LOCATION_TIME, System.currentTimeMillis())
                }
                val runtimeMigrationSaved = runtimeEditor
                    .putInt(KEY_RUNTIME_SCHEMA_VERSION, RUNTIME_SCHEMA_VERSION)
                    .commit()
                if (runtimeMigrationSaved) {
                    legacyEditor.commit()
                } else {
                    AppDiagnostics.warn("runtime_preferences_migration_failed")
                }
            }

            if (prefs.getInt(KEY_SETTINGS_SCHEMA_VERSION, 0) < SETTINGS_SCHEMA_VERSION) {
                val editor = prefs.edit()
                val legacyValues = legacyPrefs.all
                for (key in BACKUP_SAFE_SETTING_KEYS) {
                    if (prefs.contains(key)) continue
                    val value = legacyValues[key] ?: continue
                    putPreferenceValue(editor, key, value)
                }

                val legacyCompassAlreadyAvailable =
                    prefs.contains(KEY_LEGACY_COMPASS_MODE_ENABLED) ||
                        legacyValues[KEY_LEGACY_COMPASS_MODE_ENABLED] is Boolean
                if (!legacyCompassAlreadyAvailable) {
                    val oldMode = legacyValues[KEY_OLD_COMPASS_SENSOR_MODE] as? String
                    if (oldMode == OLD_LEGACY_COMPASS_MODE_VALUE) {
                        editor.putBoolean(KEY_LEGACY_COMPASS_MODE_ENABLED, true)
                    }
                }
                editor.remove(KEY_OLD_COMPASS_SENSOR_MODE)
                    .putInt(KEY_SETTINGS_SCHEMA_VERSION, SETTINGS_SCHEMA_VERSION)
                if (!editor.commit()) {
                    AppDiagnostics.warn("settings_preferences_migration_failed")
                }
            }
        }
    }

    private fun putPreferenceValue(
        editor: SharedPreferences.Editor,
        key: String,
        value: Any
    ) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    private fun normalizeHeading(value: Float): Float {
        val normalized = value % 360f
        return if (normalized < 0f) normalized + 360f else normalized
    }

    companion object {
        private const val LEGACY_PREFS_NAME = "destination_store"
        private const val SETTINGS_PREFS_NAME = "destination_settings"
        private const val RUNTIME_PREFS_NAME = "destination_runtime"
        private const val KEY_RADIUS_KM = "radius_km"
        private const val KEY_DEBUG_DEST_OVERRIDE_ENABLED = "debug_dest_override_enabled"
        private const val KEY_DEBUG_DEST_OVERRIDE_LAT = "debug_dest_override_lat"
        private const val KEY_DEBUG_DEST_OVERRIDE_LNG = "debug_dest_override_lng"
        private const val KEY_DEST_ANSWERED = "dest_answered"
        private const val KEY_ARRIVAL_REARM_REQUIRED = "arrival_rearm_required"
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LNG = "last_lng"
        private const val KEY_LAST_LOCATION_ACCURACY = "last_location_accuracy"
        private const val KEY_LAST_LOCATION_TIME = "last_location_time"
        private const val KEY_LAST_HEADING = "last_heading"
        private const val KEY_LAST_HEADING_TIME = "last_heading_time"
        private const val KEY_DEBUG_DISTANCE_OVERRIDE_ENABLED = "debug_distance_override_enabled"
        private const val KEY_LIVE_UPDATE_ENABLED = "live_update_enabled"
        private const val KEY_LIVE_UPDATE_START_DISTANCE_METERS = "live_update_start_distance_meters"
        private const val KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS = "live_update_anchor_distance_meters"
        private const val KEY_ARRIVAL_NOTIFICATION_ENABLED = "arrival_notification_enabled"
        private const val KEY_WIDGET_BACKGROUND_UPDATE_ENABLED = "widget_background_update_enabled"
        private const val KEY_WIDGET_BEARING_MODE = "widget_bearing_mode"
        private const val KEY_LEGACY_COMPASS_MODE_ENABLED = "legacy_compass_mode_enabled"
        private const val KEY_LAND_ONLY_DESTINATION_ENABLED = "land_only_destination_enabled"
        private const val KEY_DISTANCE_MASK_BUTTON_VISIBLE = "distance_mask_button_visible"
        private const val KEY_MANUAL_DISTANCE_MASK_ENABLED = "manual_distance_mask_enabled"
        private const val KEY_SCREENSHOT_WARNING_ENABLED = "screenshot_warning_enabled"
        private const val KEY_ARRIVAL_SOUND_ENABLED = "arrival_sound_enabled"
        private const val KEY_DISTANCE_114514_SOUND_ENABLED = "distance_114514_sound_enabled"
        private const val KEY_DISTANCE_INTERVAL_SOUND_ENABLED = "distance_interval_sound_enabled"
        private const val KEY_COMPASS_SMOOTHING_ENABLED = "compass_smoothing_enabled"
        private const val KEY_DISTANCE_INTERVAL_SOUND_METERS = "distance_interval_sound_meters"
        private const val KEY_DEBUG_MENU_VISIBLE = "debug_menu_visible"
        private const val KEY_STABLE_DEBUG_MENU_UNLOCK_ENABLED = "stable_debug_menu_unlock_enabled"
        private const val KEY_NON_JAPANESE_LANGUAGE_ENABLED = "non_japanese_language_enabled"
        private const val KEY_BACKGROUND_PERMISSION_GUIDE_SHOWN = "background_permission_guide_shown"
        private const val KEY_BACKGROUND_PERMISSION_GUIDE_SHOWN_AT = "background_permission_guide_shown_at"
        private const val KEY_WELCOME_COMPLETED = "welcome_completed"
        private const val KEY_ARRIVAL_DESTINATION_NAME = "arrival_destination_name"
        private const val KEY_ARRIVAL_DESTINATION_NAME_RESOLVED = "arrival_destination_name_resolved"
        private const val KEY_REGISTERED_GEOFENCE_LAT = "registered_geofence_lat"
        private const val KEY_REGISTERED_GEOFENCE_LNG = "registered_geofence_lng"
        private const val KEY_DESTINATION_GENERATION = "destination_generation"
        private const val KEY_RUNTIME_SCHEMA_VERSION = "runtime_schema_version"
        private const val KEY_SETTINGS_SCHEMA_VERSION = "settings_schema_version"
        private const val KEY_OLD_COMPASS_SENSOR_MODE = "compass_sensor_mode"
        private const val DEFAULT_LIVE_UPDATE_START_DISTANCE_METERS = 300
        private const val MIN_LIVE_UPDATE_START_DISTANCE_METERS = 200
        private const val MAX_LIVE_UPDATE_START_DISTANCE_METERS = 5000
        private const val DEFAULT_DISTANCE_INTERVAL_SOUND_METERS = 1000
        private const val MIN_DISTANCE_INTERVAL_SOUND_METERS = 100
        private const val MAX_DISTANCE_INTERVAL_SOUND_METERS = 5000
        private const val DEFAULT_NON_JAPANESE_LANGUAGE_ENABLED = true
        private const val DEFAULT_DEST_LAT = 35.665554
        private const val DEFAULT_DEST_LNG = 139.669717
        private const val MIN_LOCATION_PERSIST_INTERVAL_MILLIS = 30_000L
        private const val MIN_LOCATION_PERSIST_DISTANCE_METERS = 25f
        private const val MAX_HEADING_AGE_MILLIS = 5 * 60_000L
        private const val BACKGROUND_PERMISSION_GUIDE_COOLDOWN_MILLIS = 7 * 24 * 60 * 60_000L
        private const val RUNTIME_SCHEMA_VERSION = 1
        private const val SETTINGS_SCHEMA_VERSION = 2
        private const val OLD_LEGACY_COMPASS_MODE_VALUE = "legacy_orientation"
        private val MIGRATION_LOCK = Any()
        private val LEGACY_RUNTIME_KEYS = setOf(
            KEY_DEBUG_DEST_OVERRIDE_ENABLED,
            KEY_DEBUG_DEST_OVERRIDE_LAT,
            KEY_DEBUG_DEST_OVERRIDE_LNG,
            KEY_DEST_ANSWERED,
            KEY_ARRIVAL_REARM_REQUIRED,
            KEY_LAST_LAT,
            KEY_LAST_LNG,
            KEY_LAST_HEADING,
            KEY_DEBUG_DISTANCE_OVERRIDE_ENABLED,
            KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS,
            KEY_BACKGROUND_PERMISSION_GUIDE_SHOWN,
            KEY_BACKGROUND_PERMISSION_GUIDE_SHOWN_AT,
            KEY_ARRIVAL_DESTINATION_NAME,
            KEY_ARRIVAL_DESTINATION_NAME_RESOLVED,
            KEY_REGISTERED_GEOFENCE_LAT,
            KEY_REGISTERED_GEOFENCE_LNG
        )
        private val BACKUP_SAFE_SETTING_KEYS = setOf(
            KEY_RADIUS_KM,
            KEY_LIVE_UPDATE_ENABLED,
            KEY_LIVE_UPDATE_START_DISTANCE_METERS,
            KEY_ARRIVAL_NOTIFICATION_ENABLED,
            KEY_WIDGET_BACKGROUND_UPDATE_ENABLED,
            KEY_WIDGET_BEARING_MODE,
            KEY_LEGACY_COMPASS_MODE_ENABLED,
            KEY_LAND_ONLY_DESTINATION_ENABLED,
            KEY_DISTANCE_MASK_BUTTON_VISIBLE,
            KEY_MANUAL_DISTANCE_MASK_ENABLED,
            KEY_SCREENSHOT_WARNING_ENABLED,
            KEY_ARRIVAL_SOUND_ENABLED,
            KEY_DISTANCE_114514_SOUND_ENABLED,
            KEY_DISTANCE_INTERVAL_SOUND_ENABLED,
            KEY_COMPASS_SMOOTHING_ENABLED,
            KEY_DISTANCE_INTERVAL_SOUND_METERS,
            KEY_DEBUG_MENU_VISIBLE,
            KEY_STABLE_DEBUG_MENU_UNLOCK_ENABLED,
            KEY_NON_JAPANESE_LANGUAGE_ENABLED,
            KEY_WELCOME_COMPLETED
        )
    }
}
