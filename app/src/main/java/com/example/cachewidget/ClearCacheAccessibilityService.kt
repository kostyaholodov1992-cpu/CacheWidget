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
    private val queue = ArrayList<String>()
    private var index = 0
    private var attempts = 0
    private var running = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // работаем по опросу экрана, события не обрабатываем
    }

    override fun onInterrupt() {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY

        queue.clear()
        queue.addAll(loadUserApps())
        if (queue.isEmpty()) return START_NOT_STICKY

        running = true
        index = 0
        openNext()
        return START_NOT_STICKY
    }

    private fun loadUserApps(): List<String> {
        val result = ArrayList<String>()
        for (app in packageManager.getInstalledApplications(0)) {
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!isSystem && app.packageName != packageName) result.add(app.packageName)
        }
        return result
    }

    private fun openNext() {
        if (index >= queue.size) {
            running = false
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        val pkg = queue[index]
        attempts = 0
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        handler.postDelayed({ waitStoragePage(pkg) }, 1500)
    }

    private fun waitStoragePage(pkg: String) {
        val node = findClickable("Хранилище", "Storage")
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            handler.postDelayed({ tapClearCache(pkg) }, 1200)
        } else if (++attempts < 20) {
            handler.postDelayed({ waitStoragePage(pkg) }, 600)
        } else {
            index++
            openNext()
        }
    }

    private fun tapClearCache(pkg: String) {
        val node = findClickable("Очистить кэш", "Clear cache")
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            handler.postDelayed({ tapConfirm(pkg) }, 800)
        } else if (++attempts < 20) {
            handler.postDelayed({ tapClearCache(pkg) }, 600)
        } else {
            index++
            openNext()
        }
    }

    private fun tapConfirm(pkg: String) {
        // на части прошивок кэш чистится без диалога подтверждения,
        // поэтому если кнопки нет — просто идём к следующему приложению
        findClickable("ОК", "OK")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        index++
        handler.postDelayed({ openNext() }, 500)
    }

    private fun findClickable(vararg texts: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        for (t in texts) {
            for (n in root.findAccessibilityNodeInfosByText(t)) {
                if (n.isClickable) return n
                val p = n.parent
                if (p != null && p.isClickable) return p
            }
        }
        return null
    }
}
