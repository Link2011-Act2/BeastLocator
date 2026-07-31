package jp.linkserver.beastlocator

import android.app.Application
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.android.material.color.DynamicColors

class RandomDirectionApp : Application() {
    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(base)
        if (base != null) {
            AppLanguageController.applyPolicy(base)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLanguageController.applyPolicy(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
        registerProcessVisibilityCallbacks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NotificationHelper.CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun registerProcessVisibilityCallbacks() {
        val handler = Handler(Looper.getMainLooper())
        var startedActivityCount = 0
        var startedMainActivityCount = 0
        var backgroundGeneration = 0L

        fun reconcileForegroundActivityMonitor() {
            if (startedActivityCount > 0 && startedMainActivityCount == 0) {
                ForegroundAppLocationMonitor.start(this@RandomDirectionApp)
            } else {
                ForegroundAppLocationMonitor.stop()
            }
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount += 1
                if (activity is MainActivity) {
                    startedMainActivityCount += 1
                }
                backgroundGeneration += 1L
                if (startedActivityCount == 1) {
                    BackgroundLocationUpdater.setAppInForeground(this@RandomDirectionApp, true)
                }
                reconcileForegroundActivityMonitor()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (activity is MainActivity) {
                    startedMainActivityCount = (startedMainActivityCount - 1).coerceAtLeast(0)
                }
                if (startedActivityCount != 0) {
                    reconcileForegroundActivityMonitor()
                    return
                }
                val generation = ++backgroundGeneration
                handler.postDelayed({
                    if (startedActivityCount == 0 && generation == backgroundGeneration) {
                        ForegroundAppLocationMonitor.stop()
                        BackgroundLocationUpdater.setAppInForeground(this@RandomDirectionApp, false)
                    }
                }, BACKGROUND_TRANSITION_GRACE_MILLIS)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        private const val BACKGROUND_TRANSITION_GRACE_MILLIS = 1_500L
    }
}

