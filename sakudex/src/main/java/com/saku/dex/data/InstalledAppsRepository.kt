package com.saku.dex.data

import android.content.Intent
import android.content.pm.PackageManager
import com.saku.dex.model.LauncherApp

/**
 * Mengambil aplikasi yang menyediakan launcher activity (MAIN + LAUNCHER).
 * Hasil diurutkan berdasarkan nama dan satu entri ditampilkan per package.
 */
class InstalledAppsRepository(
    private val packageManager: PackageManager
) {
    @Suppress("DEPRECATION")
    fun getLaunchableApps(): List<LauncherApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        return packageManager
            .queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName

                runCatching {
                    LauncherApp(
                        name = resolveInfo.loadLabel(packageManager)
                            ?.toString()
                            ?.takeIf { it.isNotBlank() }
                            ?: packageName,
                        packageName = packageName,
                        icon = resolveInfo.loadIcon(packageManager)
                    )
                }.getOrNull()
            }
            .distinctBy { it.packageName }
            .sortedBy { it.name.lowercase() }
    }
}
