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

    private val userAppsCache = mutableListOf<AppInfo>()
    private val systemAppsCache = mutableListOf<AppInfo>()

    private var showUser = true
    private var showSystem = true

    private var clearList = listOf<AppInfo>()

    private var clearIndex = 0
    (остальное без изменений)
