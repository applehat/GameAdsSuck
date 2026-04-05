package com.gameadssuck

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gameadssuck.databinding.ItemWatchedAppBinding

/**
 * RecyclerView adapter that displays the list of apps currently being watched for ads.
 * Each item shows the app icon, name, package name, and a button to stop watching it.
 */
class WatchedAppsAdapter(
    private val onRemove: (packageName: String) -> Unit
) : ListAdapter<String, WatchedAppsAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemWatchedAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemWatchedAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(packageName: String) {
            val pm = binding.root.context.packageManager

            // Resolve app label and icon, falling back gracefully if the package is gone.
            try {
                val info = pm.getApplicationInfo(packageName, 0)
                binding.tvAppName.text = pm.getApplicationLabel(info)
                binding.ivAppIcon.setImageDrawable(pm.getApplicationIcon(info))
            } catch (e: PackageManager.NameNotFoundException) {
                binding.tvAppName.text = packageName
                binding.ivAppIcon.setImageDrawable(null)
            }
            binding.tvPackageName.text = packageName

            binding.btnRemove.setOnClickListener {
                AlertDialog.Builder(binding.root.context)
                    .setTitle(R.string.confirm_remove_title)
                    .setMessage(
                        binding.root.context.getString(
                            R.string.confirm_remove_message,
                            binding.tvAppName.text
                        )
                    )
                    .setPositiveButton(R.string.confirm_remove_yes) { _, _ ->
                        onRemove(packageName)
                    }
                    .setNegativeButton(R.string.confirm_remove_no, null)
                    .show()
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
            override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        }
    }
}
