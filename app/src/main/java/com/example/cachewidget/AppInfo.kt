package com.example.cachewidget

data class AppInfo(
    val name: String,
    val packageName: String,
    val cacheBytes: Long,
    val isHeader: Boolean = false
)
