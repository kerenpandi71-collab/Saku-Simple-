package com.saku.dex.data

import android.content.Context
import android.content.pm.LauncherApps
import android.content.ComponentName
import android.widget.Toast
import com.saku.dex.model.LauncherApp

object AppLauncher {
    fun launch(context: Context, app: LauncherApp) {
        try {
            val component = ComponentName.unflattenFromString(app.componentName)
            if (component == null) {
                Toast.makeText(context, "Tujuan aplikasi tidak valid", Toast.LENGTH_SHORT).show()
                return
            }
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherApps.startMainActivity(component, android.os.Process.myUserHandle(), null, null)
        } catch (_: SecurityException) {
            Toast.makeText(context, "Sistem menolak peluncuran aplikasi", Toast.LENGTH_SHORT).show()
        } catch (_: IllegalStateException) {
            Toast.makeText(context, "Aplikasi tidak dapat dibuka saat ini", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
        }
    }
}
