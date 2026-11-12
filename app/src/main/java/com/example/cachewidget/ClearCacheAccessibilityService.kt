package com.example.cachewidget

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class ClearCacheAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var searching = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != "com.android.settings") return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        startSearch()
    }

    private fun startSearch() {
        if (searching) return
        searching = true
        tryClick(0)
    }

    private fun tryClick(attempt: Int) {
        // 20 попыток по 500 мс = до 10 секунд ожидания нужного экрана
        if (attempt > 20) {
            searching = false
            showToast("Кнопка не найдена")
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            retry(attempt)
            return
        }

        // проверяем, что мы именно на экране «Хранилище» (там есть строка «Кэш»)
        val onStorageScreen = root.findAccessibilityNodeInfosByText("Кэш").isNotEmpty()
        if (!onStorageScreen) {
            retry(attempt)
            return
        }

        val node = root.findAccessibilityNodeInfosByText("Очистить кэш").firstOrNull()
            ?: root.findAccessibilityNodeInfosByText("Очистить").firstOrNull()

        if (node == null) {
            retry(attempt)
            return
        }

        // текст может лежать в надписи внутри кликабельного контейнера —
        // кликаем по первому кликабельному родителю
        val target = if (node.isClickable) node else findClickableParent(node)

        if (target != null) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            searching = false
            showToast("Нажал «Очистить кэш»")
        } else {
            retry(attempt)
        }
    }

    private fun retry(attempt: Int) {
        handler.postDelayed({ tryClick(attempt + 1) }, 500)
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun showToast(text: String) {
        handler.post {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        searching = false
        super.onDestroy()
    }
}
