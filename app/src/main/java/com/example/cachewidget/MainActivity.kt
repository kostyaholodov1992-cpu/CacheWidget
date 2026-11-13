package com.example.cachewidget

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private val apps = mutableListOf<AppInfo>()
    private var clearList = listOf<AppInfo>()

    private var clearIndex = 0
    private var token = 0
    private var advancedToken = -1
    private val handler = Handler(Looper.getMainLooper())

    private var pendingClear = false

    private var overlayView: LinearLayout? = null
    private var overlayText: TextView? = null

    private val nextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ClearCacheAccessibilityService.ACTION_CLEAR_NEXT) advance()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = AppAdapter(apps) { app ->
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${app.packageName}"))
            startActivity(intent)
        }

        val recycler = findViewById<RecyclerView>(R.id.recycler_apps)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<Button>(R.id.btn_scan).setOnClickListener { scanApps() }

        findViewById<Button>(R.id.btn_permission).setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                putExtra(
                    Intent.EXTRA_COMPONENT_NAME,
                    ComponentName(packageName, ClearCacheAccessibilityService::class.java.name)
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        findViewById<Button>(R.id.btn_clear).setOnClickListener { startClearing() }

        ContextCompat.registerReceiver(
            this, nextReceiver,
            IntentFilter(ClearCacheAccessibilityService.ACTION_CLEAR_NEXT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        if (pendingClear && Settings.canDrawOverlays(this)) {
            pendingClear = false
            Toast.makeText(this, "Разрешение получено, начинаю очистку", Toast.LENGTH_SHORT).show()
            doStartClearing()
        }
    }

    override fun onDestroy() {
        stopClearing(silent = true)
        try { unregisterReceiver(nextReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityEnabled(): Boolean {
        val setting = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return setting.contains("$packageName/${ClearCacheAccessibilityService::class.java.name}")
    }

    private fun scanApps() {
        val status = findViewById<TextView>(R.id.tv_status)

        if (!hasUsageAccess()) {
            status.text = "Включите «Доступ к данным об использовании»"
            Toast.makeText(this, "Разрешите приложению доступ к данным об использовании", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }

        status.text = "Сканирую..."

        val userApps = mutableListOf<AppInfo>()
        val systemApps = mutableListOf<AppInfo>()

        val pm = packageManager
        val installed = pm.getInstalledApplications(0)

        for (info in installed) {
            val name = try { pm.getApplicationLabel(info).toString() } catch (_: Exception) { info.packageName }
            val icon = try { pm.getApplicationIcon(info) } catch (_: Exception) { null }
            val cache = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val ssm = getSystemService(StorageStatsManager::class.java)
                    val stats = ssm.queryStatsForUid(info.storageUuid, info.uid)
                    stats.cacheBytes
                } catch (_: Exception) { 0L }
            } else 0L

            val item = AppInfo(name, info.packageName, cache, icon = icon)
            if (info.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
                userApps.add(item)
            } else {
                systemApps.add(item)
            }
        }

        userApps.sortByDescending { it.cacheBytes }
        systemApps.sortByDescending { it.cacheBytes }

        apps.clear()
        apps.add(AppInfo("Установленные", "", 0, isHeader = true))
        apps.addAll(userApps)
        apps.add(AppInfo("Системные", "", 0, isHeader = true))
        apps.addAll(systemApps)

        clearList = userApps + systemApps

        adapter.notifyDataSetChanged()
        val withCache = clearList.count { it.cacheBytes > 0 }
        status.text = "Найдено приложений: ${clearList.size} (установленных ${userApps.size}, системных ${systemApps.size}). С кэшем: $withCache"
    }

    private fun startClearing() {
        val status = findViewById<TextView>(R.id.tv_status)

        if (!isAccessibilityEnabled()) {
            status.text = "Включите службу специальных возможностей"
            Toast.makeText(this, "Сначала включите службу в настройках", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (clearList.isEmpty()) {
            status.text = "Сначала нажмите «Сканировать»"
            return
        }

        // Чистим только то, у чего кэш реально есть
        clearList = clearList.filter { it.cacheBytes > 0 }
        if (clearList.isEmpty()) {
            status.text = "Кэш не найден ни в одном приложении"
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            pendingClear = true
            status.text = "Дайте разрешение «Поверх других окон» и вернитесь"
            Toast.makeText(this, "Включите тумблер и нажмите «Назад» — очистка начнётся сама", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        doStartClearing()
    }

    private fun doStartClearing() {
        clearIndex = 0
        token = 0
        advancedToken = -1
        ClearCacheAccessibilityService.isRunning = true
        showOverlay()
        updateOverlayText("Чищу 1 из ${clearList.size}: ${clearList[0].name}")
        openNextApp()
    }

    private fun stopClearing(silent: Boolean = false) {
        handler.removeCallbacksAndMessages(null)
        token++
        ClearCacheAccessibilityService.isRunning = false
        ClearCacheAccessibilityService.currentTask = null
        pendingClear = false
        hideOverlay()
        if (!silent) {
            findViewById<TextView>(R.id.tv_status).text = "Очистка остановлена"
            Toast.makeText(this, "Очистка остановлена", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        val progressText = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }

        val stopButton = Button(this).apply {
            text = "Стоп"
            setOnClickListener { stopClearing() }
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF2FFFFFF.toInt())
            addView(ProgressBar(this@MainActivity))
            addView(progressText)
            addView(stopButton)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        overlayText = progressText

        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            overlayView = null
            overlayText = null
            Toast.makeText(this, "Не удалось показать шторку: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateOverlayText(message: String) {
        overlayText?.text = message
    }

    private fun hideOverlay() {
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
        overlayView = null
        overlayText = null
    }

    private fun openNextApp() {
        token++
        val current = token
        val app = clearList[clearIndex]
        ClearCacheAccessibilityService.currentTask = "storage"

        handler.postDelayed({
            if (current == token && current != advancedToken) advance()
        }, 8000)

        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${app.packageName}"))
        startActivity(intent)
    }

    private fun advance() {
        if (!ClearCacheAccessibilityService.isRunning) return
        if (token == advancedToken) return
        advancedToken = token
        clearIndex++

        if (clearIndex >= clearList.size) {
            val cleaned = clearList.size
            stopClearing(silent = true)
            findViewById<TextView>(R.id.tv_status).text = "Готово! Очищено приложений: $cleaned"
            Toast.makeText(this, "Кэш очищен, обновляю список", Toast.LENGTH_LONG).show()
            scanApps()
        } else {
            updateOverlayText("Чищу ${clearIndex + 1} из ${clearList.size}: ${clearList[clearIndex].name}")
            openNextApp()
        }
    }
}
