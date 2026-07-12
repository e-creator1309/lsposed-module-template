package com.nova.sigspoofing.ui

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.recyclerview.widget.RecyclerView
import com.nova.sigspoofing.R
import com.nova.sigspoofing.data.SpoofRule

/**
 * Shows the configured spoof rules. [onToggle] and [onDelete] mutate the backing list
 * in [MainActivity] and persist it; this adapter only renders and forwards events.
 */
class RuleAdapter(
    private val pm: PackageManager,
    private val onToggle: (SpoofRule, Boolean) -> Unit,
    private val onDelete: (SpoofRule) -> Unit
) : RecyclerView.Adapter<RuleAdapter.RuleViewHolder>() {

    private val rules = mutableListOf<SpoofRule>()

    fun submitList(newRules: List<SpoofRule>) {
        rules.clear()
        rules.addAll(newRules)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_rule, parent, false)
        return RuleViewHolder(view)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
        holder.bind(rules[position])
    }

    override fun getItemCount(): Int = rules.size

    inner class RuleViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val icon = itemView.findViewById<android.widget.ImageView>(R.id.appIcon)
        private val label = itemView.findViewById<android.widget.TextView>(R.id.appLabel)
        private val pkgText = itemView.findViewById<android.widget.TextView>(R.id.appPackage)
        private val enabledSwitch =
            itemView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.ruleEnabledSwitch)
        private val deleteButton = itemView.findViewById<android.widget.ImageButton>(R.id.deleteRuleButton)

        fun bind(rule: SpoofRule) {
            label.text = rule.label
            pkgText.text = rule.packageName
            icon.setImageDrawable(
                try {
                    pm.getApplicationIcon(rule.packageName)
                } catch (t: Throwable) {
                    pm.defaultActivityIcon
                }
            )

            // Avoid firing a listener while we're just syncing the switch to current state.
            enabledSwitch.setOnCheckedChangeListener(null)
            enabledSwitch.isChecked = rule.enabled
            enabledSwitch.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, isChecked ->
                onToggle(rule, isChecked)
            })

            deleteButton.setOnClickListener { onDelete(rule) }
        }
    }
}
