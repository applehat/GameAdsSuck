package com.gameadssuck

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    /** Launcher for the POST_NOTIFICATIONS runtime permission request. */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) &&
                getPreferences(MODE_PRIVATE).getBoolean(PREF_NOTIFICATION_ASKED, false)
            ) {
                // Permanently denied — take the user to app settings so they can enable it.
                openNotificationSettings()
            }
            updateNotificationStatusBanner()
        }

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
        updateNotificationStatusBanner()
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
        binding.tvNotificationStatus.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getPreferences(MODE_PRIVATE).edit()
                    .putBoolean(PREF_NOTIFICATION_ASKED, true)
                    .apply()
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /** Opens the system app-details settings so the user can manually grant permissions. */
    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
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
            binding.tvServiceStatus.setBackgroundColor(ContextCompat.getColor(this, R.color.status_enabled_bg))
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_enabled_text))
        } else {
            binding.tvServiceStatus.setText(R.string.status_service_disabled)
            binding.tvServiceStatus.setBackgroundColor(ContextCompat.getColor(this, R.color.status_disabled_bg))
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disabled_text))
        }
    }

    private fun updateNotificationStatusBanner() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            binding.tvNotificationStatus.visibility = View.GONE
            return
        }
        binding.tvNotificationStatus.visibility = if (hasNotificationPermission()) View.GONE else View.VISIBLE
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

    companion object {
        private const val PREF_NOTIFICATION_ASKED = "notification_permission_asked"
    }
}
