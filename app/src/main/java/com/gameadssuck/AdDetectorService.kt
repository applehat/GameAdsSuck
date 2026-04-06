package com.gameadssuck

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * An AccessibilityService that monitors watched apps for advertisement interstitials.
 *
 * On modern Android versions third-party apps cannot reliably kill and relaunch another app,
 * so this service now tries to dismiss ad UI directly using accessibility actions. When no
 * dismiss control is found, it falls back to a Back action and then Home if needed.
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        watchedAppsManager = WatchedAppsManager(this)
        ensureNotificationChannel()
        startStatusNotification()
    }

    override fun onInterrupt() {
        // Required by AccessibilityService; nothing to do here.
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID_SERVICE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        if (isHandlingAd) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString().orEmpty()

        if (packageName == applicationContext.packageName || packageName in IGNORED_PACKAGES) {
            return
        }

        val isWatchedPackage = watchedAppsManager.isWatched(packageName)
        val looksLikeAd = isAdWindow(packageName, className, isWatchedPackage)

        if (isWatchedPackage && !looksLikeAd) {
            currentWatchedPackage = packageName
            return
        }

        val watchedPackage = when {
            isWatchedPackage -> packageName
            currentWatchedPackage != null -> currentWatchedPackage
            else -> null
        } ?: return

        if (!looksLikeAd) return

        val elapsed = System.currentTimeMillis() - lastActionTimeMs
        if (elapsed < COOLDOWN_MS) return

        handleDetectedAd(watchedPackage)
    }

    private fun isAdWindow(
        packageName: String,
        className: String,
        isWatchedPackage: Boolean
    ): Boolean {
        if (AD_SDK_PACKAGES.any { packageName.startsWith(it) }) return true
        if (AD_ACTIVITY_KEYWORDS.any { className.contains(it, ignoreCase = true) }) return true
        if (!isWatchedPackage) return false
        return activeWindowLooksLikeAd()
    }

    private fun activeWindowLooksLikeAd(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return try {
            val dismissNode = findDismissControl(rootNode)
            try {
                containsAdCopy(rootNode) && dismissNode != null
            } finally {
                dismissNode?.recycle()
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun handleDetectedAd(watchedPackageName: String) {
        isHandlingAd = true
        lastActionTimeMs = System.currentTimeMillis()
        showAdDetectedNotification(watchedPackageName)
        attemptDismissSequence(0)
    }

    private fun attemptDismissSequence(attempt: Int) {
        if (tryAutoDismissAd()) {
            resetAdHandling()
            return
        }

        if (attempt < MAX_DISMISS_ATTEMPTS) {
            mainHandler.postDelayed({ attemptDismissSequence(attempt + 1) }, DISMISS_RETRY_DELAY_MS)
            return
        }

        if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        resetAdHandling()
    }

    private fun tryAutoDismissAd(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return try {
            val dismissNode = findDismissControl(rootNode) ?: return false
            try {
                dismissNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } finally {
                dismissNode.recycle()
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun findDismissControl(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        if (matchesDismissControl(node)) {
            if (node.isClickable) return AccessibilityNodeInfo.obtain(node)
            val parent = node.parent
            if (parent?.isClickable == true) {
                return try {
                    AccessibilityNodeInfo.obtain(parent)
                } finally {
                    parent.recycle()
                }
            }
            parent?.recycle()
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index)
            val result = try {
                findDismissControl(child)
            } finally {
                child?.recycle()
            }
            if (result != null) return result
        }

        return null
    }

    private fun containsAdCopy(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val combinedText = sequenceOf(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.viewIdResourceName
        ).filterNotNull().joinToString(" ").lowercase()

        if (AD_COPY_MARKERS.any { combinedText.contains(it) }) {
            return true
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index)
            val childContainsAdCopy = try {
                containsAdCopy(child)
            } finally {
                child?.recycle()
            }
            if (childContainsAdCopy) return true
        }
        return false
    }

    private fun matchesDismissControl(node: AccessibilityNodeInfo): Boolean {
        val combinedText = sequenceOf(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.viewIdResourceName
        ).filterNotNull().joinToString(" ").lowercase()

        return DISMISS_CONTROL_MARKERS.any { combinedText.contains(it) }
    }

    private fun resetAdHandling() {
        mainHandler.postDelayed({
            currentWatchedPackage = null
            isHandlingAd = false
        }, POST_ACTION_SETTLE_MS)
    }

    @SuppressLint("MissingPermission")
    private fun startStatusNotification() {
        if (!hasNotificationPermission()) return

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val watched = watchedAppsManager.getWatchedPackages().size
        val notification = buildNotification(
            getString(R.string.notification_title),
            getString(R.string.notification_text, watched),
            pendingIntent,
            ongoing = true
        )
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_SERVICE, notification)
    }

    @SuppressLint("MissingPermission")
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
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_AD_DETECTED, notification)
    }

    /** Creates the notification channel on Android 8+. Safe to call multiple times. */
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                getString(R.string.notification_channel_id),
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager()?.createNotificationChannel(channel)
        }
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

    private fun notificationManager(): NotificationManager? =
        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    companion object {
        private const val DISMISS_RETRY_DELAY_MS = 700L
        private const val POST_ACTION_SETTLE_MS = 1_500L
        private const val COOLDOWN_MS = 5_000L
        private const val MAX_DISMISS_ATTEMPTS = 3

        private const val NOTIFICATION_ID_SERVICE = 1001
        private const val NOTIFICATION_ID_AD_DETECTED = 1002

        private val IGNORED_PACKAGES = setOf(
            "com.android.launcher3",
            "com.android.systemui"
        )

        private val DISMISS_CONTROL_MARKERS = listOf(
            "close",
            "skip",
            "dismiss",
            "no thanks",
            "not now"
        )

        private val AD_COPY_MARKERS = listOf(
            "advertisement",
            "sponsored",
            "rewarded",
            "install",
            "play now",
            "learn more",
            "ad choices"
        )

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
