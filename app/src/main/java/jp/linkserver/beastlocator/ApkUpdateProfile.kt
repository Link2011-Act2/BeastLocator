package jp.linkserver.beastlocator

import android.content.Context
import jp.linkserver.goldy.*

/** App APK only. Location, geofencing, sound and widget execution remain independent. */
internal object ApkUpdateProfile {
    val repository = GitHubRepository("Link2011-Act2", "BeastLocator")
    val policy = UpdatePolicies.beastLocator()
    val selection = ApkSelectionPolicies.Single
    const val ACTION_VIEW_TRANSFER = "jp.linkserver.beastlocator.VIEW_APK_UPDATE"
    const val JOB_ID = 0x4241504b
    const val NOTIFICATION_ID = JOB_ID
    const val WORK_NAME = "beast-apk-update-transfer-v1"
    const val CHANNEL = "beast-apk-update-transfer"
    const val JOB_NAMESPACE = "beast-apk-updates"
    fun downloadConfig(context: Context) = downloadConfig(installedVersionName(context))
    fun downloadConfig(installedName: String) = ApkDownloadConfig(repository, selection, ApkVersionBindings.Published,
        userAgent = "BeastLocator/$installedName")
}

@Suppress("DEPRECATION")
internal fun installedVersionName(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName?.takeIf(String::isNotBlank)
        ?: error("Installed package version is missing")

internal fun updateOptions(installed: String, preview: Boolean, override: String?): RuntimeOptions = RuntimeOptions(
    developer = if (VersionPolicies.Common.channel(installed) == jp.linkserver.goldy.ReleaseChannel.IntDev)
        DeveloperOptions(ignoreVersionComparison = preview, currentVersionOverride = override?.trim()?.takeIf(String::isNotEmpty))
    else DeveloperOptions()
)
