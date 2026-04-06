package com.gameadssuck

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.widget.SearchView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.gameadssuck.databinding.ActivityAppPickerBinding
import com.google.android.material.snackbar.Snackbar
import kotlin.concurrent.thread

/**
 * Shows a searchable list of all installed apps.
 * Tapping an app adds it to the watch list and shows a confirmation snackbar.
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private lateinit var watchedAppsManager: WatchedAppsManager
    private lateinit var adapter: AppPickerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_app_picker)

        watchedAppsManager = WatchedAppsManager(this)

        setupRecyclerView()
        setupSearch()
        loadApps()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // -----------------------------------------------------------------------
    // UI setup
    // -----------------------------------------------------------------------

    private fun setupRecyclerView() {
        adapter = AppPickerAdapter { app ->
            if (watchedAppsManager.isWatched(app.packageName)) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.app_already_watched, app.appName),
                    Snackbar.LENGTH_SHORT
                ).show()
            } else {
                watchedAppsManager.addWatchedPackage(app.packageName)
                Snackbar.make(
                    binding.root,
                    getString(R.string.app_added, app.appName),
                    Snackbar.LENGTH_SHORT
                ).show()
                finish()
            }
        }

        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
        binding.rvApps.adapter = adapter
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter(newText ?: "")
                return true
            }
        })
    }

    // -----------------------------------------------------------------------
    // App loading
    // -----------------------------------------------------------------------

    /** Loads installed apps on a background thread, then populates the list on the main thread. */
    private fun loadApps() {
        thread {
            val apps = runCatching {
                val pm = packageManager
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(launcherIntent, PackageManager.GET_META_DATA)
                    .map { it.activityInfo.applicationInfo }
                    .distinctBy { it.packageName }
                    .filter { it.packageName != packageName }
                    .map { info ->
                        AppInfo(
                            packageName = info.packageName,
                            appName = pm.getApplicationLabel(info).toString(),
                            applicationInfo = info
                        )
                    }
                    .sortedBy { it.appName.lowercase() }
            }.getOrDefault(emptyList())

            // Only update the UI if the Activity is still alive.
            if (!isDestroyed && !isFinishing) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.rvApps.visibility = View.VISIBLE
                    adapter.submitAllApps(apps)
                }
            }
        }
    }
}
