package com.example.cachewidget

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ClearCacheAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var queue: List<String> = emptyList()
    private var index = 0
    private var scrolled = false
    private var running = false
    private var appStartTime = 0L
    private var stage = 0 // 0 — пробуем прямой заход, 1 — страница приложения, 2 — переход уже запланирован

    private val appTimeoutMs = 8000L
    private val directAttemptMs = 3000L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (running && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() != "com.android.settings"
        ) {
            handler.postDelayed({
                if (rootInActiveWindow?.packageName?.toString() != "com.android.settings") stopRun()
            }, 700)
            return
        }

        if (event.packageName?.toString() != "com.android.settings") return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        if (!running) {
            running = true
            queue = buildQueue()
            index = 0
            startCurrentApp()
        }
        handler.postDelayed({ tryStep() }, 400)
    }

    private fun buildQueue(): List<String> =
        packageManager.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { it.packageName }
            .sorted()

    private fun startCurrentApp() {
        stage = 0
        scrolled = false
        appStartTime = System.currentTimeMillis()
        openDirect(queue[index])
        handler.postDelayed({ tryStep() }, 1200)
    }

    // Пытаемся открыть экран «Хранилище» напрямую. Если прошивка не поддержит —
    // упадёт на страницу приложения, и сработает запасная схема.
    private fun openDirect(pkg: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra(
            ":settings:show_fragment",
            "com.android.settings.applications.appinfo.AppStorageSettings"
        )
        startActivity(intent)
    }

    private fun openAppPage(pkg: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun tryStep() {
        if (!running) return

        val root = rootInActiveWindow ?: return retry()

        // Общий тайм-бюджет: приложение не может занимать дольше 8 секунд
        if (System.currentTimeMillis() - appStartTime > appTimeoutMs) { nextApp(); return }

        // Если мы уже на экране «Хранилище» — кэш стирается сам, ждём и идём дальше
        if (stage != 2 && looksLikeStorageScreen(root)) {
            stage = 2
            handler.postDelayed({ nextApp() }, 1500)
            return
        }

        when (stage) {
            0 -> {
                // прямой заход не подтвердился за 3 секунды — переходим к запасной схеме
                if (System.currentTimeMillis() - appStartTime > directAttemptMs) {
                    stage = 1
                    openAppPage(queue[index])
                    handler.postDelayed({ tryStep() }, 1000)
                } else {
                    retry()
                }
            }
            1 -> {
                val node = root.findAccessibilityNodeInfosByText("Хранилище").firstOrNull()
                    ?: root.findAccessibilityNodeInfosByText("Память").firstOrNull()
                    ?: root.findAccessibilityNodeInfosByText("Storage").firstOrNull()
                val target = node?.let { if (it.isClickable) it else findClickableParent(it) }
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    stage = 2
                    handler.postDelayed({ nextApp() }, 1500)
                } else if (!scrolled && scrollDown(root)) {
                    scrolled = true
                    handler.postDelayed({ tryStep() }, 700)
                } else {
                    retry()
                }
            }
            // stage == 2 — переход запланирован, ничего не делаем
        }
    }

    // Экран «Хранилище» узнаём по надписям с размерами кэша/данных
    private fun looksLikeStorageScreen(root: AccessibilityNodeInfo): Boolean {
        return root.findAccessibilityNodeInfosByText("Кэш").isNotEmpty() ||
               root.findAccessibilityNodeInfosByText("Cache").isNotEmpty() ||
               root.findAccessibilityNodeInfosByText("Общий объём").isNotEmpty()
    }

    private fun scrollDown(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable &&
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        ) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scrollDown(child)) return true
        }
        return false
    }

    private fun retry() {
        handler.postDelayed({ tryStep() }, 500)
    }

    private fun nextApp() {
        if (!running) return
        index++
        if (index >= queue.size) {
            stopRun()
            return
        }
        startCurrentApp()
    }

    private fun stopRun() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    override fun onInterrupt() {
        stopRun()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
