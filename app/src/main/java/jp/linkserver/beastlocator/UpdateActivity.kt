package jp.linkserver.beastlocator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import java.util.Locale

class UpdateActivity : AppCompatActivity() {
    private lateinit var updateInfo: AppUpdateInfo
    private lateinit var downloadButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var markwon: Markwon
    private lateinit var downloadViewModel: UpdateDownloadViewModel
    private lateinit var intermediateNotesToggle: View
    private lateinit var intermediateNotesToggleText: TextView
    private lateinit var intermediateNotesText: TextView
    private lateinit var intermediateNotesChevron: View
    private var intermediateNotesExpanded = false
    private var intermediateNotesSummary = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadViewModel = ViewModelProvider(this)[UpdateDownloadViewModel::class.java]
        updateInfo = readUpdateInfo(intent) ?: run {
            finish()
            return
        }
        setContentView(R.layout.activity_update)
        applySystemBarInsets()
        markwon = Markwon.builder(this)
            .usePlugin(TablePlugin.create(this))
            .build()

        findViewById<ImageButton>(R.id.updateBackButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.updateTagText).text = updateInfo.tagName
        findViewById<TextView>(R.id.updateChannelText).text = channelLabel(updateInfo.channel)
        renderMarkdown(
            findViewById(R.id.updateReleaseNotesText),
            updateInfo.releaseNotes.ifBlank { getString(R.string.update_release_notes_empty) }
        )
        setupIntermediateNotes(savedInstanceState)
        findViewById<TextView>(R.id.updateAssetText).text = updateInfo.apkAssetName?.let {
            getString(R.string.update_asset_name, it)
        } ?: getString(R.string.update_apk_not_found)

