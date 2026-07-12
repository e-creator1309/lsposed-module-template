package com.nova.sigspoofing.data

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Base64

/** A launchable app discovered on the device, used to populate pickers in the UI. */
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

    /**
     * Reads [packageName]'s real signing certificate straight from the system and
     * returns it Base64-encoded, ready to be pasted into another app's spoof rule.
     */
    fun readRealCertificateBase64(pm: PackageManager, packageName: String): String? {
        return try {
            val pi = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = pi.signingInfo ?: return null
            val signatures = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            val signature = signatures?.firstOrNull() ?: return null
            Base64.encodeToString(signature.toByteArray(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            null
        }
    }
}
