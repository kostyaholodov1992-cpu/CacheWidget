package com.example.cachewidget

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        // Минимальный размер кэша, чтобы приложение попало в список (1 МБ)
        private const val MIN_CACHE_BYTES: Long = 1024L * 1024L

        private const val PREFS_NAME = "settings"
        private const val KEY_THEME = "theme_mode"
        private const val THEME_DARK = "dark"
        private const val THEME_LIGHT = "light"
        private const val THEME_AUTO = "auto"
    }

    private lateinit var adapter: AppAdapter
    private val apps = mutableListOf<AppInfo>()

    private val userAppsCache = mutableListOf<AppInfo>()
    private val systemAppsCache = mutableListOf<AppInfo>()

    private var showUser = true
    private var showSystem = true

    private var clearList = listOf<AppInfo>()

    private var clearIndex = 0
    private var token = 0
    private var advancedToken = -1
    private val handler = Handler(Looper.getMainLooper())

    private var pendingClear = false

    private var overlayView: LinearLayout? = null
    private var overlayText: TextView? = null

    private lateinit var prefs: SharedPreferences

    private lateinit var checkBoxSelectAll: CheckBox
    private lateinit var selectedCount: TextView

    // Карточки статистики
    private lateinit var pbCache: ProgressBar
    private lateinit var tvCachePercent: TextView
    private lateinit var tvCacheUsed: TextView
    private lateinit var tvCacheTotal: TextView

    private lateinit var pbStorage: ProgressBar
    private lateinit var tvStoragePercent: TextView
    private lateinit var tvStorageUsed: TextView
    private lateinit var tvStorageTotal: TextView

    private val nextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ClearCacheAccessibilityService.ACTION_CLEAR_NEXT) advance()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        applySavedTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        installCrashLogger()
        showLastErrorIfAny()
        supportActionBar?.hide()

        checkBoxSelectAll = findViewById(R.id.checkBoxSelectAll)
        selectedCount = findViewById(R.id.selectedCount)

        pbCache = findViewById(R.id.pb_cache)
        tvCachePercent = findViewById(R.id.tv_cache_percent)
        tvCacheUsed = findViewById(R.id.tv_cache_used)
        tvCacheTotal = findViewById(R.id.tv_cache_total)

        pbStorage = findViewById(R.id.pb_storage)
        tvStoragePercent = findViewById(R.id.tv_storage_percent)
        tvStorageUsed = findViewById(R.id.tv_storage_used)
        tvStorageTotal = findViewById(R.id.tv_storage_total)

        adapter = AppAdapter(
            apps,
            onAppClick = { app ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${app.packageName}"))
                startActivity(intent)
            },
            onHeaderClick = { header ->
                if (header.name.startsWith("Установленные")) {
                    showUser = !showUser
                } else if (header.name.startsWith("Системные")) {
                    showSystem = !showSystem
                }
                refreshDisplay()
            },
            onSelectionChanged = ::onSelectionChanged
        )

        val recycler = findViewById<RecyclerView>(R.id.recycler_apps)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        checkBoxSelectAll.setOnCheckedChangeListener { _, _ ->
            adapter.toggleAll()
        }

        findViewById<Button>(R.id.btn_scan).setOnClickListener { scanApps() }

        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { view ->
            showMenu(view)
        }

        findViewById<Button>(R.id.btn_clear).setOnClickListener { startClearing() }

        ContextCompat.registerReceiver(
            this, nextReceiver,
            IntentFilter(ClearCacheAccessibilityService.ACTION_CLEAR_NEXT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        updateStorageStats()
        updateCacheStats()
    }

    // --- Меню и тема ---

    private fun showMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(Menu.NONE, 1, Menu.NONE, "Настройки доступности")
        popup.menu.add(Menu.NONE, 2, Menu.NONE, "Тема")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        putExtra(
                            Intent.EXTRA_COMPONENT_NAME,
                            ComponentName(packageName, ClearCacheAccessibilityService::class.java.name)
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                }
                2 -> {
                    showThemePanel()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun applySavedTheme() {
        val mode = when (prefs.getString(KEY_THEME, THEME_AUTO)) {
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun currentThemeKey(): String = when (AppCompatDelegate.getDefaultNightMode()) {
        AppCompatDelegate.MODE_NIGHT_YES -> THEME_DARK
        AppCompatDelegate.MODE_NIGHT_NO -> THEME_LIGHT
        else -> THEME_AUTO
    }

    private fun showThemePanel() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val switches = mutableMapOf<String, SwitchCompat>()

        fun addSwitch(key: String, label: String) {
            val sw = SwitchCompat(this).apply {
                text = label
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                isChecked = currentThemeKey() == key
            }
            switches[key] = sw
            container.addView(sw)
        }

        addSwitch(THEME_DARK, "Тёмная")
        addSwitch(THEME_LIGHT, "Светлая")
        addSwitch(THEME_AUTO, "Автоматическая")

        val dialog = AlertDialog.Builder(this)
            .setTitle("Тема")
            .setView(container)
            .setNegativeButton("Готово", null)
            .create()

        fun selectTheme(key: String) {
            // Ползунки исключают друг друга: включён только выбранный
            for ((k, sw) in switches) {
                sw.setOnCheckedChangeListener(null)
                sw.isChecked = (k == key)
            }
            val mode = when (key) {
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            prefs.edit().putString(KEY_THEME, key).apply()
            AppCompatDelegate.setDefaultNightMode(mode)
            // Повесим слушателей обратно после программной смены состояний
            for ((k, sw) in switches) {
                sw.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectTheme(k)
                }
            }
        }

        for ((k, sw) in switches) {
            sw.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectTheme(k)
            }
        }

        dialog.show()
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

    // --- Ловушка крашей ---

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val stack = e.stackTrace.take(8).joinToString("\n") { it.toString() }
                File(filesDir, "error_log.txt")
                    .writeText("${e.javaClass.simpleName}: ${e.message}\n$stack")
            } catch (_: Exception) {}
            previous?.uncaughtException(t, e)
        }
    }

    private fun showLastErrorIfAny() {
        try {
            val f = File(filesDir, "error_log.txt")
            if (f.exists()) {
                findViewById<TextView>(R.id.tv_status).text = f.readText().take(400)
                f.delete()
            }
        } catch (_: Exception) {}
    }

    // --- Карточки статистики ---

    private fun updateStorageStats() {
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val available = stat.availableBlocksLong * stat.blockSizeLong
            val used = total - available

            val percent = if (total > 0) (used * 100 / total).toInt() else 0
            tvStoragePercent.text = "$percent%"
            tvStorageUsed.text = formatGb(used)
            tvStorageTotal.text = formatGb(total)
            pbStorage.progress = percent
        } catch (_: Exception) {
            // Если диск не читается — просто оставляем нули
        }
    }

    private fun updateCacheStats() {
        try {
            var cacheBytes = 0L
            for (app in userAppsCache) cacheBytes += app.cacheBytes
            for (app in systemAppsCache) cacheBytes += app.cacheBytes

            val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong

            val percent = if (total > 0) (cacheBytes * 100 / total).toInt() else 0
            tvCachePercent.text = "$percent%"
            tvCacheUsed.text = formatGb(cacheBytes)
            tvCacheTotal.text = formatGb(total)
            pbCache.progress = percent
        } catch (_: Exception) {
            // Не удалось прочитать диск — оставляем нули
        }
    }

    private fun formatGb(bytes: Long): String {
        val gb = bytes / 1073741824.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    // --- Проверки разрешений ---

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

    // --- Скан ---

    private fun scanApps() {
        val status = findViewById<TextView>(R.id.tv_status)

        if (!hasUsageAccess()) {
            status.text = "Включите «Доступ к данным об использовании»"
            Toast.makeText(this, "Разрешите приложению доступ к данным об использовании", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }

        status.text = "Сканирую..."

        val forceUserPackages = setOf(
            "com.android.chrome",              // Chrome
            "com.google.android.apps.photos",  // Google Фото
            "com.google.android.youtube"       // YouTube
        )

        userAppsCache.clear()
        systemAppsCache.clear()

        val pm = packageManager
        val installed = pm.getInstalledApplications(0)

        var skippedSmall = 0

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

            // Фильтр: пропускаем приложения, у которых кэш меньше 1 МБ
            if (cache < MIN_CACHE_BYTES) {
                skippedSmall++
                continue
            }

            val item = AppInfo(name, info.packageName, cache, icon = icon)

            if (forceUserPackages.contains(info.packageName)) {
                userAppsCache.add(item)
                continue
            }

            if (info.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
                userAppsCache.add(item)
            } else {
                systemAppsCache.add(item)
            }
        }

        userAppsCache.sortByDescending { it.cacheBytes }
        systemAppsCache.sortByDescending { it.cacheBytes }

        clearList = userAppsCache + systemAppsCache

        refreshDisplay()
        updateStorageStats()
        updateCacheStats()

        val withCache = clearList.size
        status.text = "Приложений с кэшем от 1 МБ: $withCache (установленных ${userAppsCache.size}, системных ${systemAppsCache.size}). Мелких пропущено: $skippedSmall"
    }

    private fun refreshDisplay() {
        apps.clear()

        apps.add(
            AppInfo(
                if (showUser) "Установленные (${userAppsCache.size})  ▾" else "Установленные (${userAppsCache.size})  ▸",
                "", 0, isHeader = true
            )
        )
        if (showUser) apps.addAll(userAppsCache)

        apps.add(
            AppInfo(
                if (showSystem) "Системные (${systemAppsCache.size})  ▾" else "Системные (${systemAppsCache.size})  ▸",
                "", 0, isHeader = true
            )
        )
        if (showSystem) apps.addAll(systemAppsCache)

        adapter.notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun onSelectionChanged() {
        // Снимаем слушателя, чтобы программная смена состояния не вызвала toggleAll по кругу
        checkBoxSelectAll.setOnCheckedChangeListener(null)
        checkBoxSelectAll.isChecked = adapter.allSelected
        selectedCount.text = "${adapter.selectedCount} из ${apps.count { !it.isHeader }}"
        checkBoxSelectAll.setOnCheckedChangeListener { _, _ ->
            adapter.toggleAll()
        }
    }

    // --- Очистка ---

    private fun startClearing() {
        val status = findViewById<TextView>(R.id.tv_status)

        if (!isAccessibilityEnabled()) {
            status.text = "Включите службу специальных возможностей"
            Toast.makeText(this, "Сначала включите службу в настройках", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        // Чистим только то, что отмечено галочками
        val selected = adapter.getSelectedApps()
        if (selected.isEmpty()) {
            status.text = "Выберите приложения галочками"
            Toast.makeText(this, "Отметьте хотя бы одно приложение", Toast.LENGTH_SHORT).show()
            return
        }

        // Из выбранного чистим только то, у чего кэш реально есть
        clearList = selected.filter { it.cacheBytes > 0 }
        if (clearList.isEmpty()) {
            status.text = "У выбранных приложений нет кэша"
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
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(0, 32, 0, 0)
        }

        val stopButton = Button(this).apply {
            text = "Стоп"
            setOnClickListener { stopClearing() }
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.overlay_bg))
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
