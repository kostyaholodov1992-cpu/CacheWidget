package com.example.cachewidget

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ClearCacheAccessibilityService : AccessibilityService() {

    private var stage = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        stage = 0
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() != "com.android.settings") return
        val root = rootInActiveWindow ?: return

        when (stage) {
            0 -> {
                // Шаг 1: на странице приложения нажимаем «Хранилище»
                for (label in listOf("Хранилище", "Storage", "Сховище", "Хранилище и кэш")) {
                    val node = root.findAccessibilityNodeInfosByText(label).firstOrNull()
                    if (node != null && node.isEnabled) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        stage = 1
                        return
                    }
                }
            }
            1 -> {
                // Шаг 2: на экране «Хранилище» нажимаем «Очистить кэш»
                for (label in listOf("Очистить кэш", "Clear cache", "Очистити кеш")) {
                    val node = root.findAccessibilityNodeInfosByText(label).firstOrNull()
                    if (node != null && node.isEnabled) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        stage = 2
                        // Возвращаемся назад и сбрасываем состояние
                        handler.postDelayed({
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            stage = 0
                        }, 1500)
                        return
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}
}
