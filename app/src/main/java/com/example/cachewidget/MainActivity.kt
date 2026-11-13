package com.example.cachewidget

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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

        // Запрос разрешения на подсчёт кэша (спец. доступ «Доступ к данным об использовании»)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.PACKAGE_USAGE_STATS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.PACKAGE_USAGE_STATS), 1
            )
        }
    }

    private fun scanApps() {
        val status = findViewById<TextView>(R.id.tv_status)
        status.text = "Сканирую..."

        apps.clear()
        val pm = packageManager
        val installed = pm.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }

        for (info in installed) {
            val name = try { pm.getApplicationLabel(info).toString() } catch (_: Exception) { info.packageName }
            val cache = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val ssm = getSystemService(StorageStatsManager::class.java)
                    val stats = ssm.queryStatsForUid(info.storageUuid, info.uid)
                    stats.cacheBytes
                } catch (_: Exception) { 0L }
            } else 0L

            apps.add(AppInfo(name, info.packageName, cache))
        }

        // Сортируем: сначала приложения с большим кэшем
        apps.sortByDescending { it.cacheBytes }

        adapter.notifyDataSetChanged()
        status.text = "Найдено приложений: ${apps.size}"
    }
}
