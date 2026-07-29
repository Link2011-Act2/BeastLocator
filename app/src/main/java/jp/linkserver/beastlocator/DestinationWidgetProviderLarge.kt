package jp.linkserver.beastlocator

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class DestinationWidgetProviderLarge : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        runCatching {
            WidgetRenderer.render(context, appWidgetManager, appWidgetIds, R.layout.widget_large)
        }.onFailure {
            AppDiagnostics.warn("large_widget_update_failed", error = it)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetRenderer.forgetWidgets(appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

