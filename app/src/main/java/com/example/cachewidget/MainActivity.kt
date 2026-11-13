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

    private var clearIndex = 0
    private var token = 0
    private var advancedToken = -1
    private val handler = Handler(Looper.getMainLooper())

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

    override fun onDestroy() {
        try { unregisterReceiver(nextReceiver) } catch (_: Exception) {}
        hideOverlay()
        ClearCacheAccessibilityService.isRunning = false
        ClearCacheAccessibilityService.currentTask = null
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

        apps.clear()
        val pm = packageManager
        val installed = pm.getInstalledApplications(0)

        for (info in installed) {
            val name = try { pm.getApplicationLabel(info).toString() } catch (_: Exception) { info.packageName }
            val cache = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val ssm = getSystemService(StorageStatsManager::class.java)
                    val stats = ssm.queryStatsForUid(info.storageUuid, info.uid)
                    stats.cacheBytes
                } catch (_: Exception) { 0L }
            } else 0L

            if (cache >= 1L * 1024 * 1024) {
                apps.add(AppInfo(name, info.packageName, cache))
            }
        }

        apps.sortByDescending { it.cacheBytes }

        adapter.notifyDataSetChanged()
        status.text = "Приложений с кэшем: ${apps.size}"
    }

    private fun startClearing() {
        val status = findViewById<TextView>(R.id.tv_status)

        if (!isAccessibilityEnabled()) {
            status.text = "Включите службу специальных возможностей"
            Toast.makeText(this, "Сначала включите службу в настройках", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (apps.isEmpty()) {
            status.text = "Сначала нажмите «Сканировать»"
            return
        }

        // Шторка поверх экрана требует разрешения «Поверх других окон»
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Дайте разрешение «Поверх других окон»"
            Toast.makeText(this, "Это нужно, чтобы показывать ползунок во время чистки", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        clearIndex = 0
        token = 0
        advancedToken = -1
        ClearCacheAccessibilityService.isRunning = true
        showOverlay()
        updateOverlayText("Чищу 1 из ${apps.size}: ${apps[0].name}")
        openNextApp()
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
            // NOT_FOCUSABLE — не перехватываем кнопки, НЕ ставим NOT_TOUCHABLE:
            // шторка глотает случайные касания, а службе они не нужны
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        val text = TextView(this).apply {
            textSize = 16f
            setPadding(0, 48, 0, 0)
            gravity = Gravity.CENTER
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF2FFFFFF.toInt())
            addView(ProgressBar(this@MainActivity))
            addView(text)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        overlayText = text

        windowManager.addView(overlayView, params)
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
        val app = apps[clearIndex]
        ClearCacheAccessibilityService.currentTask = "storage"

        // Если за 8 секунд ничего не произошло — пропускаем приложение
        handler.postDelayed({
            if (current == token && current != advancedToken) advance()
        }, 8000)

        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${app.packageName}"))
        startActivity(intent)
    }

    private fun advance() {
        if (token == advancedToken) return
        advancedToken = token
        clearIndex++

        if (clearIndex >= apps.size) {
            ClearCacheAccessibilityService.isRunning = false
            hideOverlay()
            findViewById<TextView>(R.id.tv_status).text = "Готово! Очищено приложений: ${apps.size}"
            Toast.makeText(this, "Кэш очищен, обновляю список", Toast.LENGTH_LONG).show()
            scanApps()
        } else {
            updateOverlayText("Чищу ${clearIndex + 1} из ${apps.size}: ${apps[clearIndex].name}")
            openNextApp()
        }
    }
}
