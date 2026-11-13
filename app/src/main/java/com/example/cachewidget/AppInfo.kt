package com.example.cachewidget

import android.graphics.drawable.Drawable

data class AppInfo(
    val name: String,
    val packageName: String,
    val cacheBytes: Long,
    val isHeader: Boolean = false,
    val icon: Drawable? = null
)
