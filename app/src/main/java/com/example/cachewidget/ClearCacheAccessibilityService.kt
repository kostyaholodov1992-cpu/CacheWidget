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
        var isRunning = false          // авто-очистка по списку
        var currentTask: String? = null
        var manualMode = false         // ручной режим: чистим то приложение, которое открыл пользователь
    }

    private val handler = Handler(Looper.getMainLooper())
    private var manualStage = 0        // 0 — ищем «Хранилище и кэш», 1 — ищем «Очистить кэш»
    private var lastClearAt = 0L       // антицикл: не жать повторно сразу после очистки

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Ручной режим: работает всегда, когда включён, независимо от isRunning
        if (manualMode) {
            handleManual()
            return
        }

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

    /**
     * Ручной режим: пользователь сам открыл страницу приложения в настройках —
     * сервис нажимает «Хранилище и кэш», затем «Очистить кэш» и подтверждает «ОК».
     */
    private fun handleManual() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName ?: return
        if (!pkg.contains("settings")) return

        // Не жмём повторно в течение 4 секунд после только что сделанной очистки
        if (System.currentTimeMillis() - lastClearAt < 4000) return

        when (manualStage) {
            0 -> {
                if (clickAny(root, listOf("Хранилище и кэш", "Хранилище", "Storage & cache", "Storage"))) {
                    manualStage = 1
                }
            }
            1 -> {
                if (clickAny(root, listOf("Очистить кэш", "Clear cache"))) {
                    // Кэш очищен. Дожидаемся диалога (если появится), жмём «ОК».
                    handler.postDelayed({
                        try {
                            rootInActiveWindow?.let { r ->
                                clickAny(r, listOf("ОК", "OK"))
                            }
                        } catch (_: Exception) {}
                        lastClearAt = System.currentTimeMillis()
                        manualStage = 0
                        // Возвращаемся на страницу приложения, чтобы пользователь
                        // мог сразу нажать «Назад» и открыть следующее
                        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 400)
                    }, 1200)
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
