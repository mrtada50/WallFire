package com.example.netguardlite.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * يجيب قائمة التطبيقات المثبتة اللي تمتلك صلاحية الإنترنت، ويحتفظ بكاش
 * خفيف (بدون أيقونات) بالـ SharedPreferences عشان العرض يصير فوري
 * بالمرة الجاية بدل ما ينتظر مسح كامل للنظام.
 */
object AppRepository {

    private const val PREFS_NAME = "netguard_app_cache"
    private const val KEY_CACHE = "cached_apps"

    /** قراءة سريعة من الكاش المحفوظ (بدون أي استدعاء لـ PackageManager) */
    fun loadCachedApps(context: Context): List<AppInfo> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CACHE, null) ?: return emptyList()

        return raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size == 4) {
                    val uid = parts[2].toIntOrNull()
                    if (uid != null) {
                        AppInfo(
                            packageName = parts[0],
                            appName = parts[1],
                            uid = uid,
                            isSystemApp = parts[3] == "1"
                        )
                    } else null
                } else null
            }
            .sortedBy { it.appName.lowercase() }
            .toList()
    }

    private fun saveCache(context: Context, apps: List<AppInfo>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = apps.joinToString("\n") { app ->
            val safeName = app.appName.replace("\t", " ").replace("\n", " ")
            "${app.packageName}\t$safeName\t${app.uid}\t${if (app.isSystemApp) "1" else "0"}"
        }
        prefs.edit().putString(KEY_CACHE, raw).apply()
    }

    /**
     * مسح فعلي وكامل عبر PackageManager. ثقيل نسبياً، لازم يستدعى من
     * خيط خلفية (Dispatchers.IO) مو الـ Main thread.
     */
    fun getInternetCapableApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val result = installedApps
            .filter { appInfo ->
                pm.checkPermission(
                    android.Manifest.permission.INTERNET,
                    appInfo.packageName
                ) == PackageManager.PERMISSION_GRANTED
            }
            .map { appInfo ->
                AppInfo(
                    packageName = appInfo.packageName,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    uid = appInfo.uid,
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }

        saveCache(context, result)
        return result
    }
}
