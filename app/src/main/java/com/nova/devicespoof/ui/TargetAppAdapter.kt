package com.nova.devicespoof.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nova.devicespoof.R
import com.nova.devicespoof.data.InstalledAppInfo

/**
 * Checklist of installed apps shown in the "choose target apps" dialog.
 * [checkedPackages] is mutated in place; the caller reads it back after the dialog closes.
 */
class TargetAppAdapter(
    private val apps: List<InstalledAppInfo>,
    private val checkedPackages: MutableSet<String>
) : RecyclerView.Adapter<TargetAppAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_target_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount(): Int = apps.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon = itemView.findViewById<ImageView>(R.id.appIcon)
        private val label = itemView.findViewById<TextView>(R.id.appLabel)
        private val pkgText = itemView.findViewById<TextView>(R.id.appPackage)
        private val checkBox = itemView.findViewById<CheckBox>(R.id.appCheckBox)

        fun bind(app: InstalledAppInfo) {
            label.text = app.label
            pkgText.text = app.packageName
            icon.setImageDrawable(app.icon)

            checkBox.setOnCheckedChangeListener(null)
            checkBox.isChecked = checkedPackages.contains(app.packageName)
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) checkedPackages.add(app.packageName) else checkedPackages.remove(app.packageName)
            }
            itemView.setOnClickListener { checkBox.toggle() }
        }
    }
}
