package jp.linkserver.beastlocator

import android.content.Context

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
    private val prefs = context.getSharedPreferences("destination_store", Context.MODE_PRIVATE)

    fun getRadiusKm(): Float = prefs.getFloat(KEY_RADIUS_KM, 5f)

    fun setRadiusKm(value: Float) {
        prefs.edit().putFloat(KEY_RADIUS_KM, value).apply()
    }

    fun getDestination(): Destination {
        if (isDebugDestinationOverrideEnabled() &&
            prefs.contains(KEY_DEBUG_DEST_OVERRIDE_LAT) &&
            prefs.contains(KEY_DEBUG_DEST_OVERRIDE_LNG)
        ) {
            return Destination(
                java.lang.Double.longBitsToDouble(prefs.getLong(KEY_DEBUG_DEST_OVERRIDE_LAT, 0L)),
                java.lang.Double.longBitsToDouble(prefs.getLong(KEY_DEBUG_DEST_OVERRIDE_LNG, 0L))
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
        prefs.edit()
            .putLong(KEY_DEBUG_DEST_OVERRIDE_LAT, java.lang.Double.doubleToRawLongBits(destination.lat))
            .putLong(KEY_DEBUG_DEST_OVERRIDE_LNG, java.lang.Double.doubleToRawLongBits(destination.lng))
            .putBoolean(KEY_DEBUG_DEST_OVERRIDE_ENABLED, true)
            .putBoolean(KEY_DEST_ANSWERED, false)
            .putBoolean(KEY_ARRIVAL_REARM_REQUIRED, false)
            .remove(KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS)
            .remove(KEY_ARRIVAL_DESTINATION_NAME)
            .apply()
    }

    fun clearDestination() {
        clearDebugDestinationOverride()
    }

    fun clearDebugDestinationOverride() {
        prefs.edit()
            .remove(KEY_DEBUG_DEST_OVERRIDE_LAT)
            .remove(KEY_DEBUG_DEST_OVERRIDE_LNG)
            .putBoolean(KEY_DEBUG_DEST_OVERRIDE_ENABLED, false)
            .remove(KEY_DEST_ANSWERED)
            .putBoolean(KEY_ARRIVAL_REARM_REQUIRED, false)
            .remove(KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS)
            .remove(KEY_ARRIVAL_DESTINATION_NAME)
            .apply()
    }

    fun isDebugDestinationOverrideEnabled(): Boolean =
        prefs.getBoolean(KEY_DEBUG_DEST_OVERRIDE_ENABLED, false)

    fun getDefaultDestination(): Destination = Destination(DEFAULT_DEST_LAT, DEFAULT_DEST_LNG)

    fun isDestinationAnswered(): Boolean = prefs.getBoolean(KEY_DEST_ANSWERED, false)

    fun setDestinationAnswered(answered: Boolean) {
        prefs.edit().putBoolean(KEY_DEST_ANSWERED, answered).apply()
        clearLiveUpdateAnchorDistanceMeters()
        if (!answered) {
            prefs.edit().remove(KEY_ARRIVAL_DESTINATION_NAME).apply()
        }
    }

    fun getArrivalDestinationName(): String? = prefs.getString(KEY_ARRIVAL_DESTINATION_NAME, null)

    fun setArrivalDestinationName(name: String) {
        prefs.edit().putString(KEY_ARRIVAL_DESTINATION_NAME, name).apply()
    }

    fun isArrivalRearmRequired(): Boolean =
        prefs.getBoolean(KEY_ARRIVAL_REARM_REQUIRED, false)

    fun setArrivalRearmRequired(required: Boolean) {
        prefs.edit().putBoolean(KEY_ARRIVAL_REARM_REQUIRED, required).apply()
    }

    fun setLastKnownLocation(lat: Double, lng: Double) {
        prefs.edit()
            .putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(KEY_LAST_LNG, java.lang.Double.doubleToRawLongBits(lng))
            .apply()
    }

    fun setLastKnownLocationFromSystem(lat: Double, lng: Double): Boolean {
        if (isDebugDistanceOverrideEnabled()) {
            return false
        }
        setLastKnownLocation(lat, lng)
        return true
    }

    fun clearLastKnownLocation() {
        prefs.edit()
            .remove(KEY_LAST_LAT)
            .remove(KEY_LAST_LNG)
            .remove(KEY_LAST_HEADING)
            .apply()
    }

    fun isDebugDistanceOverrideEnabled(): Boolean =
        prefs.getBoolean(KEY_DEBUG_DISTANCE_OVERRIDE_ENABLED, false)

    fun setDebugDistanceOverrideLocation(lat: Double, lng: Double) {
        prefs.edit()
            .putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(KEY_LAST_LNG, java.lang.Double.doubleToRawLongBits(lng))
            .putBoolean(KEY_DEBUG_DISTANCE_OVERRIDE_ENABLED, true)
            .apply()
    }

    fun clearDebugDistanceOverride() {
        prefs.edit()
            .remove(KEY_LAST_LAT)
            .remove(KEY_LAST_LNG)
            .remove(KEY_LAST_HEADING)
            .putBoolean(KEY_DEBUG_DISTANCE_OVERRIDE_ENABLED, false)
            .apply()
    }

    fun setLastKnownHeading(headingDegrees: Float) {
        prefs.edit().putFloat(KEY_LAST_HEADING, headingDegrees).apply()
    }

    fun getLastKnownHeading(): Float? {
        if (!prefs.contains(KEY_LAST_HEADING)) return null
        return prefs.getFloat(KEY_LAST_HEADING, 0f)
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
        if (!prefs.contains(KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS)) return null
        return prefs.getFloat(KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS, 0f)
    }

    fun setLiveUpdateAnchorDistanceMeters(distanceMeters: Float) {
        val clamped = distanceMeters.coerceAtLeast(0f)
        if (getLiveUpdateAnchorDistanceMeters() == clamped) return
        prefs.edit()
            .putFloat(KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS, clamped)
            .apply()
    }

    fun clearLiveUpdateAnchorDistanceMeters() {
        if (!prefs.contains(KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS)) return
        prefs.edit().remove(KEY_LIVE_UPDATE_ANCHOR_DISTANCE_METERS).apply()
    }

    fun isArrivalNotificationEnabled(): Boolean =
        prefs.getBoolean(KEY_ARRIVAL_NOTIFICATION_ENABLED, true)

    fun setArrivalNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ARRIVAL_NOTIFICATION_ENABLED, enabled).apply()
    }

    fun isBackgroundLocationUpdateEnabled(): Boolean =
        prefs.getBoolean(KEY_WIDGET_BACKGROUND_UPDATE_ENABLED, true)

    fun setBackgroundLocationUpdateEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIDGET_BACKGROUND_UPDATE_ENABLED, enabled).apply()
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
        prefs.edit().putBoolean(KEY_ARRIVAL_SOUND_ENABLED, enabled).apply()
    }

    fun isDistance114514SoundEnabled(): Boolean =
        prefs.getBoolean(KEY_DISTANCE_114514_SOUND_ENABLED, false)

    fun setDistance114514SoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DISTANCE_114514_SOUND_ENABLED, enabled).apply()
    }

    fun isDistanceIntervalSoundEnabled(): Boolean =
        prefs.getBoolean(KEY_DISTANCE_INTERVAL_SOUND_ENABLED, false)

    fun setDistanceIntervalSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DISTANCE_INTERVAL_SOUND_ENABLED, enabled).apply()
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

    fun isBackgroundPermissionGuideShown(): Boolean =
        prefs.getBoolean(KEY_BACKGROUND_PERMISSION_GUIDE_SHOWN, false)

    fun setBackgroundPermissionGuideShown(shown: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_PERMISSION_GUIDE_SHOWN, shown).apply()
    }

    fun isWelcomeCompleted(): Boolean =
        prefs.getBoolean(KEY_WELCOME_COMPLETED, false)

    fun setWelcomeCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_WELCOME_COMPLETED, completed).apply()
    }

    fun getLastKnownLocation(): Destination? {
        if (!prefs.contains(KEY_LAST_LAT) || !prefs.contains(KEY_LAST_LNG)) return null
        return Destination(
            java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_LAT, 0L)),
            java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_LNG, 0L))
        )
    }

    fun getRegisteredGeofenceDestination(): Destination? {
        if (!prefs.contains(KEY_REGISTERED_GEOFENCE_LAT) || !prefs.contains(KEY_REGISTERED_GEOFENCE_LNG)) {
            return null
        }
        return Destination(
            java.lang.Double.longBitsToDouble(prefs.getLong(KEY_REGISTERED_GEOFENCE_LAT, 0L)),
            java.lang.Double.longBitsToDouble(prefs.getLong(KEY_REGISTERED_GEOFENCE_LNG, 0L))
        )
    }

    fun setRegisteredGeofenceDestination(destination: Destination) {
        if (isSameRegisteredGeofenceDestination(destination)) return
        prefs.edit()
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
        if (!prefs.contains(KEY_REGISTERED_GEOFENCE_LAT) &&
            !prefs.contains(KEY_REGISTERED_GEOFENCE_LNG)
        ) {
            return
        }
        prefs.edit()
            .remove(KEY_REGISTERED_GEOFENCE_LAT)
            .remove(KEY_REGISTERED_GEOFENCE_LNG)
            .apply()
    }

    fun isSameRegisteredGeofenceDestination(destination: Destination): Boolean {
        val registered = getRegisteredGeofenceDestination() ?: return false
        return registered.lat == destination.lat && registered.lng == destination.lng
    }

    companion object {
        private const val KEY_RADIUS_KM = "radius_km"
        private const val KEY_DEBUG_DEST_OVERRIDE_ENABLED = "debug_dest_override_enabled"
        private const val KEY_DEBUG_DEST_OVERRIDE_LAT = "debug_dest_override_lat"
        private const val KEY_DEBUG_DEST_OVERRIDE_LNG = "debug_dest_override_lng"
        private const val KEY_DEST_ANSWERED = "dest_answered"
        private const val KEY_ARRIVAL_REARM_REQUIRED = "arrival_rearm_required"
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LNG = "last_lng"
        private const val KEY_LAST_HEADING = "last_heading"
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
        private const val KEY_WELCOME_COMPLETED = "welcome_completed"
        private const val KEY_ARRIVAL_DESTINATION_NAME = "arrival_destination_name"
        private const val KEY_REGISTERED_GEOFENCE_LAT = "registered_geofence_lat"
        private const val KEY_REGISTERED_GEOFENCE_LNG = "registered_geofence_lng"
        private const val DEFAULT_LIVE_UPDATE_START_DISTANCE_METERS = 300
        private const val MIN_LIVE_UPDATE_START_DISTANCE_METERS = 200
        private const val MAX_LIVE_UPDATE_START_DISTANCE_METERS = 5000
        private const val DEFAULT_DISTANCE_INTERVAL_SOUND_METERS = 1000
        private const val MIN_DISTANCE_INTERVAL_SOUND_METERS = 100
        private const val MAX_DISTANCE_INTERVAL_SOUND_METERS = 5000
        private const val DEFAULT_NON_JAPANESE_LANGUAGE_ENABLED = true
        private const val DEFAULT_DEST_LAT = 35.665554
        private const val DEFAULT_DEST_LNG = 139.669717
    }
}
