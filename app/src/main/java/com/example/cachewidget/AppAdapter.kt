package com.example.cachewidget

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val items: MutableList<AppInfo>,
    private val onAppClick: (AppInfo) -> Unit,
    private val onHeaderClick: (AppInfo) -> Unit,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val selectedPackages = mutableSetOf<String>()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_APP = 1
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position].isHeader) TYPE_HEADER else TYPE_APP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_app, parent, false)
            AppViewHolder(view)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is HeaderViewHolder -> {
                holder.tvHeader.text = item.name
                holder.itemView.setOnClickListener {
                    onHeaderClick(items[holder.adapterPosition])
                }
            }
            is AppViewHolder -> {
                holder.tvName.text = item.name
                holder.tvCache.text = formatSize(item.cacheBytes)
                item.icon?.let { holder.ivIcon.setImageDrawable(it) }

                holder.checkBox.setOnCheckedChangeListener(null)
                holder.checkBox.isChecked = selectedPackages.contains(item.packageName)
                holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedPackages.add(item.packageName)
                    else selectedPackages.remove(item.packageName)
                    onSelectionChanged()
                }

                // Клик по строке — открыть настройки приложения (старое поведение)
                holder.itemView.setOnClickListener {
                    onAppClick(items[holder.adapterPosition])
                }
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val gb = bytes / 1073741824.0
        if (gb >= 1.0) return String.format("%.1f ГБ", gb)
        val mb = bytes / 1048576.0
        if (mb >= 1.0) return String.format("%.1f МБ", mb)
        return String.format("%.0f КБ", bytes / 1024.0)
    }

    fun toggleAll() {
        val appItems = items.filter { !it.isHeader }
        if (appItems.all { selectedPackages.contains(it.packageName) }) {
            selectedPackages.clear()
        } else {
            appItems.forEach { selectedPackages.add(it.packageName) }
        }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    val allSelected: Boolean
        get() {
            val appItems = items.filter { !it.isHeader }
            return appItems.isNotEmpty() && appItems.all { selectedPackages.contains(it.packageName) }
        }

    val selectedCount: Int
        get() = selectedPackages.size

    fun getSelectedApps(): List<AppInfo> =
        items.filter { !it.isHeader && selectedPackages.contains(it.packageName) }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvHeader: TextView = view.findViewById(R.id.tv_header)
    }

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.iv_icon)
        val tvName: TextView = view.findViewById(R.id.tv_name)
        val tvCache: TextView = view.findViewById(R.id.tv_cache)
        val checkBox: CheckBox = view.findViewById(R.id.checkbox)
    }
}
