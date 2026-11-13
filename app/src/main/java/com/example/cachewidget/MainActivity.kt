package com.example.cachewidget

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.os.Build
import android.app.usage.StorageStatsManager
import android.os.storage.StorageManager

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private val apps = mutableListOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = AppAdapter(apps) { app ->
            // Открываем страницу приложения — служба сама нажмёт «Хранилище» и «Очистить кэш»
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

    private fun scanApps() {
        val status = findViewById<TextView>(R.id.tv_status)

        // Нет права «Доступ к данным об использовании» — ведём на экран настроек
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

            // Показываем только приложения с реальным кэшем (от 1 МБ)
            if (cache >= 1L * 1024 * 1024) {
                apps.add(AppInfo(name, info.packageName, cache))
            }
        }

        // Сортируем: сначала приложения с большим кэшем
        apps.sortByDescending { it.cacheBytes }

        adapter.notifyDataSetChanged()
        status.text = "Приложений с кэшем: ${apps.size}"
    }
}
