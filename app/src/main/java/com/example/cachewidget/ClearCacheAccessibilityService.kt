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

        val node = root.findAccessibilityNodeInfosByText("Хранилище").firstOrNull()
