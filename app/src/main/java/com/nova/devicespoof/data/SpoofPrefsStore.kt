package com.nova.devicespoof.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the spoof profile (build.prop text + floating_features.xml text) and the list of
 * target apps configured from the companion app, so the LSPosed hook -- running inside each
 * target app's own process -- can read them via [de.robv.android.xposed.XSharedPreferences].
 *
 * Xposed's cross-process prefs read requires the prefs file, and every directory leading to
 * it, to be world-readable -- see [makeWorldReadable].
 */
object SpoofPrefsStore {

    const val PREFS_NAME = "spoof_config"
    const val KEY_BUILD_PROP = "build_prop_text"
    const val KEY_FLOATING_FEATURES = "floating_features_xml"
    const val KEY_RULES_JSON = "rules_json"
    private const val TAG = "NovaDeviceSpoofPrefs"

    data class Profile(
        val buildPropText: String,
        val floatingFeaturesXml: String
    )

    fun loadProfile(context: Context): Profile {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Profile(
            buildPropText = prefs.getString(KEY_BUILD_PROP, "") ?: "",
            floatingFeaturesXml = prefs.getString(KEY_FLOATING_FEATURES, "") ?: ""
        )
    }

    fun saveProfile(context: Context, profile: Profile) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_BUILD_PROP, profile.buildPropText)
            .putString(KEY_FLOATING_FEATURES, profile.floatingFeaturesXml)
            .commit()
        makeWorldReadable(context)
    }

    fun loadRules(context: Context): MutableList<SpoofRule> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_RULES_JSON, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(json)
            val rules = mutableListOf<SpoofRule>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                rules.add(
                    SpoofRule(
                        packageName = obj.getString("packageName"),
                        enabled = obj.optBoolean("enabled", true),
                        label = obj.optString("label", obj.getString("packageName"))
                    )
                )
            }
            rules
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to parse stored rules, starting fresh", t)
            mutableListOf()
        }
    }

    fun saveRules(context: Context, rules: List<SpoofRule>) {
        val array = JSONArray()
        for (rule in rules) {
            val obj = JSONObject()
            obj.put("packageName", rule.packageName)
            obj.put("enabled", rule.enabled)
            obj.put("label", rule.label)
            array.put(obj)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RULES_JSON, array.toString()).commit()
        makeWorldReadable(context)
    }

    private fun makeWorldReadable(context: Context) {
        val dataDir = File(context.applicationInfo.dataDir)
        val prefsDir = File(dataDir, "shared_prefs")
        val prefsFile = File(prefsDir, "$PREFS_NAME.xml")

        try {
            // Directories need the executable bit for "others" so the target app's process
            // can traverse into them; the file itself needs to be readable.
            dataDir.setExecutable(true, false)
            prefsDir.setExecutable(true, false)
            prefsDir.setReadable(true, false)
            prefsFile.setReadable(true, false)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to relax prefs file permissions", t)
        }

        // On stock/enforcing SELinux (every Samsung/OneUI device), the chmod above only
        // relaxes Unix permissions (DAC). It does NOT let another app's process read this
        // file, because SELinux (MAC) separately denies one app's process from opening
        // another app's app_data_file -- regardless of chmod bits -- due to per-app MLS
        // categories. Root is required to relabel past that; without it, XSharedPreferences
        // reads from the target process reliably come back null even though this file looks
        // "world readable" from adb/a root shell. This device has root (KernelSU/Magisk), so
        // ask for it and relabel; silently no-op if the companion app isn't granted root.
        relaxSelinuxWithRoot(dataDir, prefsDir, prefsFile)
    }

    private fun relaxSelinuxWithRoot(dataDir: File, prefsDir: File, prefsFile: File) {
        Log.i(TAG, "relaxSelinuxWithRoot: requesting su to relabel ${prefsFile.absolutePath}")
        try {
            // Echo the current context first (ls -Z) so failures are diagnosable from
            // logcat alone -- without this we can never tell "su denied" apart from
            // "su succeeded but chcon itself failed" apart from "never ran at all".
            val cmd = "chmod 711 '${dataDir.absolutePath}'; " +
                "chmod 755 '${prefsDir.absolutePath}'; " +
                "chmod 644 '${prefsFile.absolutePath}'; " +
                "chcon u:object_r:app_data_file:s0 '${prefsDir.absolutePath}'; " +
                "chcon u:object_r:app_data_file:s0 '${prefsFile.absolutePath}'; " +
                "ls -Z '${prefsFile.absolutePath}'"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Log.i(TAG, "relaxSelinuxWithRoot: su succeeded, exit=0, ls -Z output: $stdout")
            } else {
                Log.w(
                    TAG,
                    "relaxSelinuxWithRoot: su command exited $exitCode -- companion app may not " +
                        "be granted root, or chcon was rejected by policy. stdout=[$stdout] stderr=[$stderr]"
                )
            }
        } catch (t: Throwable) {
            // No root binary, root access wasn't granted to this app, or su denied/timed out.
            // The plain chmod above still applies; whether that's enough depends on the ROM's
            // SELinux policy (on enforcing SELinux it typically is not).
            Log.w(TAG, "relaxSelinuxWithRoot: could not run root command to relax SELinux label", t)
        }
    }
}
