package com.example.cachewidget

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ClearCacheAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_CLEAR_NEXT = "com.example.cachewidget.CLEAR_NEXT"
        var isRunning = false
        var currentTask: String? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName ?: return
        if (!pkg.contains("settings")) return

        when (currentTask) {
            "storage" -> {
                // Сначала пробуем «Очистить кэш» — на некоторых телефонах он есть прямо на первой странице
                if (clickAny(root, listOf("Очистить кэш", "Clear cache"))) {
                    finishApp()
                } else if (clickAny(root, listOf("Хранилище и кэш", "Хранилище", "Storage & cache", "Storage"))) {
                    currentTask = "clear"
                }
            }
            "clear" -> {
                if (clickAny(root, listOf("Очистить кэш", "Clear cache"))) {
                    finishApp()
                }
            }
        }
    }

    private fun finishApp() {
        currentTask = null
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 500)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 1100)
        handler.postDelayed({
            sendBroadcast(Intent(ACTION_CLEAR_NEXT).setPackage(packageName))
        }, 1600)
    }

    private fun clickAny(root: AccessibilityNodeInfo, texts: List<String>): Boolean {
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                var c: AccessibilityNodeInfo? = node
                while (c != null && !c.isClickable) c = c.parent
                if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        return false
    }

    override fun onInterrupt() {}
}
