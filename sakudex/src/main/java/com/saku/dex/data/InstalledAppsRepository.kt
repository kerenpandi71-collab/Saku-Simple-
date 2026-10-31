package com.saku.dex.data

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import com.saku.dex.model.LauncherApp

/**
 * Memuat label dan ikon launcher. Panggil dari Dispatchers.IO.
 * Ikon diperkecil sebelum disimpan agar penggunaan memori lebih terkendali.
 */
class InstalledAppsRepository(
    private val packageManager: PackageManager
) {
    @Suppress("DEPRECATION")
    fun getLaunchableApps(): List<LauncherApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        return packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName

                runCatching {
                    val drawable = resolveInfo.loadIcon(packageManager)
                    LauncherApp(
                        name = resolveInfo.loadLabel(packageManager)
                            ?.toString()?.takeIf { it.isNotBlank() } ?: packageName,
                        packageName = packageName,
                        icon = drawable.toLauncherBitmap()
                    )
                }.getOrNull()
            }
            .distinctBy { it.packageName }
            .sortedBy { it.name.lowercase() }
    }

    private fun Drawable.toLauncherBitmap(): Bitmap =
        toBitmap(width = ICON_SIZE_PX, height = ICON_SIZE_PX, config = Bitmap.Config.ARGB_8888)

    private companion object {
        const val ICON_SIZE_PX = 96
    }
}
