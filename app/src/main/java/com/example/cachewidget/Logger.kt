package com.example.cachewidget

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {

    private const val MAX_LINES = 300   // держим журнал коротким

    fun log(context: Context?, message: String) {
        if (context == null) return
        try {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val file = File(context.filesDir, "app_log.txt")

            // Читаем старое содержимое, отрезаем лишнее сверху
            val old = if (file.exists()) file.readText() else ""
            var lines = old.lines()
            if (lines.size > MAX_LINES) lines = lines.takeLast(MAX_LINES)

            file.writeText(lines.joinToString("\n") + "\n[$time] $message")
        } catch (_: Exception) {
            // Журнал не должен ронять приложение
        }
    }

    /** Показать журнал в диалоговом окне */
    fun show(context: Context) {
        try {
            val file = File(context.filesDir, "app_log.txt")
            val text = if (file.exists()) file.readText() else "Журнал пуст"
            android.app.AlertDialog.Builder(context)
                .setTitle("Журнал работы")
                .setMessage(text.takeLast(4000))
                .setPositiveButton("Очистить") { _, _ -> file.delete() }
                .setNegativeButton("Закрыть", null)
                .show()
        } catch (_: Exception) {}
    }
}
