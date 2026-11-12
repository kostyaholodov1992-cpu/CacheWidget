package com.example.cachewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.widget.RemoteViews

class CacheWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val first = context.packageManager.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { it.packageName }
            .sorted()
            .firstOrNull() ?: return

        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$first"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_cache)
            views.setOnClickPendingIntent(R.id.widget_button, pi)
            manager.updateAppWidget(id, views)
        }
    }
}
