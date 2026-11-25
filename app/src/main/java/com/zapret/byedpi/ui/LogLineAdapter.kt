package com.zapret.byedpi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.zapret.byedpi.R
import com.zapret.byedpi.core.LogEntry
import com.zapret.byedpi.core.LogLevel
import com.zapret.byedpi.databinding.ItemLogBinding

class LogLineAdapter : RecyclerView.Adapter<LogLineAdapter.Holder>() {

    private var items: List<LogEntry> = emptyList()

    fun submit(newItems: List<LogEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(private val binding: ItemLogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: LogEntry) {
            binding.tvLogLine.text = entry.toDisplayLine()
            val color = when (entry.level) {
                LogLevel.ERROR -> R.color.state_off
                LogLevel.WARN -> R.color.state_busy
                LogLevel.INFO -> R.color.log_text
                LogLevel.DEBUG -> R.color.text_secondary
            }
            binding.tvLogLine.setTextColor(
                ContextCompat.getColor(binding.root.context, color)
            )
        }
    }
}
