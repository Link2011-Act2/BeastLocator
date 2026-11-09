package jp.linkserver.beastlocator

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import jp.linkserver.goldy.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine

/** XML/View presentation only. All APK transfer and installation authority comes from Gold. */
class UpdateActivity : AppCompatActivity() {
    private lateinit var model: UpdateDownloadViewModel
    private lateinit var markwon: Markwon
    private lateinit var button: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var normalProgressTint: ColorStateList? = null
    private var normalTrackTint: ColorStateList? = null
    private var normalIndeterminateTint: ColorStateList? = null
    private var incoming: UpdateCandidate? = null
    private var candidate: UpdateCandidate? = null
    private var nextAction: UpdateAction? = null
    private var checkOperation: AppUpdateManager.Operation? = null
    private var expanded = false
    private var renderedHeader: Pair<UpdateCandidate?, String?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        SystemBarInsetApplier.apply(findViewById(R.id.updateRoot))
        model = ViewModelProvider(this)[UpdateDownloadViewModel::class.java]
        markwon = Markwon.builder(this).usePlugin(TablePlugin.create(this)).build()
        button = findViewById(R.id.updateDownloadButton)
        progress = findViewById(R.id.updateProgressBar)
        normalProgressTint = progress.progressTintList
        normalTrackTint = progress.progressBackgroundTintList
        normalIndeterminateTint = progress.indeterminateTintList
        status = findViewById(R.id.updateStatusText)
        expanded = savedInstanceState?.getBoolean("history_expanded") ?: false
        findViewById<ImageButton>(R.id.updateBackButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.updateRecheckButton).setOnClickListener { checkAgain() }
        findViewById<Button>(R.id.updateCancelPausedButton).setOnClickListener { model.cancel() }
        findViewById<Button>(R.id.updateOpenReleaseButton).setOnClickListener {
            candidate?.release?.url?.let { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                .onFailure { status.setText(R.string.external_link_open_failed) } }
        }
        button.setOnClickListener {
            nextAction?.let { model.action(candidate, it) } ?: checkAgain()
        }
        findViewById<View>(R.id.updateIntermediateNotesToggle).setOnClickListener {
            expanded = !expanded
            renderHistory()
        }
        incoming = readCandidate(intent)
        incoming?.let(model::selectCandidate)
        lifecycleScope.launch {
            try {
                val host = AppUpdateManager.host(this@UpdateActivity)
                host.initialize()
                if (incoming == null) incoming = host.engine.state.value.availableUpdate
                incoming?.let(model::selectCandidate)
                renderedHeader = null
                render(model.state.value)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { status.setText(R.string.goldy_update_restore_failed) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(model.state, model.selection) { state, selected -> state to selected }.collect { (state, selected) ->
                    candidate = selected ?: incoming
                    render(state)
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming = readCandidate(intent) ?: incoming
        incoming?.let(model::selectCandidate)
        renderedHeader = null
        render(model.state.value)
    }
    override fun onResume() { super.onResume(); model.onForegroundChanged(true) }
    override fun onPause() { model.onForegroundChanged(false); super.onPause() }
    override fun onDestroy() { checkOperation?.cancel(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("history_expanded", expanded)
        super.onSaveInstanceState(outState)
    }
    private fun checkAgain() {
        if (checkOperation?.isActive == true) return
        button.isEnabled = false
        status.setText(R.string.update_checking)
        checkOperation = AppUpdateManager.checkForUpdate(this, true) { result ->
            checkOperation = null
            result.onSuccess { update ->
                incoming = update
                model.selectCandidate(update)
                candidate = model.selection.value ?: incoming
                render(model.state.value)
            }.onFailure {
                button.isEnabled = true
                status.setText(R.string.goldy_update_failed)
            }
        }
    }
    internal fun render(state: UpdateDownloadState) {
        candidate = model.selection.value ?: incoming
        val selected = candidate
        val prepared = state.preparedRequest.takeIf { selected == null }
        val active = prepared != null || state.releaseIdentity == transferIdentity(selected)
        val transfer = if (state.restoring) UpdateTransferState.PreparingInstall else if (active) state.transfer else UpdateTransferState.Idle
        val header = selected to prepared?.metadata?.versionName
        if (renderedHeader != header) {
            renderedHeader = header
            renderHeader(selected, prepared?.metadata?.versionName)
        }
        progress.visibility = View.GONE
        setProgressColors(paused = false)
        findViewById<Button>(R.id.updateCancelPausedButton).visibility =
            if (active && state.waiting && !state.restoring && transfer is UpdateTransferState.Failed)
                View.VISIBLE else View.GONE
        findViewById<ImageButton>(R.id.updateRecheckButton).isEnabled = !state.busy && !state.restoring
        button.visibility = View.VISIBLE
        button.isEnabled = true
        nextAction = null
        when (transfer) {
            is UpdateTransferState.Downloading -> {
                nextAction = UpdateAction.CancelDownload
                button.setText(R.string.goldy_update_cancel_download)
                showProgress(transfer.progress, paused = false, state.message)
            }
            UpdateTransferState.Verifying, UpdateTransferState.PreparingInstall -> {
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = true
                button.isEnabled = false
                button.setText(R.string.goldy_transfer_install)
                status.setText(when {
                    transfer == UpdateTransferState.Verifying -> R.string.goldy_update_verifying
                    state.restoring && selected == null && prepared == null -> R.string.goldy_update_loading
                    else -> R.string.goldy_update_preparing
                })
            }
            UpdateTransferState.PermissionRequired -> {
                nextAction = UpdateAction.OpenInstallPermissionSettings
                button.setText(R.string.goldy_transfer_allow_install)
                status.setText(R.string.goldy_transfer_permission_ready)
            }
            UpdateTransferState.Ready -> {
                nextAction = UpdateAction.Install
                button.setText(R.string.goldy_transfer_install)
                status.text = state.message ?: getString(R.string.goldy_update_ready)
            }
            UpdateTransferState.HandedOff -> {
                button.setText(R.string.goldy_transfer_install)
                button.isEnabled = false
                status.setText(R.string.goldy_update_handed_off)
            }
            is UpdateTransferState.Failed -> {
                nextAction = transfer.retry
                button.setText(when (transfer.retry) {
                    null -> R.string.goldy_update_retry
                    UpdateAction.Install -> R.string.goldy_transfer_install
                    UpdateAction.OpenInstallPermissionSettings -> R.string.goldy_transfer_allow_install
                    else -> if (state.waiting) R.string.goldy_transfer_restart else R.string.goldy_transfer_download
                })
                status.text = if (state.waiting) getString(R.string.goldy_transfer_paused) else transfer.message
                if (state.waiting) {
                    progress.visibility = View.VISIBLE
                    progress.isIndeterminate = false
                    progress.progress = 0
                    progress.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setProgressColors(paused = true)
                    state.retainedProgress?.let { showProgress(it, paused = true, message = null) }
                }
            }
            else -> {
                val apk = selected?.release?.let(ApkUpdateProfile.selection::select)
                nextAction = if (apk != null) UpdateAction.Download else null
                button.setText(if (apk != null) R.string.goldy_transfer_download else R.string.goldy_update_retry)
                status.setText(if (transfer == UpdateTransferState.Cancelled) R.string.goldy_update_cancelled else
                    if (selected != null && apk == null) R.string.goldy_update_no_apk else R.string.goldy_update_idle)
            }
        }
    }
    private fun renderHeader(selected: UpdateCandidate?, preparedName: String?) {
        findViewById<TextView>(R.id.updateTagText).text = selected?.release?.tag ?: preparedName.orEmpty()
        findViewById<TextView>(R.id.updateChannelText).text = selected?.release?.tag?.let { channelLabel(ReleaseChannelDetector.detect(it)) }.orEmpty()
        markwon.setMarkdown(findViewById(R.id.updateReleaseNotesText), selected?.release?.notes?.takeIf(String::isNotBlank)
            ?: getString(R.string.update_release_notes_empty))
        findViewById<TextView>(R.id.updateAssetText).text = selected?.release?.let(ApkUpdateProfile.selection::select)?.name.orEmpty()
        findViewById<Button>(R.id.updateOpenReleaseButton).visibility = if (selected != null) View.VISIBLE else View.GONE
        renderHistory()
    }
    private fun showProgress(value: ApkProgress, paused: Boolean, message: String?) {
        progress.visibility = View.VISIBLE
        val fraction = DownloadProgressFormatter.fraction(value)
        setProgressColors(paused)
        progress.isIndeterminate = fraction == null && !paused
        progress.importantForAccessibility = if (paused && fraction == null)
            View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        progress.max = 1000
        progress.progress = ((fraction ?: 0f) * 1000).toInt()
        val text = DownloadProgressFormatter.format(value, resources.configuration.locales[0])
        val explanation = if (paused) getString(R.string.goldy_transfer_paused) else message
        status.text = explanation?.let { "$it\n$text" } ?: text
    }
    private fun setProgressColors(paused: Boolean) {
        val error = if (paused) ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(
            progress, android.R.attr.colorError)) else normalProgressTint
        progress.progressTintList = error
        progress.indeterminateTintList = if (paused) error else normalIndeterminateTint
        progress.progressBackgroundTintList = if (paused) ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(progress, com.google.android.material.R.attr.colorErrorContainer)) else normalTrackTint
    }
    private fun renderHistory() {
        val selected = candidate
        val history = selected?.intermediateReleases.orEmpty()
        val cached = runCatching { AppUpdateManager.host(this).engine.state.value.availableUpdate }
            .getOrNull()?.takeIf { it.release.id == selected?.release?.id }?.intermediateReleases.orEmpty()
        val notes = (history.ifEmpty { cached }).joinToString("\n\n") { "## ${it.tag}\n\n${it.notes}" }
            .ifBlank { if (readCandidate(intent)?.release?.id == selected?.release?.id) intent.getStringExtra(HISTORY).orEmpty() else "" }
        val count = history.ifEmpty { cached }.size.takeIf { it > 0 } ?: intent.getIntExtra(HISTORY_COUNT, 0)
        findViewById<View>(R.id.updateIntermediateNotesSection).visibility = if (notes.isNotBlank() && count > 0) View.VISIBLE else View.GONE
        val summary = resources.getQuantityString(R.plurals.update_skipped_versions_count, count, count)
        findViewById<TextView>(R.id.updateIntermediateNotesToggleText).text = summary
        findViewById<TextView>(R.id.updateIntermediateNotesText).apply {
            visibility = if (expanded) View.VISIBLE else View.GONE
            markwon.setMarkdown(this, notes)
        }
        findViewById<View>(R.id.updateIntermediateNotesChevron).rotation = if (expanded) 180f else 0f
        findViewById<View>(R.id.updateIntermediateNotesToggle).contentDescription = getString(
            if (expanded) R.string.update_skipped_versions_collapse else R.string.update_skipped_versions_expand, summary)
    }
    private fun channelLabel(channel: ReleaseChannel): String = getString(when (channel) {
        ReleaseChannel.INTDEV -> R.string.about_dev_channel_value_intdev
        ReleaseChannel.BETA -> R.string.about_dev_channel_value_beta
        ReleaseChannel.PRE_RELEASE -> R.string.about_dev_channel_value_prerelease
        ReleaseChannel.RC -> R.string.about_dev_channel_value_rc
        ReleaseChannel.RELEASE -> R.string.about_dev_channel_value_stable
        ReleaseChannel.UNKNOWN -> R.string.about_dev_channel_value_unknown
    })
    companion object {
        private const val SNAPSHOT = "goldy_candidate"
        private const val HISTORY = "goldy_history"
        private const val HISTORY_COUNT = "goldy_history_count"
        fun createIntent(context: Context, updateInfo: UpdateCandidate): Intent {
            val snapshot = UpdateTransferSnapshot.encode(updateInfo.copy(release = updateInfo.release.copy(notes = updateInfo.release.notes.take(80000))), false)
            require(snapshot.length <= 200000)
            return Intent(context, UpdateActivity::class.java).putExtra(SNAPSHOT, snapshot)
                .putExtra(HISTORY, updateInfo.intermediateReleases.joinToString("\n\n") { "## ${it.tag}\n\n${it.notes}" }.take(80000))
                .putExtra(HISTORY_COUNT, updateInfo.intermediateReleases.size)
        }
        private fun readCandidate(intent: Intent): UpdateCandidate? = intent.getStringExtra(SNAPSHOT)?.let {
            runCatching { UpdateTransferSnapshot.decode(it, false) }.getOrNull()
        }
    }
}
