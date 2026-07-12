package com.nova.sigspoofing.data

/**
 * One configured spoof rule created from the companion app UI.
 *
 * @param packageName the target app that should receive [certBase64] as its signature.
 * @param certBase64 Base64-encoded DER certificate to report for [packageName].
 * @param enabled whether the hook should currently apply this rule.
 * @param label human-readable app label, cached for display so the list doesn't
 *   need a PackageManager lookup on every render.
 */
data class SpoofRule(
    val packageName: String,
    var certBase64: String,
    var enabled: Boolean,
    var label: String
)
