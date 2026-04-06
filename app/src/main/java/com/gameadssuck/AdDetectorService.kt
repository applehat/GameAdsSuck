package com.gameadssuck

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

/**
 * Aggressive AccessibilityService that uses every available strategy to detect and
 * dismiss advertisement interstitials in watched apps.
 *
 * Detection uses three signals:
 *  1. The new foreground window belongs to a known ad-SDK package.
 *  2. The new foreground Activity class name contains ad-related keywords.
 *  3. The window content tree contains known close/dismiss/skip elements.
 *
 * Dismissal cascades through increasingly aggressive strategies:
 *  1. Find and click a Close/Skip/X button in the accessibility tree.
 *  2. Gesture-tap common close-button screen coordinates (top-right corner).
 *  3. Perform BACK action (repeated).
 *  4. Kill the ad process and relaunch the watched game.
 *  5. HOME as a last resort.
 */
class AdDetectorService : AccessibilityService() {

    private lateinit var watchedAppsManager: WatchedAppsManager

    /** The package name of the most recently active watched app. */
    private var currentWatchedPackage: String? = null

    /** Prevents re-entrant ad-handling sequences. */
    private var isHandlingAd = false

    /** Timestamp of the last action, used to enforce a cooldown period. */
    private var lastActionTimeMs = 0L

    /** Counter for how many dismiss steps we've tried in the current ad sequence. */
    private var dismissAttempt = 0

    /** The package of the detected ad (for kill fallback). */
    private var currentAdPackage: String? = null

