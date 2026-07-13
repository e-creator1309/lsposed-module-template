package com.nova.devicespoof.data

/**
 * One target app that should receive the pasted build.prop / floating-features profile.
 *
 * @param packageName the app that should see the spoofed values.
 * @param enabled whether the hook should currently apply the profile to this app.
 * @param label human-readable app label, cached for display so the list doesn't
 *   need a PackageManager lookup on every render.
 */
data class SpoofRule(
    val packageName: String,
    var enabled: Boolean,
    var label: String
)
