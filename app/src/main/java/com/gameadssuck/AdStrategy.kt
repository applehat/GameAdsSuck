package com.gameadssuck

/**
 * Per-app strategy for how the ad detector should handle detected advertisements.
 *
 * Each watched app can be configured with a different strategy so the user can
 * pick the fastest / most reliable approach for each game.
 */
enum class AdStrategy(val key: String, val labelResId: Int, val descResId: Int) {

    /**
     * Full cascading sequence: scan for close button → gesture tap → BACK ×2
     * → re-scan → kill + relaunch → HOME + relaunch.
     */
    AUTO("auto", R.string.strategy_auto, R.string.strategy_auto_desc),

    /**
     * Immediately kill the game process and relaunch it.
     * Fastest option — skips all close-button attempts.
     */
    INSTANT_KILL("instant_kill", R.string.strategy_instant_kill, R.string.strategy_instant_kill_desc),

    /**
     * Try to find and click close/skip buttons in the accessibility tree,
     * then fall back to BACK presses. Never kills the app.
     */
    CLOSE_AND_BACK("close_and_back", R.string.strategy_close_and_back, R.string.strategy_close_and_back_desc),

    /**
     * Only press BACK to dismiss the ad. Simple and safe,
     * but doesn't work on all ad networks.
     */
    BACK_ONLY("back_only", R.string.strategy_back_only, R.string.strategy_back_only_desc);

    companion object {
        /** Resolves a strategy from its stored key, defaulting to [AUTO]. */
        fun fromKey(key: String?): AdStrategy =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}
