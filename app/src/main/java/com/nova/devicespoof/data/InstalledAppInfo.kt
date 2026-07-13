package com.nova.devicespoof.data

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** A launchable app discovered on the device, used to populate the target-app picker. */
data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

object InstalledAppsRepository {

    /** Every launchable app on the device (has a Home-screen entry point), sorted by label. */
    fun listLaunchableApps(pm: PackageManager, excludePackage: String? = null): List<InstalledAppInfo> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)

        return resolveInfos
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != excludePackage }
            .map { pkg -> toAppInfo(pm, pkg) }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Every installed app, including apps with no Home-screen icon (system services,
     * providers, background components). Use this when the app you want to spoof --
     * e.g. a Samsung system service or a provider-only package -- never shows up in the
     * launcher list above. Slower and much longer than [listLaunchableApps], so it's only
     * queried when the user explicitly asks to see system apps too.
     */
    fun listAllApps(pm: PackageManager, excludePackage: String? = null): List<InstalledAppInfo> {
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .map { it.packageName }
            .distinct()
            .filter { it != excludePackage }
            .map { pkg -> toAppInfo(pm, pkg) }
            .sortedBy { it.label.lowercase() }
    }

    private fun toAppInfo(pm: PackageManager, pkg: String): InstalledAppInfo {
        val appInfo = pm.getApplicationInfo(pkg, 0)
        return InstalledAppInfo(
            packageName = pkg,
            label = pm.getApplicationLabel(appInfo).toString(),
            icon = try { pm.getApplicationIcon(pkg) } catch (t: Throwable) { null }
        )
    }
}
