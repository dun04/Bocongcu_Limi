package com.xiaomi.fixnotification

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.xiaomi.fixnotification.databinding.ItemRestoreAppCardBinding

class RestoreAppsAdapter(
    private var allApps: List<DebloatAppItem>,
    private val onSelectionChanged: (List<DebloatAppItem>) -> Unit,
    private val onQuickRestore: (DebloatAppItem) -> Unit
) : RecyclerView.Adapter<RestoreAppsAdapter.RestoreViewHolder>() {

    private var displayedApps: List<DebloatAppItem> = allApps.toList()

    fun updateData(newList: List<DebloatAppItem>) {
        allApps = newList
        displayedApps = newList.toList()
        notifyDataSetChanged()
        notifySelectionUpdate()
    }

    fun filter(query: String) {
        val cleanQuery = query.trim().lowercase()
        displayedApps = if (cleanQuery.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.name.lowercase().contains(cleanQuery) ||
                        it.packageName.lowercase().contains(cleanQuery) ||
                        it.description.lowercase().contains(cleanQuery)
            }
        }
        notifyDataSetChanged()
    }

    fun selectAll(select: Boolean) {
        for (item in displayedApps) {
            item.isSelected = select
        }
        notifyDataSetChanged()
        notifySelectionUpdate()
    }

    fun getSelectedApps(): List<DebloatAppItem> {
        return allApps.filter { it.isSelected }
    }

    fun getTotalCount(): Int = displayedApps.size

    private fun notifySelectionUpdate() {
        onSelectionChanged(getSelectedApps())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RestoreViewHolder {
        val binding = ItemRestoreAppCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RestoreViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RestoreViewHolder, position: Int) {
        holder.bind(displayedApps[position])
    }

    override fun getItemCount(): Int = displayedApps.size

    inner class RestoreViewHolder(private val binding: ItemRestoreAppCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DebloatAppItem) {
            binding.tvRestoreAppName.text = item.name
            binding.tvRestorePackageName.text = item.packageName

            if (item.icon != null) {
                binding.ivRestoreAppIcon.setImageDrawable(item.icon)
            } else {
                binding.ivRestoreAppIcon.setImageResource(R.drawable.ic_default_app_icon)
            }

            binding.cbRestoreSelect.setOnCheckedChangeListener(null)
            binding.cbRestoreSelect.isChecked = item.isSelected
            binding.cbRestoreSelect.setOnCheckedChangeListener { _, isChecked ->
                item.isSelected = isChecked
                notifySelectionUpdate()
            }

            binding.root.setOnClickListener {
                binding.cbRestoreSelect.isChecked = !binding.cbRestoreSelect.isChecked
            }

            binding.btnQuickRestore.setOnClickListener {
                onQuickRestore(item)
            }
        }
    }
}
