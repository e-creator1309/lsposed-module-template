package com.nova.sigspoofing.ui

import android.app.AlertDialog
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.nova.sigspoofing.R
import com.nova.sigspoofing.data.InstalledAppInfo
import com.nova.sigspoofing.data.InstalledAppsRepository
import com.nova.sigspoofing.data.SpoofPrefsStore
import com.nova.sigspoofing.data.SpoofRule

/**
 * Companion app for the Nova Sig Spoof LSPosed module. Lets you point-and-click
 * assign a signature to any installed app instead of hand-editing manifests.
 * Rules are written through [SpoofPrefsStore]; the module hook reads them at
 * runtime via XSharedPreferences.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var adapter: RuleAdapter
    private lateinit var emptyStateText: TextView
    private val rules = mutableListOf<SpoofRule>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        emptyStateText = findViewById(R.id.emptyStateText)
        val ruleList = findViewById<RecyclerView>(R.id.ruleList)
        val addRuleFab = findViewById<FloatingActionButton>(R.id.addRuleFab)

        adapter = RuleAdapter(
            pm = packageManager,
            onToggle = { rule, enabled ->
                rule.enabled = enabled
                persistRules()
            },
            onDelete = { rule ->
                rules.remove(rule)
                persistRules()
                refreshList()
            }
        )
        ruleList.layoutManager = LinearLayoutManager(this)
        ruleList.adapter = adapter

        addRuleFab.setOnClickListener { showAddRuleDialog() }

        rules.addAll(SpoofPrefsStore.loadRules(this))
        refreshList()
    }

    private fun refreshList() {
        adapter.submitList(rules)
        emptyStateText.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun persistRules() {
        SpoofPrefsStore.saveRules(this, rules)
    }

    private fun showAddRuleDialog() {
        val installedApps = InstalledAppsRepository.listLaunchableApps(packageManager, packageName)
        if (installedApps.isEmpty()) {
            Toast.makeText(this, R.string.error_no_apps, Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_add_rule, null)
        val targetAppSpinner = dialogView.findViewById<Spinner>(R.id.targetAppSpinner)
        val sourceAppSpinner = dialogView.findViewById<Spinner>(R.id.sourceAppSpinner)
        val certSourceGroup = dialogView.findViewById<RadioGroup>(R.id.certSourceGroup)
        val manualCertInput = dialogView.findViewById<EditText>(R.id.manualCertInput)

        val appLabels = installedApps.map { "${it.label}  (${it.packageName})" }
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, appLabels)
        targetAppSpinner.adapter = spinnerAdapter
        sourceAppSpinner.adapter = spinnerAdapter

        certSourceGroup.setOnCheckedChangeListener { _, checkedId ->
            val manual = checkedId == R.id.pasteManualOption
            manualCertInput.visibility = if (manual) View.VISIBLE else View.GONE
            sourceAppSpinner.visibility = if (manual) View.GONE else View.VISIBLE
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_add_rule)
            .setView(dialogView)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (tryAddRule(installedApps, targetAppSpinner, sourceAppSpinner, certSourceGroup, manualCertInput)) {
                            dismiss()
                        }
                    }
                }
            }
            .show()
    }

    private fun tryAddRule(
        installedApps: List<InstalledAppInfo>,
        targetAppSpinner: Spinner,
        sourceAppSpinner: Spinner,
        certSourceGroup: RadioGroup,
        manualCertInput: EditText
    ): Boolean {
        val target = installedApps[targetAppSpinner.selectedItemPosition]
        val useManual = certSourceGroup.checkedRadioButtonId == R.id.pasteManualOption

        val certBase64: String? = if (useManual) {
            val raw = manualCertInput.text.toString().trim()
            if (isValidBase64(raw)) raw else null
        } else {
            val source = installedApps[sourceAppSpinner.selectedItemPosition]
            InstalledAppsRepository.readRealCertificateBase64(packageManager, source.packageName)
        }

        if (certBase64 == null) {
            Toast.makeText(this, R.string.error_invalid_cert, Toast.LENGTH_SHORT).show()
            return false
        }

        rules.removeAll { it.packageName == target.packageName }
        rules.add(SpoofRule(target.packageName, certBase64, enabled = true, label = target.label))
        persistRules()
        refreshList()
        return true
    }

    private fun isValidBase64(value: String): Boolean {
        if (value.isEmpty()) return false
        return try {
            Base64.decode(value, Base64.DEFAULT)
            true
        } catch (t: IllegalArgumentException) {
            false
        }
    }
}
