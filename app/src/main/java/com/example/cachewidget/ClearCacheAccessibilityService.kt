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
        if (attempt > 20) {
            searching = false
            showToast(diagnose())
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            retry(attempt)
            return
        }

        if (root.findAccessibilityNodeInfosByText("Кэш").isEmpty()) {
            retry(attempt)
            return
        }

        val node = root.findAccessibilityNodeInfosByText("Очистить кэш").firstOrNull()
            ?: root.findAccessibilityNodeInfosByText("Очистить").firstOrNull()

        val target = node?.let { if (it.isClickable) it else findClickableParent(it) }

        if (target != null) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            searching = false
            showToast("Нажал «Очистить кэш»")
        } else {
            retry(attempt)
        }
    }

    // Диагностика: какой пакет на экране и какие первые тексты видит сервис
    private fun diagnose(): String {
        val root = rootInActiveWindow
            ?: return "DIAG: экран не виден (root=null)"
        val sb = StringBuilder("DIAG pkg=").append(root.packageName).append("; тексты: ")
        val texts = mutableListOf<String>()
        collectTexts(root, texts, 0)
        sb.append(if (texts.isEmpty()) "пусто" else texts.joinToString(" | "))
        return sb.toString()
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || out.size >= 5 || depth > 6) return
        node.text?.let { if (it.isNotBlank()) out.add(it.toString()) }
        for (i in 0 until node.childCount) collectTexts(node.getChild(i), out, depth + 1)
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
