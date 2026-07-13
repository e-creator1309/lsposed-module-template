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
            .map { pkg ->
                val appInfo = pm.getApplicationInfo(pkg, 0)
                InstalledAppInfo(
                    packageName = pkg,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    icon = try { pm.getApplicationIcon(pkg) } catch (t: Throwable) { null }
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}
