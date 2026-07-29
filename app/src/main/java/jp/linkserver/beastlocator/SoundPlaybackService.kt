package jp.linkserver.beastlocator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class SoundPlaybackService : Service() {
    private var player: MediaPlayer? = null
    private var currentPriority: Int = 0
    private var audioFocusRequest: AudioFocusRequest? = null

    private val playbackAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
    }

    private val audioManager: AudioManager? by lazy {
        getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.runCatching { setVolume(1f, 1f) }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                player?.runCatching { setVolume(DUCK_VOLUME, DUCK_VOLUME) }
            }

            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> finishPlayback()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rawResId = intent?.getIntExtra(EXTRA_RAW_RES_ID, 0) ?: 0
        val priority = intent?.getIntExtra(EXTRA_PRIORITY, 0) ?: 0
        if (rawResId == 0) {
            finishPlayback()
            return START_NOT_STICKY
        }

        val foregroundStarted = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification())
        }.onFailure { error ->
            Log.e(TAG, "Unable to enter foreground for sound playback", error)
        }.isSuccess
        if (!foregroundStarted) {
            finishPlayback()
            return START_NOT_STICKY
        }
        val isPlayingNow = player != null
        if (isPlayingNow && priority <= currentPriority) {
            return START_NOT_STICKY
        }
        playRaw(rawResId, priority)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releasePlaybackResources()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun playRaw(rawResId: Int, priority: Int) {
        releasePlaybackResources()
        if (!requestAudioFocus()) {
            finishPlayback()
            return
        }

        // This overload applies AudioAttributes before MediaPlayer is prepared.
        val created = runCatching {
            MediaPlayer.create(this, rawResId, playbackAudioAttributes, AUDIO_SESSION_ID_NONE)
        }.onFailure { error ->
            Log.e(TAG, "Unable to create MediaPlayer", error)
        }.getOrNull() ?: run {
            finishPlayback()
            return
        }

        currentPriority = priority
        player = created
        created.setOnCompletionListener { completedPlayer ->
            if (player === completedPlayer) {
                finishPlayback()
            }
        }
        created.setOnErrorListener { failedPlayer, what, extra ->
            Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
            if (player === failedPlayer) {
                finishPlayback()
            }
            true
        }
        runCatching { created.start() }
            .onFailure { error ->
                Log.e(TAG, "Unable to start MediaPlayer", error)
                if (player === created) {
                    finishPlayback()
                }
            }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(playbackAudioAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
        val granted = runCatching {
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.onFailure { error ->
            Log.w(TAG, "Audio focus request failed", error)
        }.getOrDefault(false)
        if (granted) {
            audioFocusRequest = request
        }
        return granted
    }

    private fun releasePlaybackResources() {
        val playerToRelease = player
        player = null
        currentPriority = 0
        playerToRelease?.runCatching {
            setOnCompletionListener(null)
            setOnErrorListener(null)
            if (isPlaying) stop()
        }
        playerToRelease?.runCatching { release() }

        val request = audioFocusRequest
        audioFocusRequest = null
        val manager = audioManager
        if (manager != null && request != null) {
            runCatching { manager.abandonAudioFocusRequest(request) }
        }
    }

    private fun finishPlayback() {
        releasePlaybackResources()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_arrow)
            .setContentTitle(getString(R.string.sound_playback_notification_title))
            .setContentText(getString(R.string.sound_playback_notification_body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sound_playback_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "sound_playback_channel"
        private const val TAG = "SoundPlaybackService"
        private const val NOTIFICATION_ID = 1515
        private const val EXTRA_RAW_RES_ID = "extra_raw_res_id"
        private const val EXTRA_PRIORITY = "extra_priority"
        private const val AUDIO_SESSION_ID_NONE = 0
        private const val DUCK_VOLUME = 0.2f

        fun start(context: Context, rawResId: Int, priority: Int) {
            if (rawResId == 0) return
            val intent = Intent(context, SoundPlaybackService::class.java).apply {
                putExtra(EXTRA_RAW_RES_ID, rawResId)
                putExtra(EXTRA_PRIORITY, priority)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { error ->
                Log.e(TAG, "Unable to start sound playback service", error)
            }
        }
    }
}
