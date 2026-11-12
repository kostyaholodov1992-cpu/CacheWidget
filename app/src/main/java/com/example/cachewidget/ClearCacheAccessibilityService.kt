package com.example.cachewidget

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class ClearCacheAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != "com.android.settings") return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // ждём, пока панель с кнопками отрисуется (One UI — до 2 секунд)
        handler.postDelayed({ clickClearCache() }, 2000)
    }

    private fun clickClearCache() {
        val root = rootInActiveWindow
        if (root == null) {
            showToast("Экран не найден")
            return
        }

        val button = root.findAccessibilityNodeInfosByText("Очистить кэш")
            .firstOrNull {
                it.className != null &&
                    it.className.toString().contains("Button", ignoreCase = true)
            }

        if (button != null) {
            button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            showToast("Нажал «Очистить кэш»")
        } else {
            val fallback = root.findAccessibilityNodeInfosByText("Очистить")
                .firstOrNull { it.isClickable }
            if (fallback != null) {
                fallback.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                showToast("Нажал «Очистить»")
            } else {
                showToast("Кнопка не найдена")
            }
        }
    }

    private fun showToast(text: String) {
        handler.post {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {}
}
