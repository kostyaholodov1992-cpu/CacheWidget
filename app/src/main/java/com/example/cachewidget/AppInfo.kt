package com.example.cachewidget

import android.graphics.drawable.Drawable
import java.util.UUID

data class AppInfo(
    val name: String,
    val packageName: String,
    val cacheBytes: Long,
    val isHeader: Boolean = false,
    val icon: Drawable? = null,
    var isExpanded: Boolean = true,
    val uid: Int = 0,                 // UID приложения — для повторного замера кэша
    val storageUuid: UUID? = null     // Хранилище, где живёт приложение
)
