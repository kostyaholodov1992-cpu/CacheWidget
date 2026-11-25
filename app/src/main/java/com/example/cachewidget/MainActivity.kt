package com.example.cachewidget

import android.animation.ObjectAnimator
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
import android.view.LayoutInflater
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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        // Минимальный размер кэша, чтобы приложение попало в список (1 МБ)
        private const val MIN_CACHE_BYTES: Long = 1024L * 1024L

        // Пауза перед замером кэша: системе нужно время обновить статистику хранилища
        private const val MEASURE_DELAY_MS = 600L

        private const val PREFS_NAME = "settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_MANUAL = "manual_mode"
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

    // Суммарно реально освобождено за текущую очистку (по замерам до/после)
    private var freedTotal = 0L

    private var pendingClear = false

    private var overlayView: LinearLayout? = null
    private var overlayText: TextView? = null

    // Элементы анимированного оверлея
    private var overlayProgressBar: ProgressBar? = null
    private var overlayProgressText: TextView? = null
    private var overlayStopButton: Button? = null
    private var stopPulse: ObjectAnimator? = null

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

        // Восстанавливаем ручной режим из настроек
        ClearCacheAccessibilityService.manualMode = prefs.getBoolean(KEY_MANUAL, false)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        installCrashLogger()
        showLastErrorIfAny()
        supportActionBar?.hide()

        Logger.log(this, Logger.Level.INFO, "=== Запуск приложения ===")

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

        // Если запуск с плитки — сразу начинаем очистку
        handleAutoStart(intent)
    }

    // Приём команды, когда MainActivity уже открыта (работает благодаря singleTop)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoStart(intent)
    }

    private fun handleAutoStart(intent: Intent?) {
        if (intent?.getBooleanExtra("auto_start_clear", false) == true) {
            intent.removeExtra("auto_start_clear") // чтобы не зациклилось на каждом onResume
            startAutoClear()
        }
    }

    // Очистка всего найденного кэша без ручного выбора
    private fun startAutoClear() {
        // Не мешаем уже идущей очистке
        if (ClearCacheAccessibilityService.isRunning) return

        // Сначала сканируем, чтобы наполнить списки
        scanApps()

        // Берём всё найденное: и пользовательские, и системные
        val all = (userAppsCache + systemAppsCache).filter { it.cacheBytes > 0 }
        if (all.isEmpty()) {
            findViewById<TextView>(R.id.tv_status).text = "Кэша для очистки не найдено"
            Toast.makeText(this, "Кэша для очистки не найдено", Toast.LENGTH_SHORT).show()
            return
        }

        // Отмечаем всё в адаптере, чтобы UI выглядел согласованно
        if (!adapter.allSelected) {
            adapter.toggleAll()
            onSelectionChanged()
        }

        // Дальше — обычный путь очистки со всеми проверками разрешений
        startClearing()
    }

    // --- Меню и тема ---

    private fun showMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)

        // Список пунктов: (id, текст)
        val items = listOf(
            Pair(1, "Настройки доступности"),
            Pair(2, "Тема"),
            Pair(3, if (ClearCacheAccessibilityService.manualMode) "Ручная очистка: вкл ✓" else "Ручная очистка: выкл"),
            Pair(4, "Журнал работы")
        )

        // Сортировка по длине текста: от коротких пунктов к длинным
        val sortedItems = items.sortedWith(
            compareBy<Pair<Int, String>> { it.second.length }.thenBy { it.second }
        )

        // Добавляем пункты в меню уже отсортированными
        for ((id, text) in sortedItems) {
            popup.menu.add(Menu.NONE, id, Menu.NONE, text)
        }

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
                3 -> {
                    val newMode = !ClearCacheAccessibilityService.manualMode
                    if (newMode && !isAccessibilityEnabled()) {
                        Toast.makeText(this, "Сначала включите службу в настройках", Toast.LENGTH_LONG).show()
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        return@setOnMenuItemClickListener false
                    }
                    ClearCacheAccessibilityService.manualMode = newMode
                    prefs.edit().putBoolean(KEY_MANUAL, newMode).apply()
                    Logger.log(this, Logger.Level.INFO, "Ручной режим переключён: $newMode")
                    Toast.makeText(
                        this,
                        if (newMode) "Ручной режим включён: открой любое приложение в настройках — кэш очистится сам"
                        else "Ручной режим выключен",
                        Toast.LENGTH_LONG
                    ).show()
                    true
                }
                4 -> {
                    Logger.show(this)
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
        val current = currentThemeKey()

        // Разметка берётся из файла res/layout/dialog_theme.xml
        val container = LayoutInflater.from(this).inflate(R.layout.dialog_theme, null)

        val swDark = container.findViewById<MaterialSwitch>(R.id.sw_dark)
        val swLight = container.findViewById<MaterialSwitch>(R.id.sw_light)
        val swAuto = container.findViewById<MaterialSwitch>(R.id.sw_auto)

        // Начальные состояния ползунков
        swDark.isChecked = current == THEME_DARK
        swLight.isChecked = current == THEME_LIGHT
        swAuto.isChecked = current == THEME_AUTO

        // Пары «ползунок — ключ темы», чтобы логика не дублировалась
        val switches = listOf(
            swDark to THEME_DARK,
            swLight to THEME_LIGHT,
            swAuto to THEME_AUTO
        )

        for ((sw, key) in switches) {
            sw.setOnCheckedChangeListener { _, isChecked ->
                // Реагируем только на включение: программное выключение
                // соседних ползунков не должно зацикливать логику
                if (!isChecked) return@setOnCheckedChangeListener

                // Ползунки исключают друг друга
                for ((other, _) in switches) {
                    if (other != sw) other.isChecked = false
                }

                val mode = when (key) {
                    THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                prefs.edit().putString(KEY_THEME, key).apply()
                Logger.log(this@MainActivity, Logger.Level.INFO, "Тема изменена: $key")
                AppCompatDelegate.setDefaultNightMode(mode)

                // Диалог закроется — это нормальное поведение
                recreate()
            }
        }

        MaterialAlertDialogBuilder(this)
            .setView(container)
            .show()
    }

    override fun onResume() {
        super.onResume()
        Logger.log(
            this,
            Logger.Level.INFO,
            "onResume: usage=${hasUsageAccess()}, a11y=${isAccessibilityEnabled()}, overlay=${Settings.canDrawOverlays(this)}"
        )
        if (pendingClear && Settings.canDrawOverlays(this)) {
            pendingClear = false
            Toast.makeText(this, "Разрешение получено, начинаю очистку", Toast.LENGTH_SHORT).show()
            doStartClearing()
        }

        // Если активити была жива и getIntent ещё содержит команду — обрабатываем
        if (intent?.getBooleanExtra("auto_start_clear", false) == true) {
            intent.removeExtra("auto_start_clear")
            startAutoClear()
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

    // --- Реальный замер кэша ---

    /**
     * Текущий (на момент вызова) размер кэша приложения по его UID.
     * Возвращает 0, если замер не удался.
     */
    private fun getRealCacheSize(app: AppInfo): Long {
        return try {
            val ssm = getSystemService(StorageStatsManager::class.java)
            val stats = ssm.queryStatsForUid(app.storageUuid, app.uid)
            stats.cacheBytes
        } catch (e: Exception) {
            Logger.log(this, Logger.Level.WARN, "Замер не удался для ${app.name}: ${e.message}")
            0L
        }
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
            Logger.log(this, Logger.Level.WARN, "Скан отменён: нет доступа к данным об использовании")
            status.text = "Включите «Доступ к данным об использовании»"
            Toast.makeText(this, "Разрешите приложению доступ к данным об использовании", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }

        status.text = "Сканирую..."
        Logger.log(this, Logger.Level.INFO, "=== Скан ===")
        Logger.log(this, Logger.Level.INFO, "Скан начат")

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

            // Запоминаем uid и хранилище — по ним будем делать повторный замер после очистки
            val item = AppInfo(
                name, info.packageName, cache,
                icon = icon,
                uid = info.uid,
                storageUuid = info.storageUuid
            )

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
        val totalCacheBytes = clearList.sumOf { it.cacheBytes }
        status.text = "Приложений с кэшем от 1 МБ: $withCache (установленных ${userAppsCache.size}, системных ${systemAppsCache.size}). Мелких пропущено: $skippedSmall"
        Logger.log(
            this,
            Logger.Level.INFO,
            "Скан завершён: найдено $withCache, суммарный кэш ${formatGb(totalCacheBytes)}, пропущено мелких $skippedSmall"
        )
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
            Logger.log(this, Logger.Level.WARN, "Очистка отменена: нет службы доступности")
            status.text = "Включите службу специальных возможностей"
            Toast.makeText(this, "Сначала включите службу в настройках", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        // Чистим только то, что отмечено галочками
        val selected = adapter.getSelectedApps()
        if (selected.isEmpty()) {
            Logger.log(this, Logger.Level.WARN, "Очистка отменена: не выбрано ни одного приложения")
            status.text = "Выберите приложения галочками"
            Toast.makeText(this, "Отметьте хотя бы одно приложение", Toast.LENGTH_SHORT).show()
            return
        }

        // Из выбранного чистим только то, у чего кэш реально есть
        clearList = selected.filter { it.cacheBytes > 0 }
        if (clearList.isEmpty()) {
            Logger.log(this, Logger.Level.WARN, "Очистка отменена: у выбранных нет кэша")
            status.text = "У выбранных приложений нет кэша"
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Logger.log(this, Logger.Level.WARN, "Очистка отложена: нет разрешения поверх окон")
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
        // Автоматическая очистка не должна работать вместе с ручным режимом
        ClearCacheAccessibilityService.manualMode = false
        prefs.edit().putBoolean(KEY_MANUAL, false).apply()

        clearIndex = 0
        token = 0
        advancedToken = -1
        freedTotal = 0
        ClearCacheAccessibilityService.isRunning = true

        val totalBytes = clearList.sumOf { it.cacheBytes }
        Logger.log(this, Logger.Level.INFO, "=== Очистка ===")
        Logger.log(
            this,
            Logger.Level.INFO,
            "Очистка начата: ${clearList.size} приложений, примерно ${formatGb(totalBytes)} кэша"
        )

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
            Logger.log(this, Logger.Level.WARN, "Очистка остановлена пользователем")
            if (freedTotal > 0) {
                Logger.log(this, Logger.Level.INFO, "До остановки успели освободить: ${formatGb(freedTotal)}")
            }
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

        // Полоса прогресса
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(96, 0, 96, 0) }
            max = 100
            progress = 0
        }

        // Текст текущего приложения
        val progressText = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(0, 40, 0, 0)
        }

        // Счётчик «Готово: X из Y» + сколько уже освободили
        val progressInfo = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            alpha = 0.7f
            setPadding(0, 16, 0, 0)
        }

        val stopButton = Button(this).apply {
            text = "Стоп"
            setOnClickListener { stopClearing() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 64, 0, 0) }
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.overlay_bg))
            addView(progressBar)
            addView(progressText)
            addView(progressInfo)
            addView(stopButton)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Плавное появление всей панели
            alpha = 0f
            animate()
                .alpha(1f)
                .setDuration(350)
                .start()
        }
        overlayText = progressText
        overlayProgressBar = progressBar
        overlayProgressText = progressInfo
        overlayStopButton = stopButton

        // Пульсация кнопки «Стоп»: мягко дышит, чтобы её было заметно
        stopPulse = ObjectAnimator.ofFloat(stopButton, android.view.View.ALPHA, 1f, 0.45f).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }

        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            stopPulse?.cancel()
            stopPulse = null
            Logger.log(this, Logger.Level.ERROR, "Ошибка показа шторки: ${e.message}")
            overlayView = null
            overlayText = null
            overlayProgressBar = null
            overlayProgressText = null
            overlayStopButton = null
            Toast.makeText(this, "Не удалось показать шторку: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateOverlayText(message: String) {
        overlayText?.text = message

        // Мягкое «мигание» текста при смене приложения
        overlayText?.let { tv ->
            tv.alpha = 0.2f
            tv.animate().alpha(1f).setDuration(250).start()
        }

        // Обновляем полосу прогресса и счётчик с реально освобождённым объёмом
        if (clearList.isNotEmpty()) {
            val done = clearIndex.coerceAtLeast(0)
            val percent = (done * 100 / clearList.size)
            overlayProgressBar?.progress = percent
            overlayProgressText?.text = "Готово: $done из ${clearList.size} ($percent%) · Освобождено: ${formatGb(freedTotal)}"
        }
    }

    private fun hideOverlay() {
        stopPulse?.cancel()
        stopPulse = null

        val view = overlayView
        if (view != null) {
            // Плавное затухание перед удалением
            view.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    try { windowManager.removeView(view) } catch (_: Exception) {}
                }
                .start()
        }
        overlayView = null
        overlayText = null
        overlayProgressBar = null
        overlayProgressText = null
        overlayStopButton = null
    }

    private fun openNextApp() {
        token++
        val current = token
        val app = clearList[clearIndex]
        ClearCacheAccessibilityService.currentTask = "storage"
        Logger.log(this, Logger.Level.INFO, "Открываю ${app.name} (${app.packageName})")

        // Таймер-страховка: если сервис не сработал за 5 секунд — переходим дальше
        handler.postDelayed({
            if (current == token && current != advancedToken) advance()
        }, 5000)

        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${app.packageName}"))
        startActivity(intent)
    }

    /**
     * Сервис сообщил, что приложение обработано.
     * Делаем паузу MEASURE_DELAY_MS, чтобы система обновила статистику,
     * и только потом замеряем кэш и считаем разницу.
     */
    private fun advance() {
        if (!ClearCacheAccessibilityService.isRunning) return
        if (token == advancedToken) return
        advancedToken = token

        val current = token
        handler.postDelayed({ finishCurrentApp(current) }, MEASURE_DELAY_MS)
    }

    private fun finishCurrentApp(currentToken: Int) {
        if (currentToken != token) return
        if (!ClearCacheAccessibilityService.isRunning) return

        // Замер после очистки и подсчёт реальной разницы
        if (clearIndex < clearList.size) {
            val processed = clearList[clearIndex]
            val after = getRealCacheSize(processed)
            val freed = (processed.cacheBytes - after).coerceAtLeast(0)
            freedTotal += freed

            Logger.log(
                this,
                Logger.Level.INFO,
                "Обработано: ${processed.name} | Было: ${formatGb(processed.cacheBytes)} | Стало: ${formatGb(after)} | Освобождено: ${formatGb(freed)}"
            )
        }

        clearIndex++

        if (clearIndex >= clearList.size) {
            val cleaned = clearList.size
            stopClearing(silent = true)

            Logger.log(this, Logger.Level.INFO, "=== Очистка завершена ===")
            Logger.log(
                this,
                Logger.Level.INFO,
                "Сводка: обработано $cleaned приложений, реально освобождено ${formatGb(freedTotal)}"
            )

            findViewById<TextView>(R.id.tv_status).text =
                "Готово! Очищено приложений: $cleaned. Освобождено: ${formatGb(freedTotal)}"
            Toast.makeText(this, "Освобождено: ${formatGb(freedTotal)}", Toast.LENGTH_LONG).show()

            // Пересканируем, чтобы карточки и список показали актуальные цифры
            scanApps()
        } else {
            Logger.log(this, Logger.Level.INFO, "Переход к ${clearList[clearIndex].name} (${clearIndex + 1}/${clearList.size})")
            updateOverlayText("Чищу ${clearIndex + 1} из ${clearList.size}: ${clearList[clearIndex].name}")
            openNextApp()
        }
    }
}
