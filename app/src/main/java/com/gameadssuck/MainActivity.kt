package com.gameadssuck

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.gameadssuck.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar

/**
 * Main screen of the app.
 *
 * Displays:
 *  - A status banner showing whether the Accessibility Service is enabled.
 *  - The list of apps currently being watched for ads.
 *  - A FAB to open the app picker and add a new watched app.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var watchedAppsManager: WatchedAppsManager
    private lateinit var adapter: WatchedAppsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        watchedAppsManager = WatchedAppsManager(this)

        setupRecyclerView()
        setupFab()
        setupStatusBanner()
    }

    override fun onResume() {
        super.onResume()
        refreshWatchedApps()
        updateServiceStatusBanner()
    }

    // -----------------------------------------------------------------------
    // UI setup
    // -----------------------------------------------------------------------

    private fun setupRecyclerView() {
        adapter = WatchedAppsAdapter { packageName ->
            watchedAppsManager.removeWatchedPackage(packageName)
            refreshWatchedApps()
            Snackbar.make(binding.root, getString(R.string.app_removed, packageName), Snackbar.LENGTH_SHORT).show()
        }
        binding.rvWatchedApps.layoutManager = LinearLayoutManager(this)
        binding.rvWatchedApps.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
        binding.rvWatchedApps.adapter = adapter
    }

    private fun setupFab() {
        binding.fabAddApp.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
    }

    private fun setupStatusBanner() {
        binding.tvServiceStatus.setOnClickListener {
            // Open the system Accessibility Settings so the user can enable the service.
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    // -----------------------------------------------------------------------
    // State refresh
    // -----------------------------------------------------------------------

    private fun refreshWatchedApps() {
        val packages = watchedAppsManager.getWatchedPackages().toList().sorted()
        adapter.submitList(packages)
        binding.tvEmpty.visibility = if (packages.isEmpty()) View.VISIBLE else View.GONE
        binding.rvWatchedApps.visibility = if (packages.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateServiceStatusBanner() {
        val enabled = isAccessibilityServiceEnabled()
        if (enabled) {
            binding.tvServiceStatus.setText(R.string.status_service_enabled)
            binding.tvServiceStatus.setBackgroundColor(getColor(R.color.status_enabled_bg))
            binding.tvServiceStatus.setTextColor(getColor(R.color.status_enabled_text))
        } else {
            binding.tvServiceStatus.setText(R.string.status_service_disabled)
            binding.tvServiceStatus.setBackgroundColor(getColor(R.color.status_disabled_bg))
            binding.tvServiceStatus.setTextColor(getColor(R.color.status_disabled_text))
        }
    }

    /** Returns true when the AdDetectorService is currently enabled in system settings. */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedId = "$packageName/${AdDetectorService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        return splitter.any { it.equals(expectedId, ignoreCase = true) }
    }
}
