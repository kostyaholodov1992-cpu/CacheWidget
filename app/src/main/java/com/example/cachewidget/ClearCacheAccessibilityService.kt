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
import android.widget.Toast

class ClearCacheAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var queue: List<String> = emptyList()
    private var index = 0
    private var attempts = 0
    private var scrolled = false
    private var running = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != "com.android.settings") return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        if (!running) {
            running = true
            queue = buildQueue()
            index = 0
            attempts = 0
            scrolled = false
            Toast.makeText(this, "Старт: ${queue.size} приложений", Toast.LENGTH_LONG).show()
        }
        attempts = 0
        handler.postDelayed({ tryStep() }, 600)
    }

    private fun buildQueue(): List<String> =
        packageManager.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { it.packageName }
            .sorted()

    private fun tryStep() {
        val root = rootInActiveWindow
        if (root == null) { retry(); return }

        // ищем строку разными названиями: «Хранилище», «Хранилище и кэш», «Память»
        val node = root.findAccessibilityNodeInfosByText("Хранилище").firstOrNull()
            ?: root.findAccessibilityNodeInfosByText("Память").firstOrNull()
            ?: root.findAccessibilityNodeInfosByText("Storage").firstOrNull()

        val target = node?.let { if (it.isClickable) it else findClickableParent(it) }

        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            attempts = 0
            // кэш стирается при открытии экрана — даём ему время прогрузиться
            handler.postDelayed({ nextApp() }, 2500)
        } else {
            // строки на экране нет — пробуем проскроллить вниз (один раз за приложение)
            if (!scrolled && scrollDown(root)) {
                scrolled = true
                handler.postDelayed({ tryStep() }, 800)
            } else {
                retry()
            }
        }
    }

    private fun scrollDown(root: AccessibilityNodeInfo): Boolean {
        if (root.actionFlags and AccessibilityNodeInfo.ACTION_SCROLL_FORWARD != 0) {
            return root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            if (child.isScrollable && child.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                return true
            }
            if (scrollDown(child)) return true
        }
        return false
    }

    private fun retry() {
        attempts++
        if (attempts > 20) {
            val pkg = queue.getOrNull(index) ?: "?"
            Toast.makeText(this, "Пропустил: $pkg", Toast.LENGTH_SHORT).show()
            nextApp()
            return
        }
        handler.postDelayed({ tryStep() }, 500)
    }

    private fun nextApp() {
        index++
        if (index >= queue.size) {
            running = false
            Toast.makeText(this, "Готово: пройдено $index приложений", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${queue[index]}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        attempts = 0
        scrolled = false
        handler.postDelayed({ tryStep() }, 1500)
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
        running = false
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        running = false
        super.onDestroy()
    }
}
