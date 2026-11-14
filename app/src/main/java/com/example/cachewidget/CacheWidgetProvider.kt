package com.example.cachewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews

class CacheWidgetProvider : AppWidgetProvider() {

    companion object {
        // Уникальное действие: по нему служба поймёт, что команда пришла из виджета
        const val ACTION_CLEAR_CACHE = "com.example.cachewidget.ACTION_CLEAR_CACHE"

        // Статический метод для обновления всех виджетов (вызовем из службы после очистки)
        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, CacheWidgetProvider::class.java)
            )
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val options: Bundle = manager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)

            // Выбор разметки в зависимости от размера виджета на столе
            val layoutRes = if (minWidth >= 180 && minHeight >= 180) {
                R.layout.widget_3x3
            } else if (minWidth >= 180) {
                R.layout.widget_3x1
            } else {
                R.layout.widget_2x2
            }

            val views = RemoteViews(context.packageName, layoutRes)
            views.setOnClickPendingIntent(R.id.widget_action, buildClearIntent(context))
            manager.updateAppWidget(appWidgetId, views)
        }

        // Шаг 1 + Шаг 2: собираем Intent и оборачиваем в PendingIntent
        private fun buildClearIntent(context: Context): PendingIntent {
            val intent = Intent(ACTION_CLEAR_CACHE).apply {
                setPackage(context.packageName)          // адресат — только наше приложение
                putExtra("source", "widget")             // маркер, что вызов из виджета
            }

            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, manager, appWidgetId)
        }
    }
}
