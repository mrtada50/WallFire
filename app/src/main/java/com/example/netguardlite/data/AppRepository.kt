package com.example.netguardlite.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * يجيب قائمة التطبيقات المثبتة على الجهاز اللي تمتلك صلاحية الإنترنت فقط،
 * لأن باقي التطبيقات مو مهمة لتطبيق فايروول.
 */
object AppRepository {

    fun getInternetCapableApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        return installedApps
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
                    icon = try {
                        pm.getApplicationIcon(appInfo.packageName)
                    } catch (e: Exception) {
                        null
                    },
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }
}
