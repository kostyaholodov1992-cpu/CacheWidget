package com.example.cachewidget

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppInfo(val name: String, val packageName: String, val cacheBytes: Long)

class AppAdapter(
    private val apps: MutableList<AppInfo>,
    private val onClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    class Holder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.img_icon)
        val name: TextView = view.findViewById(R.id.tv_name)
        val cache: TextView = view.findViewById(R.id.tv_cache)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val app = apps[position]
        h.name.text = app.name
        h.cache.text = if (app.cacheBytes > 0) "Кэш: ${formatBytes(app.cacheBytes)}" else "Кэш: —"
        try {
            h.icon.setImageDrawable(h.itemView.context.packageManager.getApplicationIcon(app.packageName))
        } catch (_: Exception) { }

        h.itemView.setOnClickListener { onClick(app) }
    }

    override fun getItemCount() = apps.size

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("Б", "КБ", "МБ", "ГБ")
        var value = bytes.toDouble()
        var idx = 0
        while (value >= 1024 && idx < units.size - 1) {
            value /= 1024; idx++
        }
        return String.format("%.1f %s", value, units[idx])
    }
}
