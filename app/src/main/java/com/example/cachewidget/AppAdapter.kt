package com.example.cachewidget

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val items: List<AppInfo>,
    private val onClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_APP = 1
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tv_header)
    }

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_icon)
        val name: TextView = view.findViewById(R.id.tv_name)
        val cache: TextView = view.findViewById(R.id.tv_cache)
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
            is HeaderViewHolder -> holder.title.text = item.name
            is AppViewHolder -> {
                holder.icon.setImageDrawable(item.icon)
                holder.name.text = item.name
                holder.cache.text = formatSize(item.cacheBytes)
                holder.itemView.setOnClickListener { onClick(item) }
            }
        }
    }

    override fun getItemCount() = items.size

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "Нет кэша"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 100) "${mb.toInt()} МБ" else String.format("%.1f МБ", mb)
    }
}
