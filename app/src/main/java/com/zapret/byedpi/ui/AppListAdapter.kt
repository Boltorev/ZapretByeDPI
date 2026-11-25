package com.zapret.byedpi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.zapret.byedpi.databinding.ItemAppBinding

data class AppEntry(
    val packageName: String,
    val label: String
)

class AppListAdapter(
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.Holder>() {

    private var items: List<AppEntry> = emptyList()
    private var selected: Set<String> = emptySet()
    private var selectionEnabled = true

    fun submit(
        newItems: List<AppEntry>,
        newSelected: Set<String>,
        enabled: Boolean
    ) {
        items = newItems
        selected = newSelected
        selectionEnabled = enabled
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAppBinding.inflate(
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

    inner class Holder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: AppEntry) {
            binding.cbApp.setOnCheckedChangeListener(null)
            binding.cbApp.text = entry.label
            binding.cbApp.isChecked = selected.contains(entry.packageName)
            binding.cbApp.isEnabled = selectionEnabled
            binding.cbApp.alpha = if (selectionEnabled) 1f else 0.55f
            binding.tvPackage.text = entry.packageName

            binding.cbApp.setOnCheckedChangeListener { _, checked ->
                onToggle(entry.packageName, checked)
            }
            binding.root.setOnClickListener {
                if (!selectionEnabled) return@setOnClickListener
                binding.cbApp.isChecked = !binding.cbApp.isChecked
            }
        }
    }
}
