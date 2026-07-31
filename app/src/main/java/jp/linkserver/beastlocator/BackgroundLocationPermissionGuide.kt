package jp.linkserver.beastlocator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Owns the background-location permission explanation dialog for one Activity instance.
 *
 * [onHostResumed] deliberately re-evaluates current permissions instead of caching them. This
 * allows a host to call it after returning from system settings. The persisted guide cooldown is
 * only started after a settings Activity was launched successfully.
 */
class BackgroundLocationPermissionGuide(
    private val activity: Activity,
    private val store: DestinationStore,
    @StringRes private val titleResId: Int = R.string.background_permission_guide_title,
    @StringRes private val messageResId: Int = R.string.background_permission_guide_message,
    @StringRes private val positiveButtonResId: Int = R.string.background_permission_guide_positive,
    private val onSettingsLaunched: () -> Unit = {}
) {
    private var dialog: AlertDialog? = null

    val isShowing: Boolean
        get() = dialog?.isShowing == true

    fun onBackgroundFeatureEnabled(): Boolean = showIfNeeded()

    fun onHostResumed(): Boolean = showIfNeeded()

    fun showIfNeeded(): Boolean {
        if (activity.isFinishing || activity.isDestroyed || isShowing) return false
        if (!BackgroundLocationPermissionGuidePolicy.shouldShow(
                isAtLeastAndroidQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                hasFineLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
                hasBackgroundLocation = hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                isBackgroundMonitoringActive = store.isBackgroundLocationUpdateActive(),
                wasGuideShownRecently = store.isBackgroundPermissionGuideShown()
            )
        ) {
            return false
        }

        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(titleResId)
            .setMessage(messageResId)
            .setCancelable(false)
            .setPositiveButton(positiveButtonResId) { _, _ ->
                if (openAppPermissionSettings()) {
                    store.setBackgroundPermissionGuideShown(true)
                    onSettingsLaunched()
                }
            }
            .setOnDismissListener {
                dialog = null
            }
            .show()
        return true
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun openAppPermissionSettings(): Boolean {
        val intents = listOf(
            Intent("android.settings.APP_PERMISSION_SETTINGS").apply {
                putExtra("android.provider.extra.APP_PACKAGE", activity.packageName)
                putExtra(
                    "android.provider.extra.PERMISSION_NAME",
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
                putExtra(
                    "android.provider.extra.PERMISSION_GROUP_NAME",
                    "android.permission-group.LOCATION"
                )
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
            }
        )
        for (intent in intents) {
            if (runCatching { activity.startActivity(intent) }.isSuccess) {
                return true
            }
        }
        AppDiagnostics.warn("background_permission_settings_open_failed")
        return false
    }
}

internal object BackgroundLocationPermissionGuidePolicy {
    fun shouldShow(
        isAtLeastAndroidQ: Boolean,
        hasFineLocation: Boolean,
        hasBackgroundLocation: Boolean,
        isBackgroundMonitoringActive: Boolean,
        wasGuideShownRecently: Boolean
    ): Boolean = isAtLeastAndroidQ &&
        hasFineLocation &&
        !hasBackgroundLocation &&
        isBackgroundMonitoringActive &&
        !wasGuideShownRecently
}
