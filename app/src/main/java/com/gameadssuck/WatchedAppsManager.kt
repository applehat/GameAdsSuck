package com.gameadssuck

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages the list of watched app package names using SharedPreferences.
 */
class WatchedAppsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns an immutable copy of the currently watched package names. */
    fun getWatchedPackages(): Set<String> =
        prefs.getStringSet(KEY_WATCHED_PACKAGES, emptySet())?.toSet() ?: emptySet()

    /** Adds a package to the watch list. */
    fun addWatchedPackage(packageName: String) {
        val packages = getWatchedPackages().toMutableSet()
        packages.add(packageName)
        prefs.edit().putStringSet(KEY_WATCHED_PACKAGES, packages).apply()
    }

    /** Removes a package from the watch list. */
    fun removeWatchedPackage(packageName: String) {
        val packages = getWatchedPackages().toMutableSet()
        packages.remove(packageName)
        prefs.edit().putStringSet(KEY_WATCHED_PACKAGES, packages).apply()
    }

    /** Returns true if the given package is currently being watched. */
    fun isWatched(packageName: String): Boolean =
        getWatchedPackages().contains(packageName)

    companion object {
        const val PREFS_NAME = "game_ads_suck_prefs"
        private const val KEY_WATCHED_PACKAGES = "watched_packages"
    }
}
