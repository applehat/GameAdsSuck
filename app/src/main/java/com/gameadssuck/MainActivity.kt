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
import androidx.appcompat.app.AlertDialog
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
 *  - Status banners for Accessibility Service, Restricted Settings, and Notifications.
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
                openAppDetailsSettings()
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
        setupStatusBanners()
    }

    override fun onResume() {
        super.onResume()
        refreshWatchedApps()
        updateServiceStatusBanner()
        updateRestrictedSettingsBanner()
        updateNotificationStatusBanner()
    }

    // -----------------------------------------------------------------------
    // UI setup
    // -----------------------------------------------------------------------

    private fun setupRecyclerView() {
        adapter = WatchedAppsAdapter(
            onRemove = { packageName ->
                watchedAppsManager.removeWatchedPackage(packageName)
                refreshWatchedApps()
                Snackbar.make(binding.root, getString(R.string.app_removed, packageName), Snackbar.LENGTH_SHORT).show()
            },
            onStrategyChanged = { packageName, strategy ->
                watchedAppsManager.setStrategy(packageName, strategy)
                refreshWatchedApps()
            }
        )
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

    private fun setupStatusBanners() {
        // Accessibility service banner — tap opens Accessibility Settings.
        binding.tvServiceStatus.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Restricted settings banner — tap shows instructional dialog.
        binding.tvRestrictedSettings.setOnClickListener {
            showRestrictedSettingsDialog()
        }

        // Notification permission banner — tap requests the permission.
        binding.tvNotificationStatus.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getPreferences(MODE_PRIVATE).edit()
                    .putBoolean(PREF_NOTIFICATION_ASKED, true)
                    .apply()
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Permission guidance
    // -----------------------------------------------------------------------

    /**
     * Shows a dialog explaining how to enable "Allow restricted settings"
     * on Android 13+ for sideloaded apps, then opens App Details.
     */
    private fun showRestrictedSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.restricted_settings_title)
            .setMessage(R.string.restricted_settings_message)
            .setPositiveButton(R.string.restricted_settings_ok) { _, _ ->
                openAppDetailsSettings()
            }
            .setNegativeButton(R.string.restricted_settings_cancel, null)
            .show()
    }

    /** Opens the system app-details settings so the user can manage permissions. */
    private fun openAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    // -----------------------------------------------------------------------
    // State refresh
    // -----------------------------------------------------------------------

    private fun refreshWatchedApps() {
        val items = watchedAppsManager.getWatchedPackages()
            .sorted()
            .map { WatchedAppItem(it, watchedAppsManager.getStrategy(it)) }
        adapter.submitList(items)
        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.rvWatchedApps.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
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

    /**
     * Shows the "Allow restricted settings" banner on Android 13+ when the
     * accessibility service is not yet enabled. This is necessary for
     * sideloaded (non-Play-Store) apps.
     */
    private fun updateRestrictedSettingsBanner() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            binding.tvRestrictedSettings.visibility = View.GONE
            return
        }
        // Show the banner when the service is disabled — the user may need
        // restricted settings turned on before they can enable it.
        val serviceEnabled = isAccessibilityServiceEnabled()
        binding.tvRestrictedSettings.visibility = if (serviceEnabled) View.GONE else View.VISIBLE
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
