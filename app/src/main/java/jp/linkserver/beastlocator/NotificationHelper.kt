package jp.linkserver.beastlocator

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {
    const val CHANNEL_ID = "destination_channel"
    private const val NOTIFICATION_ID = 1001
    private const val APPROACH_NOTIFICATION_ID = 1002
    private const val LIVE_UPDATE_MIN_SDK = 36
    private const val MIN_APPROACH_UPDATE_INTERVAL_MS = 1_000L
    private var isApproachNotificationStateKnown = false
    private var isApproachNotificationVisible = false
    private var lastApproachBody: String? = null
    private var lastApproachProgress = -1
    private var lastApproachUpdateElapsedRealtime = 0L

    fun isLiveUpdateSupported(): Boolean = Build.VERSION.SDK_INT >= LIVE_UPDATE_MIN_SDK

    fun showDestinationReached(context: Context, message: String) {
        if (!DestinationStore(context).isArrivalNotificationEnabled()) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val openIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            20,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_arrow)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    @Synchronized
    fun showApproachProgress(context: Context, remainingMeters: Float, progressPercent: Int) {
        if (!isLiveUpdateSupported()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val openIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            21,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val clamped = progressPercent.coerceIn(0, 100)
        val body = context.getString(
            R.string.notification_live_body,
            GeoUtils.formatDistance(remainingMeters)
        )
        if (isApproachNotificationVisible &&
            lastApproachBody == body &&
            lastApproachProgress == clamped
        ) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (isApproachNotificationVisible &&
            now - lastApproachUpdateElapsedRealtime < MIN_APPROACH_UPDATE_INTERVAL_MS
        ) {
            return
        }

        val shown = showApproachLiveUpdateApi36(context, pendingIntent, body, clamped) ||
            showApproachProgressCompat(context, pendingIntent, body, clamped)
        if (shown) {
            isApproachNotificationStateKnown = true
            isApproachNotificationVisible = true
            lastApproachBody = body
            lastApproachProgress = clamped
            lastApproachUpdateElapsedRealtime = now
        }
    }

    @SuppressLint("MissingPermission")
    private fun showApproachProgressCompat(
        context: Context,
        pendingIntent: PendingIntent,
        body: String,
        clamped: Int
    ): Boolean {
        return runCatching {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_arrow)
                .setContentTitle(context.getString(R.string.notification_live_title))
                .setContentText(body)
                .setSubText(context.getString(R.string.notification_live_subtext))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(body)
                        .setSummaryText(
                            context.getString(R.string.notification_live_summary, clamped)
                        )
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setColor(resolveLiveNotificationColor(context))
                .setColorized(true)
                .setProgress(100, clamped, false)
                .setContentIntent(pendingIntent)
                .build()

            NotificationManagerCompat.from(context).notify(APPROACH_NOTIFICATION_ID, notification)
        }.isSuccess
    }

    @SuppressLint("MissingPermission")
    private fun showApproachLiveUpdateApi36(
        context: Context,
        pendingIntent: PendingIntent,
        body: String,
        clamped: Int
    ): Boolean {
        if (Build.VERSION.SDK_INT < LIVE_UPDATE_MIN_SDK) return false

        return try {
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_arrow)
                .setContentTitle(context.getString(R.string.notification_live_title))
                .setContentText(body)
                .setSubText(context.getString(R.string.notification_live_subtext))
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setColor(resolveLiveNotificationColor(context))
                .setColorized(true)
                // Keep classic progress in parallel so OEM SystemUI can render correctly
                // even when ProgressStyle handling is inconsistent.
                .setProgress(100, clamped, false)

            val progressStyle = Class.forName("android.app.Notification\$ProgressStyle")
                .getDeclaredConstructor()
                .newInstance()

            val progressApplied = applyProgress(progressStyle, clamped)
            if (!progressApplied) return false

            invokeAny(progressStyle, "setStyledByProgress", true)
            if (!invokeAny(builder, "setStyle", progressStyle)) return false

            invokeAny(builder, "setRequestPromotedOngoing", true)

            val notification = builder.build()
            NotificationManagerCompat.from(context).notify(APPROACH_NOTIFICATION_ID, notification)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeAny(target: Any, methodName: String, vararg args: Any): Boolean {
        val candidates = target.javaClass.methods.filter {
            it.name == methodName && it.parameterTypes.size == args.size
        }
        for (method in candidates) {
            try {
                method.invoke(target, *args)
                return true
            } catch (_: Throwable) {
                // try next candidate
            }
        }
        return false
    }

    private fun applyProgress(progressStyle: Any, clamped: Int): Boolean {
        val fraction = (clamped / 100f).coerceIn(0f, 1f)
        return invokeExact(
            progressStyle,
            "setProgress",
            arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            arrayOf(100, clamped, false)
        ) || invokeExact(
            progressStyle,
            "setProgress",
            arrayOf(Int::class.javaPrimitiveType!!),
            arrayOf(clamped)
        ) || invokeExact(
            progressStyle,
            "setProgress",
            arrayOf(Long::class.javaPrimitiveType!!),
            arrayOf(clamped.toLong())
        ) || invokeExact(
            progressStyle,
            "setProgress",
            arrayOf(Float::class.javaPrimitiveType!!),
            arrayOf(fraction)
        ) || invokeExact(
            progressStyle,
            "setProgress",
            arrayOf(Float.Companion::class.java),
            arrayOf(fraction)
        )
    }

    private fun invokeExact(
        target: Any,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any>
    ): Boolean {
        return try {
            val method = target.javaClass.getMethod(methodName, *parameterTypes)
            method.invoke(target, *args)
            true
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    fun cancelApproachProgress(context: Context) {
        if (isApproachNotificationStateKnown && !isApproachNotificationVisible) return
        val cancelled = runCatching {
            NotificationManagerCompat.from(context).cancel(APPROACH_NOTIFICATION_ID)
        }.isSuccess
        if (cancelled) {
            isApproachNotificationStateKnown = true
            isApproachNotificationVisible = false
            lastApproachBody = null
            lastApproachProgress = -1
            lastApproachUpdateElapsedRealtime = 0L
        }
    }

    private fun resolveLiveNotificationColor(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getColor(android.R.color.system_accent1_500)
        } else {
            ContextCompat.getColor(context, R.color.expressive_primary)
        }
    }
}

