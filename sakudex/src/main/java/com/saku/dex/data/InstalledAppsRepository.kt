package com.saku.dex.data

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import androidx.core.graphics.drawable.toBitmap
import com.saku.dex.model.LauncherApp

/**
 * Reads launcher activities from every Android profile exposed to this app.
 * This includes manufacturer dual-app/clone profiles when Android exposes them
 * through LauncherApps.getProfiles(). Call from a background dispatcher.
 */
class InstalledAppsRepository(context: Context) {
    private val launcherApps =
        context.applicationContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

    fun getLaunchableApps(): List<LauncherApp> {
        val currentUser = Process.myUserHandle()
        val profiles = runCatching { launcherApps.profiles }
            .getOrDefault(listOf(currentUser))
            .ifEmpty { listOf(currentUser) }
            .distinct()
            .sortedBy { if (it == currentUser) 0 else 1 }

        return profiles.flatMap { profile ->
            runCatching { launcherApps.getActivityList(null, profile) }
                .getOrDefault(emptyList())
                .mapNotNull { activity ->
                    runCatching {
                        val component = activity.componentName
                        val packageName = component.packageName
                        LauncherApp(
                            name = activity.label?.toString()?.takeIf { it.isNotBlank() } ?: packageName,
                            packageName = packageName,
                            componentName = component.flattenToString(),
                            icon = activity.getIcon(0).toLauncherBitmap(),
                            userHandle = profile,
                            isClone = profile != currentUser
                        )
                    }.getOrNull()
                }
        }
            // A package can expose multiple launcher activities and the same app can exist in multiple profiles.
            .distinctBy { it.uniqueId }
            .sortedWith(compareBy<LauncherApp, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.userHandle.hashCode() })
    }

    private fun Drawable.toLauncherBitmap(): Bitmap =
        toBitmap(width = ICON_SIZE_PX, height = ICON_SIZE_PX, config = Bitmap.Config.ARGB_8888)

    private companion object {
        const val ICON_SIZE_PX = 96
    }
}
