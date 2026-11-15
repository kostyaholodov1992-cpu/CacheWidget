package com.example.cachewidget

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val items: List<AppInfo>,
    private val onAppClick: (AppInfo) -> Unit,
    private val onHeaderClick: (AppInfo) -> Unit,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_APP = 1
    }

    // Выбор храним по packageName — переживает прокрутку, сворачивание секций и новый скан
    private val selectedApps = mutableSetOf<String>()

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tv_header)
    }

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_icon)
        val name: TextView = view.findViewById(R.id.tv_name)
        val cache: TextView = view.findViewById(R.id.tv_cache)
        val checkBox: CheckBox = view.findViewById(R.id.checkbox)
    }

    override fun getItemViewType(position: Int) =
        if (items[position].isHeader) TYPE_HEADER else TYPE_APP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_header, parent, false))
        } else {
            AppViewHolder(inflater.inflate(R.layout.item_app, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is HeaderViewHolder -> {
                holder.title.text = item.name
                holder.itemView.setOnClickListener { onHeaderClick(item) }
            }
            is AppViewHolder -> {
                holder.icon.setImageDrawable(item.icon)
                holder.name.text = item.name
                holder.cache.text = formatSize(item.cacheBytes)

                // Сначала гасим слушатель, потом ставим состояние — иначе при
                // прокрутке переиспользованная ячейка «перетаскивает» чужую галочку
                holder.checkBox.setOnCheckedChangeListener(null)
                holder.checkBox.isChecked = selectedApps.contains(item.packageName)

                holder.itemView.setOnClickListener { toggle(holder.adapterPosition) }
            }
        }
    }

    override fun getItemCount() = items.size

    // --- Логика выбора ---

    private fun toggle(position: Int) {
        val item = items[position]
        if (item.isHeader) return
        if (!selectedApps.remove(item.packageName)) {
            selectedApps.add(item.packageName)
        }
        notifyItemChanged(position)
        onSelectionChanged()
    }

    // Приложения из РАЗВЁРНУТЫХ секций
    private fun visibleApps(): List<AppInfo> {
        val result = mutableListOf<AppInfo>()
        var sectionOpen = true
        for (item in items) {
            if (item.isHeader) {
                sectionOpen = item.isExpanded
            } else if (sectionOpen) {
                result.add(item)
            }
        }
        return result
    }

    fun selectAll() {
        visibleApps().forEach { selectedApps.add(it.packageName) }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    val allSelected: Boolean
        get() {
            val visible = visibleApps()
            return visible.isNotEmpty() && visible.all { selectedApps.contains(it.packageName) }
        }

    val selectedCount: Int
        get() = selectedApps.size

    fun toggleAll() {
        if (allSelected) {
            selectedApps.clear()
            notifyDataSetChanged()
            onSelectionChanged()
        } else {
            selectAll()
        }
    }

    fun getSelectedApps(): List<AppInfo> =
        items.filter { !it.isHeader && selectedApps.contains(it.packageName) }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "Нет кэша"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 100) "${mb.toInt()} МБ" else String.format("%.1f МБ", mb)
    }
}
