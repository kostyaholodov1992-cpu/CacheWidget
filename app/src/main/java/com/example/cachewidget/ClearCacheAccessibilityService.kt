package com.example.cachecleaner

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class CacheAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // реагируем только на системные настройки
        if (event.packageName != "com.android.settings") return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

      // ждём, пока панель с кнопками отрисуется (One UI — до 2 секунд)
Thread.sleep(2000)

// ищем кнопку «Очистить кэш» по тексту
val cleanCacheBtn = findAccessibilityNodeInfosByText("Очистить кэш")
    .firstOrNull { it.className?.toString()?.contains("Button", ignoreCase = true) == true }

if (cleanCacheBtn != null) {
    cleanCacheBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    Toast.makeText(this, "Нажал «Очистить кэш»", Toast.LENGTH_SHORT).show()
} else {
    // запасной вариант: ищем по части текста
    val fallback = findAccessibilityNodeInfosByText("Очистить")
        .firstOrNull { it.isClickable }
    if (fallback != null) {
        fallback.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } else {
        Toast.makeText(this, "Кнопка не найдена", Toast.LENGTH_SHORT).show()
    }
}
        }
    }

    override fun onInterrupt() {}
}

