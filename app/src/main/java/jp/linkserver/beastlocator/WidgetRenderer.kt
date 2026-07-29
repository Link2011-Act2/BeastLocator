package jp.linkserver.beastlocator

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.util.Locale

object WidgetRenderer {
    private const val ARROW_IMAGE_FORWARD_OFFSET_DEGREES = 45f
    private const val WIDGET_ARROW_STEP_DEGREES = 2f
    private val renderedSignatures = mutableMapOf<WidgetKey, RenderSignature>()

    private data class WidgetKey(val layoutRes: Int, val appWidgetId: Int)

    private data class RenderSignature(
        val title: String?,
        val distance: String,
        val isArrived: Boolean,
        val arrowRotation: Float?
    )

    fun render(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        layoutRes: Int,
        forceUpdate: Boolean = true
    ) {
        if (appWidgetIds.isEmpty()) return

        val store = DestinationStore(context)
        val current = store.getLastKnownLocation()
        val target = store.getDestination()
        val heading = store.getLastKnownHeading()
        val widgetBearingMode = store.getWidgetBearingMode()
        val isArrived = store.isDestinationAnswered()
        val isSmallLayout = layoutRes == R.layout.widget_small
        val arrivalText = context.getString(
            if (isSmallLayout) R.string.widget_arrival_short else R.string.arrival_title
        )
        val normalDistanceText = if (current == null) {
            context.getString(R.string.widget_distance_placeholder)
        } else if (!isValidDestination(current) || !isValidDestination(target)) {
            context.getString(R.string.widget_distance_placeholder)
        } else {
            val d = runCatching { GeoUtils.distanceMeters(current, target) }.getOrDefault(0f)
            formatWidgetDistance(d)
        }
        val absoluteBearing = if (current == null) {
            0f
        } else if (!isValidDestination(current) || !isValidDestination(target)) {
            0f
        } else {
            runCatching { GeoUtils.bearingDegrees(current, target) }.getOrDefault(0f)
        }
        val displayBearing = if (current == null) {
            0f
        } else {
            if (widgetBearingMode == WidgetBearingMode.RELATIVE && heading != null) {
                normalizeTo360(absoluteBearing - heading)
            } else {
                absoluteBearing
            }
        }
        val titleText = if (current == null) {
            context.getString(R.string.widget_waiting_location)
        } else {
            context.getString(
                R.string.direction_label,
                GeoUtils.cardinalFromBearing(absoluteBearing)
            )
        }
        val arrowRotation = quantizeRotation(
            displayBearing - ARROW_IMAGE_FORWARD_OFFSET_DEGREES
        )
        val signature = RenderSignature(
            title = titleText.toString().takeUnless { isSmallLayout },
            distance = (if (isArrived) arrivalText else normalDistanceText).toString(),
            isArrived = isArrived,
            arrowRotation = arrowRotation.takeUnless { isArrived }
        )

        appWidgetIds.forEach { id ->
            val key = WidgetKey(layoutRes, id)
            val shouldUpdate = synchronized(renderedSignatures) {
                if (!forceUpdate && renderedSignatures[key] == signature) {
                    false
                } else {
                    renderedSignatures[key] = signature
                    true
                }
            }
            if (!shouldUpdate) return@forEach

            val isSmall = isSmallLayout
            val views = RemoteViews(context.packageName, layoutRes)
            if (isSmall) {
                views.setViewVisibility(R.id.widgetTitle, android.view.View.GONE)
            } else {
                views.setViewVisibility(R.id.widgetTitle, android.view.View.VISIBLE)
                views.setTextViewText(R.id.widgetTitle, titleText)
            }
            if (isSmall) {
                views.setTextViewText(
                    R.id.widgetDistance,
                    if (isArrived) arrivalText else normalDistanceText
                )
            } else if (isArrived) {
                views.setViewVisibility(R.id.widgetDistance, android.view.View.GONE)
                views.setViewVisibility(R.id.widgetArrivalDistance, android.view.View.VISIBLE)
                views.setTextViewText(R.id.widgetArrivalDistance, arrivalText)
            } else {
                views.setViewVisibility(R.id.widgetDistance, android.view.View.VISIBLE)
                views.setViewVisibility(R.id.widgetArrivalDistance, android.view.View.GONE)
                views.setTextViewText(R.id.widgetDistance, normalDistanceText)
            }
            if (isArrived) {
                views.setViewVisibility(R.id.widgetArrow, android.view.View.GONE)
                views.setViewVisibility(R.id.widgetParty, android.view.View.VISIBLE)
                views.setTextViewText(R.id.widgetParty, context.getString(R.string.widget_arrival_celebration))
            } else {
                views.setViewVisibility(R.id.widgetArrow, android.view.View.VISIBLE)
                views.setViewVisibility(R.id.widgetParty, android.view.View.GONE)
                views.setFloat(R.id.widgetArrow, "setRotation", arrowRotation)
            }
            if (!isSmall) {
                views.setTextColor(
                    R.id.widgetTitle,
                    ContextCompat.getColor(context, R.color.expressive_on_surface_variant)
                )
            }
            views.setTextColor(
                R.id.widgetDistance,
                ContextCompat.getColor(context, R.color.expressive_on_surface)
            )
            if (!isSmall) {
                views.setTextColor(
                    R.id.widgetArrivalDistance,
                    ContextCompat.getColor(context, R.color.expressive_on_surface)
                )
            }
            views.setOnClickPendingIntent(
                R.id.widgetRoot,
                PendingIntent.getActivity(
                    context,
                    id,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            runCatching { appWidgetManager.updateAppWidget(id, views) }
                .onFailure {
                    synchronized(renderedSignatures) {
                        if (renderedSignatures[key] == signature) {
                            renderedSignatures.remove(key)
                        }
                    }
                }
        }
    }

    fun refreshAllWidgets(context: Context) {
        runCatching {
            val manager = AppWidgetManager.getInstance(context)
            val smallIds = manager.getAppWidgetIds(
                ComponentName(context, DestinationWidgetProvider::class.java)
            )
            val largeIds = manager.getAppWidgetIds(
                ComponentName(context, DestinationWidgetProviderLarge::class.java)
            )
            if (smallIds.isEmpty() && largeIds.isEmpty()) {
                synchronized(renderedSignatures) { renderedSignatures.clear() }
                return@runCatching
            }
            if (smallIds.isNotEmpty()) {
                render(
                    context,
                    manager,
                    smallIds,
                    R.layout.widget_small,
                    forceUpdate = false
                )
            }
            if (largeIds.isNotEmpty()) {
                render(
                    context,
                    manager,
                    largeIds,
                    R.layout.widget_large,
                    forceUpdate = false
                )
            }

            val activeKeys = buildSet {
                smallIds.forEach { add(WidgetKey(R.layout.widget_small, it)) }
                largeIds.forEach { add(WidgetKey(R.layout.widget_large, it)) }
            }
            synchronized(renderedSignatures) {
                renderedSignatures.keys.retainAll(activeKeys)
            }
        }.onFailure {
            AppDiagnostics.warn("widget_refresh_failed", error = it)
        }
    }

    fun forgetWidgets(appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val removedIds = appWidgetIds.toSet()
        synchronized(renderedSignatures) {
            renderedSignatures.keys.removeAll { it.appWidgetId in removedIds }
        }
    }

    private fun formatWidgetDistance(distanceMeters: Float): String {
        return if (distanceMeters >= 1000f) {
            val km = distanceMeters / 1000f
            if (km >= 100f) {
                String.format(Locale.getDefault(), "%.0f km", km)
            } else {
                String.format(Locale.getDefault(), "%.1f km", km)
            }
        } else {
            "${distanceMeters.toInt()} m"
        }
    }

    private fun normalizeTo360(value: Float): Float {
        if (!value.isFinite()) return 0f
        val mod = value % 360f
        return if (mod < 0f) mod + 360f else mod
    }

    private fun quantizeRotation(value: Float): Float {
        val normalized = normalizeTo360(value)
        val stepped = kotlin.math.round(normalized / WIDGET_ARROW_STEP_DEGREES) *
            WIDGET_ARROW_STEP_DEGREES
        return normalizeTo360(stepped)
    }

    private fun isValidDestination(destination: Destination): Boolean {
        if (!destination.lat.isFinite() || !destination.lng.isFinite()) return false
        if (destination.lat !in -90.0..90.0) return false
        if (destination.lng !in -180.0..180.0) return false
        return true
    }
}

