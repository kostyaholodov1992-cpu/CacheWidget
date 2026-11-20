package com.example.cachewidget

import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {

    /** Уровни важности: INFO — обычные шаги, WARN — отклонения, ERROR — сбои */
    enum class Level(val tag: String) {
        INFO("INFO "),
        WARN("WARN "),
        ERROR("ERROR")
    }

    private const val MAX_LINES = 500   // держим журнал коротким

    fun log(context: Context?, level: Level = Level.INFO, message: String) {
        if (context == null) return
        try {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val file = File(context.filesDir, "app_log.txt")

            // Читаем старое содержимое, отрезаем лишнее сверху
            val old = if (file.exists()) file.readText() else ""
            var lines = old.lines()
            if (lines.isNotEmpty() && lines.last().isBlank()) lines = lines.dropLast(1)
