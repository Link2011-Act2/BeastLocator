package jp.linkserver.beastlocator

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.materialswitch.MaterialSwitch

class ExperimentalSettingsActivity : AppCompatActivity() {
    private lateinit var store: DestinationStore
    private lateinit var distanceIntervalSoundDistanceTitle: TextView
    private lateinit var distanceIntervalSoundDistanceHelp: TextView
    private lateinit var distanceIntervalSoundDistanceLabel: TextView
    private lateinit var distanceIntervalSoundDistanceSeek: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_experimental_settings)

        store = DestinationStore(this)
        distanceIntervalSoundDistanceTitle = findViewById(R.id.distanceIntervalSoundDistanceTitle)
        distanceIntervalSoundDistanceHelp = findViewById(R.id.distanceIntervalSoundDistanceHelp)
        distanceIntervalSoundDistanceLabel = findViewById(R.id.distanceIntervalSoundDistanceLabel)
        distanceIntervalSoundDistanceSeek = findViewById(R.id.distanceIntervalSoundDistanceSeek)

        val arrivalSoundSwitch = findViewById<MaterialSwitch>(R.id.arrivalSoundSwitch)
        val distance114514SoundSwitch = findViewById<MaterialSwitch>(R.id.distance114514SoundSwitch)
        val distance114514LinkButton = findViewById<Button>(R.id.distance114514LinkButton)
        val distanceIntervalSoundSwitch = findViewById<MaterialSwitch>(R.id.distanceIntervalSoundSwitch)
        val compassSmoothingSwitch = findViewById<MaterialSwitch>(R.id.compassSmoothingSwitch)
        val nonJapaneseLanguageSwitch = findViewById<MaterialSwitch>(R.id.nonJapaneseLanguageSwitch)
        val openLanguageSettingsButton = findViewById<Button>(R.id.openLanguageSettingsButton)

        val initialDistanceIntervalSound = store.getDistanceIntervalSoundMeters()
            .coerceIn(DISTANCE_INTERVAL_SOUND_MIN_METERS, DISTANCE_INTERVAL_SOUND_MAX_METERS)
        distanceIntervalSoundDistanceSeek.progress =
            toDistanceIntervalSoundProgress(initialDistanceIntervalSound)
        updateDistanceIntervalSoundLabel(initialDistanceIntervalSound)
        distanceIntervalSoundDistanceSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val meters = fromDistanceIntervalSoundProgress(progress)
                store.setDistanceIntervalSoundMeters(meters)
                updateDistanceIntervalSoundLabel(meters)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        arrivalSoundSwitch.isChecked = store.isArrivalSoundEnabled()
        arrivalSoundSwitch.setOnCheckedChangeListener { _, isChecked ->
            store.setArrivalSoundEnabled(isChecked)
            BackgroundLocationUpdater.updateRegistration(this)
        }

        distance114514SoundSwitch.isChecked = store.isDistance114514SoundEnabled()
        distance114514SoundSwitch.setOnCheckedChangeListener { _, isChecked ->
            store.setDistance114514SoundEnabled(isChecked)
            BackgroundLocationUpdater.updateRegistration(this)
        }
        distance114514LinkButton.setOnClickListener {
            openExternalLink("https://www.nicovideo.jp/watch/sm33266722")
        }

        distanceIntervalSoundSwitch.isChecked = store.isDistanceIntervalSoundEnabled()
        applyDistanceIntervalSoundUiEnabled(distanceIntervalSoundSwitch.isChecked)
        distanceIntervalSoundSwitch.setOnCheckedChangeListener { _, isChecked ->
            store.setDistanceIntervalSoundEnabled(isChecked)
            applyDistanceIntervalSoundUiEnabled(isChecked)
            BackgroundLocationUpdater.updateRegistration(this)
        }

        compassSmoothingSwitch.isChecked = store.isCompassSmoothingEnabled()
        compassSmoothingSwitch.setOnCheckedChangeListener { _, isChecked ->
            store.setCompassSmoothingEnabled(isChecked)
        }

        nonJapaneseLanguageSwitch.isChecked = store.isNonJapaneseLanguageEnabled()
        updateLanguageSettingsButtonState(openLanguageSettingsButton, nonJapaneseLanguageSwitch.isChecked)
        nonJapaneseLanguageSwitch.setOnCheckedChangeListener { _, isChecked ->
            store.setNonJapaneseLanguageEnabled(isChecked)
            if (isChecked) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ja"))
            }
            updateLanguageSettingsButtonState(openLanguageSettingsButton, isChecked)
        }
        openLanguageSettingsButton.setOnClickListener {
            openLanguageSettings()
        }

        findViewById<ImageButton>(R.id.experimentalSettingsBackButton).setOnClickListener { finish() }
    }

    private fun updateDistanceIntervalSoundLabel(distanceMeters: Int) {
        distanceIntervalSoundDistanceLabel.text = if (distanceMeters >= 1000) {
            getString(
                R.string.distance_interval_sound_distance_value_km,
                distanceMeters / 1000f
            )
        } else {
            getString(R.string.distance_interval_sound_distance_value_m, distanceMeters)
        }
    }

    private fun toDistanceIntervalSoundProgress(distanceMeters: Int): Int {
        return ((distanceMeters - DISTANCE_INTERVAL_SOUND_MIN_METERS) / DISTANCE_INTERVAL_SOUND_STEP_METERS)
            .coerceIn(0, DISTANCE_INTERVAL_SOUND_MAX_PROGRESS)
    }

    private fun fromDistanceIntervalSoundProgress(progress: Int): Int {
        val clamped = progress.coerceIn(0, DISTANCE_INTERVAL_SOUND_MAX_PROGRESS)
        return DISTANCE_INTERVAL_SOUND_MIN_METERS + (clamped * DISTANCE_INTERVAL_SOUND_STEP_METERS)
    }

    private fun applyDistanceIntervalSoundUiEnabled(enabled: Boolean) {
        distanceIntervalSoundDistanceSeek.isEnabled = enabled
        distanceIntervalSoundDistanceTitle.alpha = if (enabled) 1f else 0.5f
        distanceIntervalSoundDistanceHelp.alpha = if (enabled) 1f else 0.5f
        distanceIntervalSoundDistanceLabel.alpha = if (enabled) 1f else 0.5f
        distanceIntervalSoundDistanceSeek.alpha = if (enabled) 1f else 0.5f
    }

    private fun updateLanguageSettingsButtonState(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.5f
    }

    private fun openLanguageSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(Settings.ACTION_APP_LOCALE_SETTINGS).apply {
                putExtra("android.provider.extra.APP_PACKAGE", packageName)
                data = Uri.fromParts("package", packageName, null)
            }
        } else {
            Intent(Settings.ACTION_LOCALE_SETTINGS)
        }
        runCatching {
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, R.string.language_settings_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openExternalLink(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, R.string.external_link_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val DISTANCE_INTERVAL_SOUND_MIN_METERS = 100
        private const val DISTANCE_INTERVAL_SOUND_MAX_METERS = 5000
        private const val DISTANCE_INTERVAL_SOUND_STEP_METERS = 100
        private const val DISTANCE_INTERVAL_SOUND_MAX_PROGRESS =
            (DISTANCE_INTERVAL_SOUND_MAX_METERS - DISTANCE_INTERVAL_SOUND_MIN_METERS) / DISTANCE_INTERVAL_SOUND_STEP_METERS
    }
}
