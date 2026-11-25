package com.example.cachewidget

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    /** Короткая форма: без уровня — записывается как INFO */
    fun log(context: Context?, message: String) {
        log(context, Level.INFO, message)
    }

    /** Полная форма: с уровнем важности */
    fun log(context: Context?, level: Level, message: String) {
        if (context == null) return
        try {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val file = File(context.filesDir, "app_log.txt")

            // Читаем старое содержимое, отрезаем лишнее сверху
            val old = if (file.exists()) file.readText() else ""
            var lines = old.lines()
            if (lines.isNotEmpty() && lines.last().isBlank()) lines = lines.dropLast(1)
            if (lines.size > MAX_LINES) lines = lines.takeLast(MAX_LINES)

            file.writeText(lines.joinToString("\n") + "\n[$time] ${level.tag} $message")
        } catch (_: Exception) {
            // Журнал не должен ронять приложение
        }
    }

    /** Диалог с журналом: просмотр, отправка, очистка */
    fun show(context: Context) {
        try {
            val file = File(context.filesDir, "app_log.txt")
            val text = if (file.exists()) file.readText() else "Журнал пуст"

            MaterialAlertDialogBuilder(context)
                .setMessage("\n" + text.takeLast(6000))
                .setPositiveButton("Поделиться") { _, _ -> share(context, text) }
                .setNeutralButton("Очистить") { _, _ ->
                    try {
                        file.delete()
                        Toast.makeText(context, "Журнал очищен", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
                .show()
        } catch (_: Exception) {}
    }

    /** Отправка журнала в любое приложение: Telegram, почта, заметки и т.д. */
    private fun share(context: Context, text: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Журнал Cache Widget")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, "Отправить журнал"))
        } catch (_: Exception) {}
    }
}
