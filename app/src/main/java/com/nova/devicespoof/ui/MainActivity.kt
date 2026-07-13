package com.nova.devicespoof.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nova.devicespoof.R
import com.nova.devicespoof.data.InstalledAppsRepository
import com.nova.devicespoof.data.SpoofPrefsStore
import com.nova.devicespoof.data.SpoofRule

/**
 * Companion app for the Nova Device Spoof LSPosed module.
 *
 * Paste a build.prop-style profile and/or a floating_feature.xml dump once, then pick which
 * installed apps should see it. The hook reads both through XSharedPreferences and applies
 * them only inside the process of the apps you enabled below -- the real device is untouched.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var buildPropInput: EditText
    private lateinit var floatingFeaturesInput: EditText
    private lateinit var targetAppsSummary: TextView
    private lateinit var adapter: TargetAppAdapter

    private val rules = mutableListOf<SpoofRule>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        buildPropInput = findViewById(R.id.buildPropInput)
        floatingFeaturesInput = findViewById(R.id.floatingFeaturesInput)
        targetAppsSummary = findViewById(R.id.targetAppsSummary)

        val profile = SpoofPrefsStore.loadProfile(this)
        buildPropInput.setText(profile.buildPropText)
        floatingFeaturesInput.setText(profile.floatingFeaturesXml)

        rules.addAll(SpoofPrefsStore.loadRules(this))
        refreshTargetAppsSummary()

        findViewById<View>(R.id.saveProfileButton).setOnClickListener { saveProfile() }
        findViewById<View>(R.id.chooseAppsButton).setOnClickListener { showChooseAppsDialog() }
    }

    private fun saveProfile() {
        SpoofPrefsStore.saveProfile(
            this,
            SpoofPrefsStore.Profile(
                buildPropText = buildPropInput.text.toString(),
                floatingFeaturesXml = floatingFeaturesInput.text.toString()
            )
        )
        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
    }

    private fun refreshTargetAppsSummary() {
        val enabledCount = rules.count { it.enabled }
        targetAppsSummary.text = if (enabledCount == 0) {
            getString(R.string.no_target_apps)
        } else {
            resources.getQuantityString(R.plurals.target_apps_count, enabledCount, enabledCount)
        }
    }

    private fun showChooseAppsDialog() {
        var launchableOnly = InstalledAppsRepository.listLaunchableApps(packageManager, packageName)
        if (launchableOnly.isEmpty()) {
            Toast.makeText(this, R.string.error_no_apps, Toast.LENGTH_SHORT).show()
            return
        }

        // Carry enabled state across a "show system apps" toggle, and across whatever was
        // saved previously -- so switching the checkbox never silently drops a selection.
        val enabledByPackage = rules.filter { it.enabled }.map { it.packageName }.toMutableSet()
        var currentApps = launchableOnly

        val dialogView = layoutInflater.inflate(R.layout.dialog_choose_apps, null)
        val showSystemCheckbox = dialogView.findViewById<CheckBox>(R.id.showSystemAppsCheckbox)
        val list = dialogView.findViewById<RecyclerView>(R.id.chooseAppsList)
        list.layoutManager = LinearLayoutManager(this)
        var chooseAdapter = TargetAppAdapter(currentApps, enabledByPackage)
        list.adapter = chooseAdapter

        showSystemCheckbox.setOnCheckedChangeListener { _, checked ->
            currentApps = if (checked) {
                // Includes apps with no launcher icon (system services, providers) --
                // this is what you need if the app you want to spoof never shows up
                // in the default list, e.g. a Samsung system component.
                InstalledAppsRepository.listAllApps(packageManager, packageName)
            } else {
                launchableOnly
            }
            chooseAdapter = TargetAppAdapter(currentApps, enabledByPackage)
            list.adapter = chooseAdapter
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_choose_apps)
            .setView(dialogView)
            .setPositiveButton(R.string.action_save) { _, _ ->
                // Merge instead of replacing wholesale: a package enabled while the system-apps
                // list was showing must stay enabled even though it's outside launchableOnly.
                val previousRules = rules.associateBy { it.packageName }
                rules.clear()
                val seen = mutableSetOf<String>()
                currentApps.forEach { app ->
                    rules.add(
                        SpoofRule(
                            packageName = app.packageName,
                            enabled = enabledByPackage.contains(app.packageName),
                            label = app.label
                        )
                    )
                    seen.add(app.packageName)
                }
                // Keep any previously-enabled package this session's list didn't include
                // (e.g. it was picked from the system-apps view before toggling back off).
                previousRules.values.forEach { rule ->
                    if (rule.enabled && rule.packageName !in seen) {
                        rules.add(rule)
                    }
                }
                SpoofPrefsStore.saveRules(this, rules)
                refreshTargetAppsSummary()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
