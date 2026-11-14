package com.example.cachewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

open class CacheWidgetProvider : AppWidgetProvider() {

    // Каждая плитка подставляет свой макет — переопределяется в наследниках
    open val layoutRes: Int = R.layout.widget_2x2

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, layoutRes)
            views.setOnClickPendingIntent(R.id.widget_action, buildClearIntent(context))
            manager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun buildClearIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_CLEAR_CACHE).apply {
            setPackage(context.packageName)
            putExtra("source", "widget")
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_CLEAR_CACHE = "com.example.cachewidget.ACTION_CLEAR_CACHE"

        // Обновление всех плиток всех размеров (вызывается из службы после очистки)
        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val map = mapOf(
                CacheWidgetProvider1x1::class.java to R.layout.widget_1x1,
                CacheWidgetProvider2x2::class.java to R.layout.widget_2x2,
                CacheWidgetProvider3x3::class.java to R.layout.widget_3x3
            )
            for ((providerClass, layout) in map) {
                val ids = manager.getAppWidgetIds(ComponentName(context, providerClass))
                for (id in ids) {
                    val views = RemoteViews(context.packageName, layout)
                    views.setOnClickPendingIntent(R.id.widget_action, buildClearIntent(context))
                    manager.updateAppWidget(id, views)
                }
            }
        }

        private fun buildClearIntent(context: Context): PendingIntent {
            val intent = Intent(ACTION_CLEAR_CACHE).apply {
                setPackage(context.packageName)
                putExtra("source", "widget")
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
