package com.example.cachewidget

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        val title: TextView = view.findViewById(android.R.id.text1)
    }

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(android.R.id.text1)
        val cache: TextView = view.findViewById(android.R.id.text2)
    }

    override fun getItemViewType(position: Int) =
        if (items[position].isHeader) TYPE_HEADER else TYPE_APP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(android.R.layout.simple_list_item_1, parent, false))
        } else {
            AppViewHolder(inflater.inflate(android.R.layout.two_line_list_item, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is HeaderViewHolder -> {
                holder.title.text = item.name
                holder.title.setTypeface(null, Typeface.BOLD)
            }
            is AppViewHolder -> {
                holder.name.text = item.name
                holder.cache.text = formatSize(item.cacheBytes)
                holder.itemView.setOnClickListener { onClick(item) }
            }
        }
    }

    override fun getItemCount() = items.size

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 100) "${mb.toInt()} МБ" else String.format("%.1f МБ", mb)
    }
}
