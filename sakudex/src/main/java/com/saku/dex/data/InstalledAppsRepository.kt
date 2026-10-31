package com.saku.dex.data

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.core.graphics.drawable.toBitmap
import com.saku.dex.model.LauncherApp

/**
 * Mengambil semua launcher activity untuk profil Android saat ini.
 * Pemanggil wajib menjalankannya di dispatcher background.
 */
class InstalledAppsRepository(context: Context) {
    private val launcherApps =
        context.applicationContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

    fun getLaunchableApps(): List<LauncherApp> {
        return launcherApps.getActivityList(null, Process.myUserHandle())
            .mapNotNull { activity ->
                runCatching {
                    val component = activity.componentName
                    val packageName = component.packageName
                    LauncherApp(
                        name = activity.label?.toString()?.takeIf { it.isNotBlank() } ?: packageName,
                        packageName = packageName,
                        componentName = component.flattenToString(),
                        icon = activity.getIcon(0).toLauncherBitmap()
                    )
                }.getOrNull()
            }
            // Jangan deduplikasi berdasarkan package: satu paket dapat memiliki beberapa launcher activity.
            .distinctBy { it.componentName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun Drawable.toLauncherBitmap(): Bitmap =
        toBitmap(width = ICON_SIZE_PX, height = ICON_SIZE_PX, config = Bitmap.Config.ARGB_8888)

    private companion object {
        const val ICON_SIZE_PX = 96
    }
}
