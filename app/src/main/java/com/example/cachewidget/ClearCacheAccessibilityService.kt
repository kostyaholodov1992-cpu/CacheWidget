package com.example.cachewidget

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ClearCacheAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // Работаем только на экранах настроек (пакет com.android.settings)
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.android.settings") return

        rootInActiveWindow ?: return

        // Ищем кнопку «Очистить кэш» (может называться по-разному на разных прошивках)
        val texts = listOf("Очистить кэш", "Clear cache", "Очистити кеш")
        for (text in texts) {
            val node = findNodeByText(rootInActiveWindow, text)
            if (node != null && node.isEnabled) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return // нажали — выходим, чтобы не нажимать повторно
            }
        }
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.equals(text, ignoreCase = true) == true) return node
        for (i in 0 until node.childCount) {
            findNodeByText(node.getChild(i) ?: continue, text)?.let { return it }
        }
        return null
    }

    override fun onInterrupt() {
        // пусто — прерывание службы нам не критично
    }
}
