package com.saku.dex.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.widget.Toast
import com.saku.dex.model.LauncherApp

/** Launches the selected app in the exact Android profile it belongs to. */
object AppLauncher {
    fun launch(context: Context, app: LauncherApp) {
        try {
            val component = ComponentName.unflattenFromString(app.componentName) ?: run {
                Toast.makeText(context, "Tujuan aplikasi tidak valid", Toast.LENGTH_SHORT).show()
                return
            }
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherApps.startMainActivity(component, app.userHandle, null, null)
        } catch (_: SecurityException) {
            Toast.makeText(
                context,
                if (app.isClone) "Android menolak akses ke profil aplikasi klon" else "Sistem menolak peluncuran aplikasi",
                Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
        }
    }
}