    /** Tracks whether the last foreground event was the watched app returning. */
    private var lastForegroundWasWatched = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching {
            watchedAppsManager = WatchedAppsManager(this)
            ensureNotificationChannel()
            startForegroundNotification()
            Log.i(TAG, "AdDetectorService connected and ready")
        }.onFailure {
            Log.e(TAG, "Failed to initialize accessibility service", it)
        }
    }

    override fun onInterrupt() {
        // Required by AccessibilityService; nothing to do here.
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        notificationManager()?.cancel(NOTIFICATION_ID_SERVICE)
    }

    // -----------------------------------------------------------------------
    // Event handling
    // -----------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching {
            if (!::watchedAppsManager.isInitialized) return
            if (event == null) return

            val packageName = event.packageName?.toString() ?: return
            val className = event.className?.toString() ?: ""
            val eventType = event.eventType

            // Update tracking when user is inside a watched app.
            if (watchedAppsManager.isWatched(packageName)) {
                currentWatchedPackage = packageName
                if (isHandlingAd) {
                    // The watched app came back to the foreground — the ad was dismissed.
                    Log.i(TAG, "Watched app $packageName returned to foreground, ad dismissed")
                    lastForegroundWasWatched = true
                    resetHandlingState()
                }
                return
            }

            // Ignore our own package and system chrome.
            if (isSystemPackage(packageName)) return

            // === WINDOW STATE CHANGED — primary ad detection ===
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val watched = currentWatchedPackage ?: return

                if (isAdWindow(packageName, className)) {
                    val elapsed = System.currentTimeMillis() - lastActionTimeMs
                    if (elapsed < COOLDOWN_MS) return
                    if (isHandlingAd) return

                    Log.i(TAG, "Ad detected! pkg=$packageName class=$className")
                    currentAdPackage = packageName
                    startDismissSequence(watched)
                }
            }

            // === WINDOW CONTENT CHANGED — opportunistic close-button scanning ===
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && isHandlingAd) {
                // While we're handling an ad, keep scanning for newly-appeared close buttons.
                tryClickCloseButton(event.source)
            }
        }.onFailure {
            Log.e(TAG, "Event handling failed", it)
            resetHandlingState()
        }
    }

    // -----------------------------------------------------------------------
    // Ad detection
    // -----------------------------------------------------------------------

    private fun isAdWindow(packageName: String, className: String): Boolean {
        // Signal 1: Known ad-SDK package prefix.
        if (AD_SDK_PACKAGES.any { packageName.startsWith(it) }) return true
        // Signal 2: Activity class name contains ad keyword.
        if (AD_ACTIVITY_KEYWORDS.any { className.contains(it, ignoreCase = true) }) return true
        // Signal 3: The ad is from Google Play Services (AdMob, etc.)
        if (packageName == "com.google.android.gms" &&
            AD_ACTIVITY_KEYWORDS.any { className.contains(it, ignoreCase = true) }) return true
        return false
    }

    private fun isSystemPackage(packageName: String): Boolean {
        return packageName == applicationContext.packageName ||
                packageName == "com.android.launcher3" ||
                packageName == "com.android.launcher" ||
                packageName == "com.google.android.apps.nexuslauncher" ||
                packageName == "com.android.systemui" ||
                packageName == "com.android.settings" ||
                packageName == "com.sec.android.app.launcher" || // Samsung
                packageName == "com.huawei.android.launcher" || // Huawei
                packageName == "com.miui.home" // Xiaomi
    }

    // -----------------------------------------------------------------------
    // Multi-strategy dismiss sequence
    // -----------------------------------------------------------------------

    /**
     * Kicks off a dismiss sequence using the per-app strategy configured
     * for the watched package.
     */
    private fun startDismissSequence(watchedPackage: String) {
        isHandlingAd = true
        lastActionTimeMs = System.currentTimeMillis()
        dismissAttempt = 0

        showAdDetectedNotification(watchedPackage)

        val strategy = watchedAppsManager.getStrategy(watchedPackage)
        Log.i(TAG, "Using strategy ${strategy.key} for $watchedPackage")

        when (strategy) {
            AdStrategy.INSTANT_KILL -> {
                // Skip all close-button logic — immediately kill + relaunch.
                mainHandler.postDelayed({
                    killAndRelaunch(watchedPackage)
                }, INSTANT_KILL_DELAY_MS)
            }
            AdStrategy.BACK_ONLY -> {
                // Just press BACK a couple of times.
                mainHandler.postDelayed({
                    executeBackOnlyStep(watchedPackage)
                }, INITIAL_SCAN_DELAY_MS)
            }
            AdStrategy.CLOSE_AND_BACK -> {
                mainHandler.postDelayed({
                    executeCloseAndBackStep(watchedPackage)
                }, INITIAL_SCAN_DELAY_MS)
            }
            AdStrategy.AUTO -> {
                // Full cascading sequence.
                mainHandler.postDelayed({
                    executeAutoDismissStep(watchedPackage)
                }, INITIAL_SCAN_DELAY_MS)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Strategy: AUTO — full cascading dismiss
    // -----------------------------------------------------------------------

    private fun executeAutoDismissStep(watchedPackage: String) {
        if (!isHandlingAd) return

        dismissAttempt++
        Log.i(TAG, "Auto dismiss attempt #$dismissAttempt for ad in $watchedPackage")

        when (dismissAttempt) {
            1 -> {
                val clicked = scanAndClickCloseButton()
                if (clicked) {
                    Log.i(TAG, "Auto step 1: Found and clicked close button")
                    scheduleAutoVerification(watchedPackage)
                } else {
                    mainHandler.postDelayed({ executeAutoDismissStep(watchedPackage) }, STEP_DELAY_MS)
                }
            }
            2 -> {
                val tapped = tryGestureTapClosePositions()
                if (tapped) Log.i(TAG, "Auto step 2: Gesture-tapped close position")
                scheduleAutoVerification(watchedPackage)
            }
            3 -> {
                Log.i(TAG, "Auto step 3: BACK action")
                performGlobalAction(GLOBAL_ACTION_BACK)
                scheduleAutoVerification(watchedPackage)
            }
            4 -> {
                Log.i(TAG, "Auto step 4: Second BACK action")
                performGlobalAction(GLOBAL_ACTION_BACK)
                scheduleAutoVerification(watchedPackage)
            }
            5 -> {
                val clicked = scanAndClickCloseButton()
                Log.i(TAG, "Auto step 5: Re-scan tree, found=$clicked")
                if (!clicked) {
                    mainHandler.postDelayed({ executeAutoDismissStep(watchedPackage) }, STEP_DELAY_MS)
                } else {
                    scheduleAutoVerification(watchedPackage)
                }
            }
            6 -> {
                Log.i(TAG, "Auto step 6: Kill + relaunch")
                killAndRelaunch(watchedPackage)
            }
            else -> {
                Log.i(TAG, "Auto step 7: HOME fallback")
                performGlobalAction(GLOBAL_ACTION_HOME)
                mainHandler.postDelayed({
                    relaunchApp(watchedPackage)
                    resetHandlingState()
                }, RELAUNCH_DELAY_MS)
            }
        }
    }

    private fun scheduleAutoVerification(watchedPackage: String) {
        mainHandler.postDelayed({
            if (!isHandlingAd) return@postDelayed
            executeAutoDismissStep(watchedPackage)
        }, VERIFY_DELAY_MS)
    }

    // -----------------------------------------------------------------------
    // Strategy: CLOSE_AND_BACK
    // -----------------------------------------------------------------------

    private fun executeCloseAndBackStep(watchedPackage: String) {
        if (!isHandlingAd) return

        dismissAttempt++
        Log.i(TAG, "CloseAndBack step #$dismissAttempt for $watchedPackage")

        when (dismissAttempt) {
            1 -> {
                val clicked = scanAndClickCloseButton()
                if (clicked) {
                    Log.i(TAG, "CloseAndBack: Found close button")
                    scheduleCloseAndBackVerification(watchedPackage)
                } else {
                    mainHandler.postDelayed({ executeCloseAndBackStep(watchedPackage) }, STEP_DELAY_MS)
                }
            }
            2 -> {
                val tapped = tryGestureTapClosePositions()
                if (tapped) Log.i(TAG, "CloseAndBack: Gesture-tapped close position")
                scheduleCloseAndBackVerification(watchedPackage)
            }
            3 -> {
                Log.i(TAG, "CloseAndBack: BACK action")
                performGlobalAction(GLOBAL_ACTION_BACK)
                scheduleCloseAndBackVerification(watchedPackage)
            }
            4 -> {
                Log.i(TAG, "CloseAndBack: Second BACK action")
                performGlobalAction(GLOBAL_ACTION_BACK)
                scheduleCloseAndBackVerification(watchedPackage)
            }
            5 -> {
                val clicked = scanAndClickCloseButton()
                Log.i(TAG, "CloseAndBack: Re-scan, found=$clicked")
                if (!clicked) {
                    // Give up — don't kill the app.
                    Log.i(TAG, "CloseAndBack: Exhausted — giving up")
                    resetHandlingState()
                } else {
                    scheduleCloseAndBackVerification(watchedPackage)
                }
            }
            else -> {
                Log.i(TAG, "CloseAndBack: Exhausted")
                resetHandlingState()
            }
        }
    }

    private fun scheduleCloseAndBackVerification(watchedPackage: String) {
        mainHandler.postDelayed({
            if (!isHandlingAd) return@postDelayed
            executeCloseAndBackStep(watchedPackage)
        }, VERIFY_DELAY_MS)
    }

    // -----------------------------------------------------------------------
    // Strategy: BACK_ONLY
    // -----------------------------------------------------------------------

    private fun executeBackOnlyStep(watchedPackage: String) {
        if (!isHandlingAd) return

        dismissAttempt++
        Log.i(TAG, "BackOnly step #$dismissAttempt for $watchedPackage")

        if (dismissAttempt <= 3) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            mainHandler.postDelayed({
                if (!isHandlingAd) return@postDelayed
                executeBackOnlyStep(watchedPackage)
            }, VERIFY_DELAY_MS)
        } else {
            Log.i(TAG, "BackOnly: Exhausted")
            resetHandlingState()
        }
    }

    /** Schedule a verification check — if the ad is still showing, proceed to next step. */
    private fun scheduleVerification(watchedPackage: String) {
        mainHandler.postDelayed({
            if (!isHandlingAd) return@postDelayed
            // If the user has gone back to the watched app, we're done.
            // Otherwise try the next step.
            executeAutoDismissStep(watchedPackage)
        }, VERIFY_DELAY_MS)
    }

    // -----------------------------------------------------------------------
    // Strategy 1: Find and click Close/Skip/X buttons in accessibility tree
    // -----------------------------------------------------------------------

    /**
     * Walks the entire accessibility tree looking for clickable nodes that
     * look like ad close/skip buttons. Clicks the first one found.
     */
    private fun scanAndClickCloseButton(): Boolean {
        return runCatching {
            val rootNode = rootInActiveWindow ?: return false
            val result = findCloseButtonInTree(rootNode)
            safeRecycle(rootNode)
            result
        }.getOrDefault(false)
    }

    /**
     * Attempts to click a close button on a specific node (used during
     * content-changed events for opportunistic scanning).
     */
    private fun tryClickCloseButton(node: AccessibilityNodeInfo?) {
        runCatching {
            node ?: return
            findCloseButtonInTree(node)
            safeRecycle(node)
        }
    }

    /**
     * Recursively searches for close/dismiss/skip buttons.
     * Returns true if a button was found and clicked.
     */
    private fun findCloseButtonInTree(node: AccessibilityNodeInfo): Boolean {
        return runCatching {
            // Check this node.
            if (isCloseButton(node)) {
                if (clickNode(node)) return true
            }

            // Search children.
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (findCloseButtonInTree(child)) {
                    safeRecycle(child)
                    return true
                }
                safeRecycle(child)
            }
            false
        }.getOrDefault(false)
    }

    /**
     * Determines if a node looks like a close/dismiss/skip button for an ad.
     */
    private fun isCloseButton(node: AccessibilityNodeInfo): Boolean {
        // Must be visible and either clickable or has a clickable parent.
        if (!node.isVisibleToUser) return false

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.toString() ?: ""
        val className = node.className?.toString() ?: ""

        // Check text content.
        val textLower = text.lowercase()
        val descLower = desc.lowercase()
        val idLower = viewId.lowercase()

        // ===  Close/skip/dismiss signals ===
        for (keyword in CLOSE_BUTTON_TEXTS) {
            if (textLower.contains(keyword)) return isClickableOrParent(node)
            if (descLower.contains(keyword)) return isClickableOrParent(node)
        }

        // Check resource IDs for close button patterns.
        for (keyword in CLOSE_BUTTON_IDS) {
            if (idLower.contains(keyword)) return isClickableOrParent(node)
        }

        // Tiny views with "X" or "✕" text are almost certainly close buttons.
        if (text.length <= 2 && text in X_SYMBOLS) {
            return isClickableOrParent(node)
        }

        // Small ImageView/ImageButton in the top-right corner → likely close button.
        if ((className.contains("ImageView") || className.contains("ImageButton")) &&
            isInTopRightCorner(node)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val w = bounds.width()
            val h = bounds.height()
            if (w in MIN_CLOSE_BUTTON_PX..MAX_CLOSE_BUTTON_PX &&
                h in MIN_CLOSE_BUTTON_PX..MAX_CLOSE_BUTTON_PX) {
                return isClickableOrParent(node)
            }
        }

        return false
    }

    private fun isClickableOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return true
        // Walk up to check if a parent wrapper is clickable.
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 3) {
            if (parent.isClickable) {
                safeRecycle(parent)
                return true
            }
            val next = parent.parent
            safeRecycle(parent)
            parent = next
            depth++
        }
        safeRecycle(parent)
        return false
    }

    private fun isInTopRightCorner(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        // Top-right: x > 65% of screen, y < 25% of screen.
        return bounds.left > screenWidth * TOP_RIGHT_X_THRESHOLD &&
                bounds.top < displayMetrics.heightPixels * TOP_RIGHT_Y_THRESHOLD
    }

    /**
     * Clicks a node. If the node itself isn't clickable, clicks its clickable parent.
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // Walk up to find a clickable ancestor.
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 3) {
            if (parent.isClickable) {
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                safeRecycle(parent)
                return result
            }
            val next = parent.parent
            safeRecycle(parent)
            parent = next
            depth++
        }
        safeRecycle(parent)
        return false
    }

    // -----------------------------------------------------------------------
    // Strategy 2: Gesture tap on common close-button positions
    // -----------------------------------------------------------------------

    /**
     * Dispatches gesture taps on positions where close buttons typically appear:
     *  - Top-right corner (most common for interstitial ads)
     *  - Top-left corner (some ad networks)
     */
    private fun tryGestureTapClosePositions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false // Gestures require API 24+.

        val displayMetrics = resources.displayMetrics
        val screenW = displayMetrics.widthPixels.toFloat()

        var dispatched = false

        // Top-right corner — most common close button position.
        dispatched = dispatchTap(screenW - 50f, 90f) || dispatched
        // Slightly inset from top-right (padded ads).
        mainHandler.postDelayed({
            runCatching { dispatchTap(screenW - 100f, 140f) }
        }, 200)
        // Top-left corner (some networks).
        mainHandler.postDelayed({
            runCatching { dispatchTap(80f, 90f) }
        }, 400)

        return dispatched
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return runCatching {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }.getOrDefault(false)
    }

    // -----------------------------------------------------------------------
    // Strategy 3: Kill process and relaunch
    // -----------------------------------------------------------------------

    private fun killAndRelaunch(watchedPackage: String) {
        runCatching {
            // Go HOME first so the ad isn't in the foreground.
            performGlobalAction(GLOBAL_ACTION_HOME)

            mainHandler.postDelayed({
                runCatching {
                    // Kill the ad's background process if possible.
                    val adPkg = currentAdPackage
                    if (adPkg != null) {
                        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
                        am?.killBackgroundProcesses(adPkg)
                        Log.i(TAG, "Killed background processes of $adPkg")
                    }
                }

                // Relaunch the game after a short delay.
                mainHandler.postDelayed({
                    relaunchApp(watchedPackage)
                    resetHandlingState()
                }, RELAUNCH_DELAY_MS)
            }, KILL_DELAY_MS)
        }.onFailure {
            Log.e(TAG, "Kill + relaunch failed", it)
            resetHandlingState()
        }
    }

    private fun relaunchApp(packageName: String) {
        runCatching {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                startActivity(launchIntent)
                Log.i(TAG, "Relaunched $packageName")
            } else {
                Log.w(TAG, "No launch intent for $packageName")
            }
        }.onFailure {
            Log.e(TAG, "Failed to relaunch $packageName", it)
        }
    }

    // -----------------------------------------------------------------------
    // Notifications
    // -----------------------------------------------------------------------

    private fun startForegroundNotification() {
        if (!hasNotificationPermission()) return

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
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
        notificationManager()?.notify(NOTIFICATION_ID_SERVICE, notification)
    }

    private fun showAdDetectedNotification(packageName: String) {
        if (!hasNotificationPermission()) return

        val appName = runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)

        val notification = buildNotification(
            getString(R.string.notification_ad_detected_title),
            getString(R.string.notification_ad_detected_text, appName),
            null
        )
        notificationManager()?.notify(NOTIFICATION_ID_AD_DETECTED, notification)
    }

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

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun resetHandlingState() {
        isHandlingAd = false
        dismissAttempt = 0
        currentAdPackage = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(NOTIFICATION_SERVICE) as? NotificationManager

    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        runCatching { node?.recycle() }
    }

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    companion object {
        private const val TAG = "AdDetectorService"

        private const val INITIAL_SCAN_DELAY_MS = 300L
        private const val STEP_DELAY_MS = 400L
        private const val VERIFY_DELAY_MS = 800L
        private const val KILL_DELAY_MS = 500L
        private const val RELAUNCH_DELAY_MS = 600L
        private const val COOLDOWN_MS = 4_000L
        private const val INSTANT_KILL_DELAY_MS = 150L

        // Close-button detection thresholds.
        private const val MIN_CLOSE_BUTTON_PX = 20
        private const val MAX_CLOSE_BUTTON_PX = 200
        private const val TOP_RIGHT_X_THRESHOLD = 0.65f
        private const val TOP_RIGHT_Y_THRESHOLD = 0.25f

        /** Characters commonly used as close-button labels. */
        private val X_SYMBOLS = setOf("X", "x", "✕", "✖", "×", "╳")

        private const val NOTIFICATION_ID_SERVICE = 1001
        private const val NOTIFICATION_ID_AD_DETECTED = 1002

        /**
         * Known ad-SDK package prefixes. A window whose package starts with any of
         * these is treated as an ad interstitial.
         */
        val AD_SDK_PACKAGES = listOf(
            // Google
            "com.google.android.gms.ads",
            // Unity
            "com.unity3d.ads",
            "com.unity3d.services",
            // AppLovin / MAX
            "com.applovin",
            // ironSource / LevelPlay
            "com.ironsource",
            "com.ironsrc",
            // Vungle / Liftoff
            "com.vungle",
            // Chartboost
            "com.chartboost",
            // AdColony / Digital Turbine
            "com.adcolony",
            "com.digitalturbine",
            "com.fyber",
            // InMobi
            "com.inmobi",
            // Mintegral
            "com.mintegral",
            "com.mbridge",
            // Meta / Facebook Audience Network
            "com.facebook.ads",
            // Amazon
            "com.amazon.device.ads",
            // Pangle / ByteDance
            "com.bytedance.sdk",
            "com.pangle",
            // MoPub (legacy, now AppLovin)
            "com.mopub",
            // StartApp
            "com.startapp",
            // Tapjoy / Mistplay
            "com.tapjoy",
            // PubNative / Verve
            "net.pubnative",
            // Ogury
            "com.ogury",
            // SupersonicAds (ironSource legacy)
            "com.supersonic",
            // Smaato
            "com.smaato",
            // Yandex
            "com.yandex.mobile.ads",
            // Snap
            "com.snap.adkit",
            // Kidoz
            "com.kidoz",
            // Liftoff / Vungle
            "com.liftoff",
            // Bigo Ads
            "sg.bigo.ads"
        )

        /**
         * Keywords that, when present in a foreground Activity's class name, indicate
         * an ad interstitial regardless of the hosting package.
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
            "OfferWall",
            "AdViewActivity",
            "RichMediaActivity",
            "InterstitialActivity",
            "RewardActivity",
            "UnityAdsActivity",
            "ApplovinActivity",
            "MaxFullscreenAd",
            "IronSourceActivity",
            "VungleActivity",
            "ChartboostActivity",
            "InMobiActivity",
            "AdOverlay",
            "AdPopup",
            "PlayableAd",
            "EndCard",
            "SkippableActivity",
            "com.google.android.gms.ads"
        )

        /**
         * Text patterns found on close/dismiss/skip buttons in ads.
         * Matched case-insensitively against node text and content descriptions.
         */
        val CLOSE_BUTTON_TEXTS = listOf(
            "close",
            "skip",
            "skip ad",
            "skip ads",
            "skip video",
            "dismiss",
            "no thanks",
            "no, thanks",
            "not now",
            "continue",
            "x",
            "✕",
            "✖",
            "×",
            "╳",
            "close ad",
            "close this ad",
            "fermer",      // French
            "cerrar",      // Spanish
            "schließen",   // German
            "chiudi",      // Italian
            "fechar",      // Portuguese
            "閉じる",       // Japanese
            "关闭",         // Chinese
            "닫기",         // Korean
        )

        /**
         * Resource ID substrings that typically identify close/dismiss buttons.
         */
        val CLOSE_BUTTON_IDS = listOf(
            "close",
            "skip",
            "dismiss",
            "btn_close",
            "close_btn",
            "skip_btn",
            "btn_skip",
            "iv_close",
            "close_button",
            "skip_button",
            "ad_close",
            "interstitial_close",
            "reward_close",
            "btn_x",
            "closeButton",
            "skipButton",
            "close_image",
            "btnClose"
        )
    }
}
