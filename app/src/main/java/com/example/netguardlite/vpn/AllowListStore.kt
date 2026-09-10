package com.example.netguardlite.vpn

import android.content.Context

/**
 * تخزين بسيط: قائمة التطبيقات "المسموحة" (Whitelist) + حالة تفعيل الحماية.
 * أي تطبيق مو موجود بالقائمة = محجوب افتراضياً.
 */
object AllowListStore {

    private const val PREFS_NAME = "netguard_prefs"
    private const val KEY_ALLOWED = "allowed_packages"
    private const val KEY_ENABLED = "protection_enabled"
    private const val KEY_NETWORK_SCOPE = "network_scope"

    const val SCOPE_BOTH = "both"
    const val SCOPE_WIFI_ONLY = "wifi"
    const val SCOPE_MOBILE_ONLY = "mobile"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAllowedPackages(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_ALLOWED, emptySet()) ?: emptySet()
    }

    fun setAllowed(context: Context, packageName: String, allowed: Boolean) {
        val current = getAllowedPackages(context).toMutableSet()
        if (allowed) current.add(packageName) else current.remove(packageName)
        prefs(context).edit().putStringSet(KEY_ALLOWED, current).apply()
    }

    fun isProtectionEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setProtectionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getNetworkScope(context: Context): String {
        return prefs(context).getString(KEY_NETWORK_SCOPE, SCOPE_BOTH) ?: SCOPE_BOTH
    }

    fun setNetworkScope(context: Context, scope: String) {
        prefs(context).edit().putString(KEY_NETWORK_SCOPE, scope).apply()
    }
}
