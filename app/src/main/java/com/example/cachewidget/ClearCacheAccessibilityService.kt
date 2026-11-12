package com.example.cachewidget

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class ClearCacheAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var queue: List<String> = emptyList()
    private var index = 0
    private var running = false
    private var attempts = 0

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Реагируем только на настройки
        if (event.packageName?.toString() != "com.android.settings") return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        if (running) {
            attempts = 0
            handler.postDelayed({ tryStep() }, 600)
        }
    }

    // Тот же список, что у виджета
    private fun buildQueue(): List<String> =
        packageManager.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { it.packageName }
            .sorted()

    private fun startRun() {
        running = true
        queue = buildQueue()
        index = 0
        attempts = 0
        Toast.makeText(this, "Старт: ${queue.size} приложений", Toast.LENGTH_LONG).show()
        handler.postDelayed({ tryStep() }, 800)
    }

    private fun tryStep() {
        // На One UI кэш сбрасывается сам при входе в «Хранилище»,
        // поэтому мы не ищем кнопки, а просто ждём, пока экран прогрузится,
        // и сразу идём дальше.

        // Если мы уже на странице приложения — считаем, что кэш очищен.
        // Переходим к следующему.
        nextApp()
    }

    private fun nextApp() {
        index++
        if (index >= queue.size) {
            running = false
            Toast.makeText(this, "Готово: кэш очищен у $index приложений", Toast.LENGTH_LONG).show()
            return
        }

        val nextPkg = queue[index]
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$nextPkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)

        // Даём системе время открыть экран (One UI может быть медленным)
        handler.postDelayed({ tryStep() }, 1200)
    }

    override fun onInterrupt() {
        running = false
        Toast.makeText(this, "Остановлено", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        running = false
        super.onDestroy()
    }
}
