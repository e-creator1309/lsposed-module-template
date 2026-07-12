package com.nova.sigspoofing.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists spoof rules configured from the companion app so the LSPosed hook
 * (running as a different UID inside system_server) can read them via
 * [de.robv.android.xposed.XSharedPreferences].
 *
 * Xposed's cross-process prefs read requires the prefs file, and every
 * directory leading to it, to be world-readable -- see [makeWorldReadable].
 */
object SpoofPrefsStore {

    const val PREFS_NAME = "spoof_config"
    const val KEY_RULES_JSON = "rules_json"
    private const val TAG = "NovaSpoofPrefs"

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
                        certBase64 = obj.getString("certBase64"),
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
            obj.put("certBase64", rule.certBase64)
            obj.put("enabled", rule.enabled)
            obj.put("label", rule.label)
            array.put(obj)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RULES_JSON, array.toString()).commit()
        makeWorldReadable(context)
    }

    private fun makeWorldReadable(context: Context) {
        try {
            val dataDir = File(context.applicationInfo.dataDir)
            val prefsDir = File(dataDir, "shared_prefs")
            val prefsFile = File(prefsDir, "$PREFS_NAME.xml")

            // Directories need the executable bit for "others" so system_server
            // can traverse into them; the file itself needs to be readable.
            dataDir.setExecutable(true, false)
            prefsDir.setExecutable(true, false)
            prefsDir.setReadable(true, false)
            prefsFile.setReadable(true, false)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to relax prefs file permissions", t)
        }
    }
}
