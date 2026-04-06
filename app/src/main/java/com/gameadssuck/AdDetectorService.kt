package com.gameadssuck

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat

/**
 * An AccessibilityService that monitors window state changes to detect advertisement
 * interstitials in watched apps. When an ad is detected, it navigates to the home screen,
 * forces the watched app out of the foreground, then relaunches it to bypass the ad.
 *
 * Detection uses two signals:
 *  1. The new foreground window belongs to a known ad SDK package.
 *  2. The new foreground Activity class name contains ad-related keywords.
 */
class AdDetectorService : AccessibilityService() {

    private lateinit var watchedAppsManager: WatchedAppsManager

    /** The package name of the most recently active watched app. */
    private var currentWatchedPackage: String? = null

    /** Prevents re-entrant ad-handling sequences. */
    private var isHandlingAd = false

    /** Timestamp of the last action, used to enforce a cooldown period. */
    private var lastActionTimeMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        watchedAppsManager = WatchedAppsManager(this)
        startForegroundNotification()
    }

    override fun onInterrupt() {
        // Required by AccessibilityService; nothing to do here.
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        // Cancel the persistent notification since the service is stopping.
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID_SERVICE)
    }

    // -----------------------------------------------------------------------
    // Event handling
    // -----------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (isHandlingAd) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // Update tracking when user is inside a watched app.
        if (watchedAppsManager.isWatched(packageName)) {
            currentWatchedPackage = packageName
            return
        }

        // Ignore our own package and the Android launcher/system UI.
        if (packageName == applicationContext.packageName ||
            packageName == "com.android.launcher3" ||
            packageName == "com.android.systemui"
        ) return

        // Check whether this window looks like an ad interstitial.
        val watched = currentWatchedPackage ?: return
        if (isAdWindow(packageName, className)) {
            val elapsed = System.currentTimeMillis() - lastActionTimeMs
            if (elapsed < COOLDOWN_MS) return  // Respect cooldown between actions.
            dismissAdAndRelaunch(watched)
        }
    }

    // -----------------------------------------------------------------------
    // Ad detection
    // -----------------------------------------------------------------------

    /**
     * Returns true when the given package / class combination is recognised as an
     * advertisement interstitial.
     *
     * Detection strategy:
     *  - Package prefix matches a known ad-SDK namespace, OR
     *  - Activity class name contains an ad-related keyword.
     */
    private fun isAdWindow(packageName: String, className: String): Boolean {
        if (AD_SDK_PACKAGES.any { packageName.startsWith(it) }) return true
        if (AD_ACTIVITY_KEYWORDS.any { className.contains(it, ignoreCase = true) }) return true
        return false
    }

    // -----------------------------------------------------------------------
    // Dismiss ad and relaunch the game
    // -----------------------------------------------------------------------

    /**
     * Handles a detected ad interstitial by:
     *  1. Navigating to the home screen (sends the game to the background).
     *  2. Terminating the game process via ActivityManager.
     *  3. Relaunching the game so the player can continue without the ad.
     */
    private fun dismissAdAndRelaunch(packageName: String) {
        isHandlingAd = true
        lastActionTimeMs = System.currentTimeMillis()

        showAdDetectedNotification(packageName)

        // 1. Navigate to the home screen so the game moves to the background.
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. After a short delay, terminate the background process then relaunch.
        mainHandler.postDelayed({
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)

            mainHandler.postDelayed({
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                }
                // Reset state so the next ad can be detected.
                currentWatchedPackage = null
                isHandlingAd = false
            }, RELAUNCH_DELAY_MS)
        }, DISMISS_DELAY_MS)
    }

    // -----------------------------------------------------------------------
    // Notifications
    // -----------------------------------------------------------------------

    private fun startForegroundNotification() {
        if (!hasNotificationPermission()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                getString(R.string.notification_channel_id),
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val watched = watchedAppsManager.getWatchedPackages().size
        val notification = buildNotification(
            getString(R.string.notification_title),
            getString(R.string.notification_text, watched),
            pendingIntent,
            ongoing = true
        )
        // Post a regular ongoing notification. AccessibilityServices are already kept alive
        // by the system; startForeground() is not needed and causes crashes on Android 14
        // (targetSdk 34) when no foreground service type is declared.
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID_SERVICE, notification)
    }

    private fun showAdDetectedNotification(packageName: String) {
        if (!hasNotificationPermission()) return

        val appName = try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }

        val notification = buildNotification(
            getString(R.string.notification_ad_detected_title),
            getString(R.string.notification_ad_detected_text, appName),
            null
        )
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID_AD_DETECTED, notification)
    }

    private fun buildNotification(
        title: String,
        text: String,
        pendingIntent: PendingIntent?,
        ongoing: Boolean = false
    ): Notification {
        val builder = NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_delete)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (pendingIntent != null) builder.setContentIntent(pendingIntent)
        return builder.build()
    }

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    companion object {
        private const val DISMISS_DELAY_MS = 600L
        private const val RELAUNCH_DELAY_MS = 500L
        private const val COOLDOWN_MS = 5_000L

        private const val NOTIFICATION_ID_SERVICE = 1001
        private const val NOTIFICATION_ID_AD_DETECTED = 1002

        /**
         * Known ad-SDK package prefixes. A window whose package starts with any of
         * these strings is treated as an advertisement interstitial.
         */
        val AD_SDK_PACKAGES = listOf(
            "com.unity3d.ads",
            "com.vungle",
            "com.applovin",
            "com.chartboost",
            "com.mopub",
            "com.ironsource",
            "com.startapp",
            "com.inmobi",
            "com.tapjoy",
            "com.fyber",
            "com.adcolony",
            "com.mintegral",
            "com.digitalturbine",
            "com.supersonic",
            "net.pubnative",
            "com.ogury",
            "com.pangle"
        )

        /**
         * Keywords that, when present in a foreground Activity's class name, indicate
         * an advertisement interstitial regardless of the hosting package.
         *
         * This catches ad SDKs that run inside the game's own process (e.g. Google
         * AdMob's com.google.android.gms.ads.AdActivity).
         */
        val AD_ACTIVITY_KEYWORDS = listOf(
            "AdActivity",
            "InterstitialAd",
            "RewardedAd",
            "FullscreenAd",
            "AdFullscreen",
            "VideoAdActivity",
            "MraidActivity",
            "VastVideoActivity",
            "NativeAdActivity",
            "AdWebView",
            "BannerAdActivity",
            "RewardVideo",
            "OfferWall"
        )
    }
}