        progressBar = findViewById(R.id.updateProgressBar)
        statusText = findViewById(R.id.updateStatusText)
        downloadButton = findViewById(R.id.updateDownloadButton)
        downloadButton.visibility =
            if (updateInfo.apkDownloadUrl == null) View.GONE else View.VISIBLE
        downloadButton.setText(R.string.update_download_install_button)
        downloadButton.setOnClickListener {
            if (downloadViewModel.state.value is UpdateDownloadState.Downloading) {
                cancelDownload()
            } else if (updateInfo.apkDownloadUrl == null) {
                openReleasePage()
            } else {
                prepareDownload()
            }
        }
        findViewById<Button>(R.id.updateOpenReleaseButton).setOnClickListener {
            openReleasePage()
        }
        downloadViewModel.state.observe(this, ::renderDownloadState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_INTERMEDIATE_NOTES_EXPANDED, intermediateNotesExpanded)
    }

    override fun onResume() {
        super.onResume()
        if (downloadViewModel.consumeInstallSettingsReturn()) {
            if (AppUpdateManager.canRequestPackageInstalls(this)) {
                downloadViewModel.clearInstallPermissionRequest()
                startDownload()
            } else {
                statusText.setText(R.string.update_install_permission_required)
            }
        }
    }

    private fun prepareDownload() {
        if (!AppUpdateManager.canRequestPackageInstalls(this)) {
            downloadViewModel.beginInstallPermissionRequest()
            statusText.setText(R.string.update_install_permission_guide)
            runCatching { AppUpdateManager.openUnknownAppInstallSettings(this) }
                .onFailure {
                    downloadViewModel.markInstallSettingsOpenFailed()
                    statusText.setText(R.string.update_install_settings_failed)
            }
            return
        }
        downloadViewModel.clearInstallPermissionRequest()
        startDownload()
    }

    private fun startDownload() {
        downloadViewModel.startDownload(updateInfo)
    }

    private fun renderDownloadState(state: UpdateDownloadState) {
        when (state) {
            UpdateDownloadState.Idle -> {
                progressBar.visibility = View.GONE
                downloadButton.setText(R.string.update_download_install_button)
            }
            is UpdateDownloadState.Downloading -> showProgress(state.progress)
            is UpdateDownloadState.Downloaded -> openDownloadedApk(state)
            is UpdateDownloadState.Failed -> showFailure(state.error)
            UpdateDownloadState.Cancelled -> {
                progressBar.visibility = View.GONE
                statusText.setText(R.string.update_download_cancelled)
                downloadButton.setText(R.string.update_download_install_button)
            }
        }
    }

    private fun showProgress(progress: ApkDownloadProgress?) {
        progressBar.visibility = View.VISIBLE
        downloadButton.setText(R.string.update_cancel_download_button)
        if (progress == null) {
            progressBar.isIndeterminate = true
            statusText.setText(R.string.update_downloading)
            return
        }
        val total = progress.totalBytes
        if (total != null && total > 0L) {
            val percent = ((progress.downloadedBytes * 100L) / total).toInt().coerceIn(0, 100)
            progressBar.isIndeterminate = false
            progressBar.progress = percent
            statusText.text = getString(
                R.string.update_downloading_progress,
                percent,
                progress.downloadedBytes.toMegabytesText(),
                total.toMegabytesText()
            )
        } else {
            progressBar.isIndeterminate = true
            statusText.text = getString(
                R.string.update_downloading_progress_unknown_total,
                progress.downloadedBytes.toMegabytesText()
            )
        }
    }

    private fun openDownloadedApk(state: UpdateDownloadState.Downloaded) {
        progressBar.visibility = View.GONE
        statusText.setText(R.string.update_opening_installer)
        downloadButton.setText(R.string.update_download_install_button)
        val apkFile = downloadViewModel.claimDownloadedFile(state) ?: return
        runCatching { AppUpdateManager.openPackageInstaller(this, apkFile) }
            .onFailure(downloadViewModel::reportInstallerFailure)
    }

    private fun showFailure(error: Throwable) {
        progressBar.visibility = View.GONE
        downloadButton.setText(R.string.update_download_install_button)
        statusText.text = getString(
            R.string.update_download_failed,
            error.localizedMessage ?: error.javaClass.simpleName
        )
    }

    private fun cancelDownload() {
        downloadViewModel.cancelDownload()
    }

    private fun openReleasePage() {
        val url = updateInfo.releaseUrl
        if (url.isBlank()) {
            statusText.setText(R.string.update_release_page_unavailable)
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            .onFailure { statusText.setText(R.string.external_link_open_failed) }
    }

    private fun renderMarkdown(view: TextView, markdown: String) {
        view.movementMethod = LinkMovementMethod.getInstance()
        markwon.setMarkdown(view, markdown)
    }

    private fun setupIntermediateNotes(savedInstanceState: Bundle?) {
        val section = findViewById<View>(R.id.updateIntermediateNotesSection)
        val notes = intent.getStringExtra(EXTRA_INTERMEDIATE_NOTES).orEmpty()
        val skippedVersionCount = intent.getIntExtra(EXTRA_INTERMEDIATE_COUNT, 0)
        if (notes.isBlank() || skippedVersionCount <= 0) {
            section.visibility = View.GONE
            return
        }

        section.visibility = View.VISIBLE
        intermediateNotesToggle = findViewById(R.id.updateIntermediateNotesToggle)
        intermediateNotesToggleText = findViewById(R.id.updateIntermediateNotesToggleText)
        intermediateNotesText = findViewById(R.id.updateIntermediateNotesText)
        intermediateNotesChevron = findViewById(R.id.updateIntermediateNotesChevron)
        intermediateNotesSummary = resources.getQuantityString(
            R.plurals.update_skipped_versions_count,
            skippedVersionCount,
            skippedVersionCount
        )
        intermediateNotesToggleText.text = intermediateNotesSummary
        renderMarkdown(intermediateNotesText, notes)
        setIntermediateNotesExpanded(
            savedInstanceState?.getBoolean(STATE_INTERMEDIATE_NOTES_EXPANDED) == true
        )
        intermediateNotesToggle.setOnClickListener {
            setIntermediateNotesExpanded(!intermediateNotesExpanded)
        }
    }

    private fun setIntermediateNotesExpanded(expanded: Boolean) {
        intermediateNotesExpanded = expanded
        intermediateNotesText.visibility = if (expanded) View.VISIBLE else View.GONE
        intermediateNotesChevron.rotation = if (expanded) 180f else 0f
        intermediateNotesToggle.contentDescription = getString(
            if (expanded) {
                R.string.update_skipped_versions_collapse
            } else {
                R.string.update_skipped_versions_expand
            },
            intermediateNotesSummary
        )
    }

    private fun applySystemBarInsets() {
        SystemBarInsetApplier.apply(findViewById(R.id.updateRoot))
    }

    private fun channelLabel(channel: ReleaseChannel): String = when (channel) {
        ReleaseChannel.INTDEV -> getString(R.string.about_dev_channel_value_intdev)
        ReleaseChannel.BETA -> getString(R.string.about_dev_channel_value_beta)
        ReleaseChannel.PRE_RELEASE -> getString(R.string.about_dev_channel_value_prerelease)
        ReleaseChannel.RELEASE -> getString(R.string.about_dev_channel_value_stable)
        ReleaseChannel.UNKNOWN -> getString(R.string.about_dev_channel_value_unknown)
    }

    private fun Long.toMegabytesText(): String =
        String.format(Locale.US, "%.1f", this / (1024f * 1024f))

    companion object {
        private const val EXTRA_TAG = "update_tag"
        private const val EXTRA_CHANNEL = "update_channel"
        private const val EXTRA_RELEASE_NOTES = "update_release_notes"
        private const val EXTRA_RELEASE_URL = "update_release_url"
        private const val EXTRA_APK_NAME = "update_apk_name"
        private const val EXTRA_APK_URL = "update_apk_url"
        private const val EXTRA_PRERELEASE = "update_prerelease"
        private const val EXTRA_INTERMEDIATE_NOTES = "update_intermediate_notes"
        private const val EXTRA_INTERMEDIATE_COUNT = "update_intermediate_count"
        private const val STATE_INTERMEDIATE_NOTES_EXPANDED = "intermediate_notes_expanded"
        private const val MAX_NOTES_EXTRA_CHARS = 80_000

        fun createIntent(context: Context, updateInfo: AppUpdateInfo): Intent {
            val intermediate = updateInfo.intermediateReleaseNotes.joinToString("\n\n") {
                "## ${it.tagName} (${it.channel.canonicalName})\n\n${it.releaseNotes}"
            }.take(MAX_NOTES_EXTRA_CHARS)
            return Intent(context, UpdateActivity::class.java).apply {
                putExtra(EXTRA_TAG, updateInfo.tagName)
                putExtra(EXTRA_CHANNEL, updateInfo.channel.name)
                putExtra(EXTRA_RELEASE_NOTES, updateInfo.releaseNotes.take(MAX_NOTES_EXTRA_CHARS))
                putExtra(EXTRA_RELEASE_URL, updateInfo.releaseUrl)
                putExtra(EXTRA_APK_NAME, updateInfo.apkAssetName)
                putExtra(EXTRA_APK_URL, updateInfo.apkDownloadUrl)
                putExtra(EXTRA_PRERELEASE, updateInfo.isPrerelease)
                putExtra(EXTRA_INTERMEDIATE_NOTES, intermediate)
                putExtra(EXTRA_INTERMEDIATE_COUNT, updateInfo.intermediateReleaseNotes.size)
            }
        }

        private fun readUpdateInfo(intent: Intent): AppUpdateInfo? {
            val tag = intent.getStringExtra(EXTRA_TAG)?.takeIf { it.isNotBlank() } ?: return null
            val channel = intent.getStringExtra(EXTRA_CHANNEL)
                ?.let { runCatching { ReleaseChannel.valueOf(it) }.getOrNull() }
                ?: ReleaseChannel.UNKNOWN
            return AppUpdateInfo(
                tagName = tag,
                channel = channel,
                releaseNotes = intent.getStringExtra(EXTRA_RELEASE_NOTES).orEmpty(),
                releaseUrl = intent.getStringExtra(EXTRA_RELEASE_URL).orEmpty(),
                apkAssetName = intent.getStringExtra(EXTRA_APK_NAME),
                apkDownloadUrl = intent.getStringExtra(EXTRA_APK_URL),
                isPrerelease = intent.getBooleanExtra(EXTRA_PRERELEASE, false)
            )
        }
    }
}
