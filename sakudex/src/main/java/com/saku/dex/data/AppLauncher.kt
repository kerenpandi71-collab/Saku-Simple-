package com.saku.dex.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.widget.Toast
import com.saku.dex.model.LauncherApp

/** Standard Android app launcher. Legacy launch-bounds window mode was removed. */
object AppLauncher {
    fun launch(context: Context, app: LauncherApp) {
        try {
            val component = ComponentName.unflattenFromString(app.componentName) ?: run {
                Toast.makeText(context, "Tujuan aplikasi tidak valid", Toast.LENGTH_SHORT).show()
                return
            }
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherApps.startMainActivity(
                component,
                android.os.Process.myUserHandle(),
                null,
                null
            )
        } catch (_: SecurityException) {
            Toast.makeText(context, "Sistem menolak peluncuran aplikasi", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
        }
    }
}
