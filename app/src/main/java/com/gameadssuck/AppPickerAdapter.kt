package com.gameadssuck

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gameadssuck.databinding.ItemAppPickerBinding

/** Represents an installed app in the picker list. */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val applicationInfo: ApplicationInfo
)

/**
 * RecyclerView adapter for the app-picker screen.
 * Shows all installed apps; tapping an item adds it to the watch list.
 */
class AppPickerAdapter(
    private val onAppSelected: (AppInfo) -> Unit
) : ListAdapter<AppInfo, AppPickerAdapter.ViewHolder>(DIFF_CALLBACK) {

    /** Full unfiltered list, kept so we can re-apply the search filter. */
    private var allApps: List<AppInfo> = emptyList()

    fun submitAllApps(apps: List<AppInfo>) {
        allApps = apps
        submitList(apps)
    }

    /** Filters the displayed list by the given query string. */
    fun filter(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        submitList(filtered)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppPickerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemAppPickerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppInfo) {
            val pm = binding.root.context.packageManager
            binding.tvAppName.text = app.appName
            binding.tvPackageName.text = app.packageName
            try {
                binding.ivAppIcon.setImageDrawable(pm.getApplicationIcon(app.applicationInfo))
            } catch (e: PackageManager.NameNotFoundException) {
                binding.ivAppIcon.setImageDrawable(null)
            }
            binding.root.setOnClickListener { onAppSelected(app) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo) =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo) =
                oldItem == newItem
        }
    }
}
