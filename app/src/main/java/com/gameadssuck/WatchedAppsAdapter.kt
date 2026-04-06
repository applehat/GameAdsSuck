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
 * Data class pairing a watched package name with its per-app ad strategy.
 */
data class WatchedAppItem(
    val packageName: String,
    val strategy: AdStrategy
)

/**
 * RecyclerView adapter that displays the list of apps currently being watched for ads.
 * Each item shows the app icon, name, package name, the current ad-dismiss strategy,
 * and a button to stop watching it.
 */
class WatchedAppsAdapter(
    private val onRemove: (packageName: String) -> Unit,
    private val onStrategyChanged: (packageName: String, strategy: AdStrategy) -> Unit
) : ListAdapter<WatchedAppItem, WatchedAppsAdapter.ViewHolder>(DIFF_CALLBACK) {

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

        fun bind(item: WatchedAppItem) {
            val ctx = binding.root.context
            val pm = ctx.packageManager

            // Resolve app label and icon, falling back gracefully if the package is gone.
            try {
                val info = pm.getApplicationInfo(item.packageName, 0)
                binding.tvAppName.text = pm.getApplicationLabel(info)
                binding.ivAppIcon.setImageDrawable(pm.getApplicationIcon(info))
            } catch (e: PackageManager.NameNotFoundException) {
                binding.tvAppName.text = item.packageName
                binding.ivAppIcon.setImageDrawable(null)
            }
            binding.tvPackageName.text = item.packageName

            // Show current strategy label — tapping it opens the strategy picker.
            binding.tvStrategy.text = ctx.getString(item.strategy.labelResId)
            binding.tvStrategy.setOnClickListener {
                showStrategyDialog(item)
            }

            binding.btnRemove.setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.confirm_remove_title)
                    .setMessage(
                        ctx.getString(
                            R.string.confirm_remove_message,
                            binding.tvAppName.text
                        )
                    )
                    .setPositiveButton(R.string.confirm_remove_yes) { _, _ ->
                        onRemove(item.packageName)
                    }
                    .setNegativeButton(R.string.confirm_remove_no, null)
                    .show()
            }
        }

        private fun showStrategyDialog(item: WatchedAppItem) {
            val ctx = binding.root.context
            val strategies = AdStrategy.entries
            val labels = strategies.map { strategy ->
                val label = ctx.getString(strategy.labelResId)
                val desc = ctx.getString(strategy.descResId)
                "$label\n$desc"
            }.toTypedArray()

            val currentIndex = strategies.indexOf(item.strategy)

            AlertDialog.Builder(ctx)
                .setTitle(R.string.strategy_dialog_title)
                .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                    val selected = strategies[which]
                    if (selected != item.strategy) {
                        onStrategyChanged(item.packageName, selected)
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.confirm_remove_no, null)
                .show()
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<WatchedAppItem>() {
            override fun areItemsTheSame(oldItem: WatchedAppItem, newItem: WatchedAppItem) =
                oldItem.packageName == newItem.packageName
            override fun areContentsTheSame(oldItem: WatchedAppItem, newItem: WatchedAppItem) =
                oldItem == newItem
        }
    }
}
