package com.awd.teledrive.core.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object VersionUtils {
    fun getVersionName(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    fun getVersionCode(context: Context): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Leading numeric segments of a version string: "2.0.259-rc1" -> [2, 0, 259].
     * Returns an empty list when the version has no numeric core at all
     * (e.g. "Unknown" or "").
     */
    fun numericCore(version: String): List<Int> {
        val core = mutableListOf<Int>()
        for (segment in version.split(".", "-", "_")) {
            core.add(segment.toIntOrNull() ?: break)
        }
        return core
    }

    /**
     * True when the version carries a prerelease suffix after its numeric
     * core, e.g. "2.0.259-rc1" or "2.0.258-beta".
     */
    fun hasPrereleaseSuffix(version: String): Boolean {
        return version.split(".", "-", "_").any { it.isNotEmpty() && it.toIntOrNull() == null }
    }

    /**
     * True when [latest] is a newer release than [installed].
     *
     * Numeric cores are compared first; when they are equal, a stable
     * build outranks a prerelease of the same version (2.0.259 is newer
     * than 2.0.259-rc1), so a prerelease suffix can never make a version
     * look newer than the stable build it belongs to (L2).
     *
     * Returns false when either side has no numeric core (e.g. the
     * installed version is "Unknown"): an unknown version never forces
     * an update.
     */
    fun isNewerVersion(installed: String, latest: String): Boolean {
        if (latest.isEmpty() || installed == latest) return false
        val installedCore = numericCore(installed)
        val latestCore = numericCore(latest)
        if (installedCore.isEmpty() || latestCore.isEmpty()) return false
        val maxLen = maxOf(installedCore.size, latestCore.size)
        for (i in 0 until maxLen) {
            val inst = installedCore.getOrElse(i) { 0 }
            val lat = latestCore.getOrElse(i) { 0 }
            if (lat > inst) return true
            if (lat < inst) return false
        }
        // Same numeric core: stable beats its own prerelease.
        return !hasPrereleaseSuffix(latest) && hasPrereleaseSuffix(installed)
    }
}
