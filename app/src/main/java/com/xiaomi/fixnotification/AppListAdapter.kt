package com.xiaomi.fixnotification

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.xiaomi.fixnotification.databinding.ItemAppCardBinding

class AppListAdapter(
    private var allApps: List<InstalledAppItem>,
    private val onSelectionChanged: (List<String>) -> Unit,
    private val onAppClick: (InstalledAppItem, View) -> Unit
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {

    private var displayedApps: List<InstalledAppItem> = allApps.toList()
    var isCollapsed: Boolean = true
        private set

    fun updateData(newList: List<InstalledAppItem>, triggerSelectionCallback: Boolean = false) {
        allApps = newList
        displayedApps = newList.toList()
        notifyDataSetChanged()
        if (triggerSelectionCallback) {
            notifySelectionUpdate()
        }
    }

    fun setCollapsed(collapsed: Boolean) {
        isCollapsed = collapsed
        notifyDataSetChanged()
    }

    fun getTotalCount(): Int = displayedApps.size

    fun filter(query: String) {
        val cleanQuery = query.trim().lowercase()
        displayedApps = if (cleanQuery.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.name.lowercase().contains(cleanQuery) ||
                        it.packageName.lowercase().contains(cleanQuery) ||
                        it.category.lowercase().contains(cleanQuery)
            }
        }
        notifyDataSetChanged()
    }

    fun selectAll(select: Boolean) {
        for (item in allApps) {
            item.isSelected = select
        }
        notifyDataSetChanged()
        notifySelectionUpdate()
    }

    fun getSelectedPackages(): List<String> {
        return allApps.filter { it.isSelected }.map { it.packageName }
    }

    private fun notifySelectionUpdate() {
        onSelectionChanged(getSelectedPackages())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(displayedApps[position])
    }

    override fun getItemCount(): Int {
        return if (isCollapsed && displayedApps.size > 5) {
            5
        } else {
            displayedApps.size
        }
    }

    inner class AppViewHolder(private val binding: ItemAppCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: InstalledAppItem) {
            val context = binding.root.context
            binding.tvAppName.text = item.name
            binding.tvAppPackage.text = item.packageName
            binding.tvAppCategory.text = item.category

            val (bgColorRes, textColorRes) = when (item.category) {
                "Chat" -> Pair(R.color.badge_chat_bg, R.color.badge_chat_text)
                "MXH" -> Pair(R.color.badge_mxh_bg, R.color.badge_mxh_text)
                "Ngân hàng", "Ví điện tử" -> Pair(R.color.badge_bank_bg, R.color.badge_bank_text)
                "GMS" -> Pair(R.color.badge_gms_bg, R.color.badge_gms_text)
                "Email" -> Pair(R.color.badge_email_bg, R.color.badge_email_text)
                else -> Pair(R.color.badge_default_bg, R.color.badge_default_text)
            }

            val badgeDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6 * context.resources.displayMetrics.density
                setColor(ContextCompat.getColor(context, bgColorRes))
            }
            binding.tvAppCategory.background = badgeDrawable
            binding.tvAppCategory.setTextColor(ContextCompat.getColor(context, textColorRes))

            if (item.icon != null) {
                binding.imgAppIcon.setImageDrawable(item.icon)
            } else {
                binding.imgAppIcon.setImageResource(R.drawable.ic_default_app_icon)
            }

            binding.cbAppSelect.setOnCheckedChangeListener(null)
            binding.cbAppSelect.isChecked = item.isSelected
            binding.cbAppSelect.setOnCheckedChangeListener { _, isChecked ->
                item.isSelected = isChecked
                notifySelectionUpdate()
            }

            binding.root.setOnClickListener {
                onAppClick(item, binding.root)
            }
            binding.root.setOnLongClickListener {
                onAppClick(item, binding.root)
                true
            }

            binding.btnAppInfo.setOnClickListener {
                onAppClick(item, binding.btnAppInfo)
            }
        }
    }
}
