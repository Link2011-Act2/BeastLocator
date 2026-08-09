package jp.linkserver.beastlocator

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.doOnLayout
import androidx.core.view.setPadding
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AboutActivity : AppCompatActivity() {
    private var updateCheckOperation: AppUpdateManager.Operation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        SystemBarInsetApplier.apply(findViewById(R.id.aboutRoot))

        val (versionName, versionCode) = resolveAppVersionInfo()
        val (simpleVersion, _) = splitVersionAndChannel(versionName)
        val channel = ReleaseChannelDetector.detect(versionName)
        val channelLabel = resolveChannelLabel(channel)
        configureVersionSummary(
            findViewById(R.id.aboutVersionText),
            channelLabel,
            versionName,
            versionCode,
        )
        findViewById<TextView>(R.id.aboutDevChannelText).text =
            channel.canonicalName
        findViewById<TextView>(R.id.aboutSimpleVersionText).text =
            getString(R.string.about_simple_version_value, simpleVersion)
        findViewById<TextView>(R.id.aboutUpdateVersionText).text =
            getString(R.string.about_update_latest_format, versionName, versionCode)
        findViewById<android.widget.FrameLayout>(R.id.aboutDevChannelCard).setOnClickListener {
            showDevChannelDescription(channel, channelLabel)
        }
        findViewById<android.widget.FrameLayout>(R.id.aboutSimpleVersionCard).setOnClickListener {
            showVersionDetailsDialog(versionName, versionCode, channel)
        }
        findViewById<android.widget.Button>(R.id.aboutCheckUpdateButton).setOnClickListener {
            checkForUpdateManually()
        }

        findViewById<LinearLayout>(R.id.openOssLicensesCard).setOnClickListener {
            startActivity(Intent(this, OssLicensesActivity::class.java))
        }
        findViewById<android.widget.Button>(R.id.aboutSupportSiteButton).setOnClickListener {
            openUrl(getString(R.string.about_support_site_url))
        }
        findViewById<android.widget.Button>(R.id.aboutSupportTwitterButton).setOnClickListener {
            openUrl(getString(R.string.about_support_twitter_url))
        }
        findViewById<ImageButton>(R.id.aboutBackButton).setOnClickListener {
            finish()
        }
        findViewById<ImageView>(R.id.aboutAppIcon).setOnLongClickListener {
            showEasterEggDialog()
            true
        }
    }

    override fun onDestroy() {
        updateCheckOperation?.cancel()
        updateCheckOperation = null
        super.onDestroy()
    }

    private fun checkForUpdateManually() {
        val button = findViewById<android.widget.Button>(R.id.aboutCheckUpdateButton)
        val notes = findViewById<TextView>(R.id.aboutUpdateNotesText)
        updateCheckOperation?.cancel()
        button.isEnabled = false
        notes.setText(R.string.update_checking)
        updateCheckOperation = runCatching {
            AppUpdateManager.checkForUpdate(this, force = true) { result ->
                updateCheckOperation = null
                button.isEnabled = true
                result.onSuccess { updateInfo ->
                    if (updateInfo == null) {
                        notes.setText(R.string.update_check_latest)
                    } else {
                        notes.text = getString(R.string.update_available, updateInfo.tagName)
                        startActivity(UpdateActivity.createIntent(this, updateInfo))
                    }
                }.onFailure { error ->
                    notes.text = getString(
                        R.string.update_check_failed,
                        error.localizedMessage ?: error.javaClass.simpleName
                    )
                }
            }
        }.getOrElse { error ->
            button.isEnabled = true
            notes.text = getString(
                R.string.update_check_failed,
                error.localizedMessage ?: error.javaClass.simpleName
            )
            null
        }
    }

    private fun resolveAppVersionInfo(): Pair<String, Int> {
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
            val versionName = info.versionName ?: "unknown"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
            Pair(versionName, versionCode)
        } catch (_: Exception) {
            Pair("unknown", 0)
        }
    }

    private fun configureVersionSummary(
        view: TextView,
        channelLabel: String,
        versionName: String,
        versionCode: Int,
    ) {
        val summaryWithVersionCode = getString(
            R.string.about_version_label,
            channelLabel,
            versionName,
            versionCode,
        )
        view.text = summaryWithVersionCode
        view.doOnLayout {
            val availableWidth = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
            if (view.paint.measureText(summaryWithVersionCode) > availableWidth) {
                view.text = getString(
                    R.string.about_version_label_without_code,
                    channelLabel,
                    versionName,
                )
                view.setAutoSizeTextTypeUniformWithConfiguration(
                    11,
                    14,
                    1,
                    TypedValue.COMPLEX_UNIT_SP,
                )
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.oss_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun splitVersionAndChannel(versionName: String): Pair<String, String> {
        fun fallback(): Pair<String, String> =
            Pair(versionName, "unknown")

        // Preferred format: x.x.x-Channel
        val hyphenPos = versionName.lastIndexOf('-')
        if (hyphenPos > 0 && hyphenPos < versionName.length - 1) {
            val core = versionName.substring(0, hyphenPos).trim()
            val channel = versionName.substring(hyphenPos + 1).trim().trim('(', ')')
            if (core.isNotBlank() && channel.isNotBlank()) {
                return Pair(core, channel)
            }
        }

        // Backward compatibility: x.x.x.Channel
        val dotPos = versionName.lastIndexOf('.')
        if (dotPos <= 0 || dotPos >= versionName.length - 1) return fallback()
        val rawChannel = versionName.substring(dotPos + 1).trim()
        val hasChannelHint = rawChannel.any { it.isLetter() } ||
            rawChannel.startsWith("(") || rawChannel.endsWith(")")
        if (!hasChannelHint) return fallback()

        val channel = rawChannel.trim('(', ')', ' ')
        val coreVersion = versionName.substring(0, dotPos).trimEnd('.')
        return Pair(coreVersion.ifBlank { versionName }, channel.ifBlank { "unknown" })
    }

    private fun showDevChannelDescription(channel: ReleaseChannel, channelLabel: String) {
        val messageResId = when (channel) {
            ReleaseChannel.INTDEV -> R.string.about_dev_channel_desc_intdev
            ReleaseChannel.BETA -> R.string.about_dev_channel_desc_dev
            ReleaseChannel.PRE_RELEASE -> R.string.about_dev_channel_desc_prerelease
            ReleaseChannel.RELEASE -> R.string.about_dev_channel_desc_stable
            ReleaseChannel.UNKNOWN -> R.string.about_dev_channel_desc_unknown
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.about_dev_channel_dialog_title, channelLabel))
            .setMessage(messageResId)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showVersionDetailsDialog(
        versionName: String,
        versionCode: Int,
        channel: ReleaseChannel
    ) {
        val isStable = channel == ReleaseChannel.RELEASE
        if (!isStable) {
            val message = getString(
                R.string.about_version_details_message,
                versionName,
                versionCode,
                BuildConfig.BUILD_NUMBER
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_version_details_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val store = DestinationStore(this)
        var tapCount = 0
        val detailsLines = getString(
            R.string.about_version_details_message,
            versionName,
            versionCode,
            BuildConfig.BUILD_NUMBER
        ).split('\n')
        val horizontalPadding = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding)
        }
        val versionView = TextView(this).apply {
            text = detailsLines.getOrElse(0) { "" }
        }
        val internalVersionView = TextView(this).apply {
            text = detailsLines.getOrElse(1) { "" }
        }
        val buildNumberView = TextView(this).apply {
            text = detailsLines.getOrElse(2) { "" }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                tapCount += 1
                if (tapCount >= 7) {
                    store.setStableDebugMenuUnlockEnabled(true)
                    Toast.makeText(
                        this@AboutActivity,
                        R.string.debug_toggle_show,
                        Toast.LENGTH_SHORT
                    ).show()
                    tapCount = 0
                }
            }
        }

        container.addView(versionView)
        container.addView(internalVersionView)
        container.addView(buildNumberView)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_version_details_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showEasterEggDialog() {
        val imageView = ImageView(this).apply {
            setImageResource(R.drawable.annyui)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        MaterialAlertDialogBuilder(this)
            .setView(imageView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun resolveChannelLabel(channel: ReleaseChannel): String {
        return when (channel) {
            ReleaseChannel.INTDEV -> getString(R.string.about_dev_channel_value_intdev)
            ReleaseChannel.BETA -> getString(R.string.about_dev_channel_value_beta)
            ReleaseChannel.PRE_RELEASE -> getString(R.string.about_dev_channel_value_prerelease)
            ReleaseChannel.RELEASE -> getString(R.string.about_dev_channel_value_stable)
            ReleaseChannel.UNKNOWN -> getString(R.string.about_dev_channel_value_unknown)
        }
    }
}
