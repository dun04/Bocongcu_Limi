package com.xiaomi.fixnotification

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.xiaomi.fixnotification.databinding.ItemDebloatAppCardBinding

class DebloatListAdapter(
    private var allApps: List<DebloatAppItem>,
    private val onSelectionChanged: (List<DebloatAppItem>) -> Unit,
    private val onQuickUninstallClick: (DebloatAppItem) -> Unit,
    private val onAppClick: (DebloatAppItem, View) -> Unit
) : RecyclerView.Adapter<DebloatListAdapter.DebloatViewHolder>() {

    private var displayedApps: List<DebloatAppItem> = allApps.toList()
    var currentFilterType: AppType? = null
        private set
    private var currentSearchQuery: String = ""
    var isCollapsed: Boolean = true
        private set

    fun updateData(newList: List<DebloatAppItem>) {
        allApps = newList
        applyFilter()
    }

    fun setCollapsed(collapsed: Boolean) {
        isCollapsed = collapsed
        notifyDataSetChanged()
    }

    fun getTotalCount(): Int = displayedApps.size

    fun filterByQuery(query: String) {
        currentSearchQuery = query.trim().lowercase()
        applyFilter()
    }

    fun filterByType(type: AppType?) {
        currentFilterType = type
        applyFilter()
    }

    private fun applyFilter() {
        displayedApps = allApps.filter { item ->
            val matchesQuery = currentSearchQuery.isEmpty() ||
                    item.name.lowercase().contains(currentSearchQuery) ||
                    item.packageName.lowercase().contains(currentSearchQuery) ||
                    item.description.lowercase().contains(currentSearchQuery)

            val matchesType = currentFilterType == null || item.appType == currentFilterType

            matchesQuery && matchesType
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

    fun selectOnlyBloatware() {
        for (item in allApps) {
            item.isSelected = (item.appType == AppType.BLOATWARE)
        }
        notifyDataSetChanged()
        notifySelectionUpdate()
    }

    fun getSelectedApps(): List<DebloatAppItem> {
        return allApps.filter { it.isSelected }
    }

    private fun notifySelectionUpdate() {
        onSelectionChanged(getSelectedApps())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DebloatViewHolder {
        val binding = ItemDebloatAppCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DebloatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DebloatViewHolder, position: Int) {
        holder.bind(displayedApps[position])
    }

    override fun getItemCount(): Int {
        return if (isCollapsed && displayedApps.size > 5) {
            5
        } else {
            displayedApps.size
        }
    }

    inner class DebloatViewHolder(private val binding: ItemDebloatAppCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DebloatAppItem) {
            binding.tvDebloatAppName.text = item.name
            binding.tvDebloatPackageName.text = item.packageName

            if (item.icon != null) {
                binding.ivDebloatAppIcon.setImageDrawable(item.icon)
            } else {
                binding.ivDebloatAppIcon.setImageResource(R.drawable.ic_default_app_icon)
            }

            // Gán nhãn loại ứng dụng: Hệ thống màu đỏ, Bloatware màu cam đỏ, Google xanh, User xanh
            binding.tvDebloatBadge.text = item.appType.displayName
            binding.tvDebloatBadge.setTextColor(Color.parseColor(item.appType.badgeTextColor))
            binding.tvDebloatBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor(item.appType.badgeBgColor))

            // Mô tả nếu là bloatware đã biết
            if (item.description.isNotEmpty()) {
                binding.tvDebloatDesc.visibility = View.VISIBLE
                binding.tvDebloatDesc.text = item.description
            } else {
                binding.tvDebloatDesc.visibility = View.GONE
            }

            // Checkbox: CHỈ thay đổi khi bấm ĐÚNG vào ô tích
            binding.cbSelectDebloat.setOnCheckedChangeListener(null)
            binding.cbSelectDebloat.isChecked = item.isSelected
            binding.cbSelectDebloat.setOnCheckedChangeListener { _, isChecked ->
                item.isSelected = isChecked
                notifySelectionUpdate()
            }

            // Ấn vào thẻ hoặc Ấn giữ 2s -> mở bảng thông tin chi tiết app
            binding.root.setOnClickListener {
                onAppClick(item, binding.root)
            }
            binding.root.setOnLongClickListener {
                onAppClick(item, binding.root)
                true
            }

            // Nút gỡ nhanh 1 app
            binding.btnQuickUninstall.setOnClickListener {
                onQuickUninstallClick(item)
            }
        }
    }
}
