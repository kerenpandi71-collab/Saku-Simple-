package com.saku.dex.data

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import android.content.pm.LauncherApps
import com.saku.dex.model.LauncherApp

object AppLauncher {
    fun launch(context: Context, app: LauncherApp) = launchInternal(context, app, false)

    fun launchInWindow(context: Context, app: LauncherApp) = launchInternal(context, app, true)

    private fun launchInternal(context: Context, app: LauncherApp, requestWindow: Boolean) {
        try {
            val component = ComponentName.unflattenFromString(app.componentName)
            if (component == null) {
                Toast.makeText(context, "Tujuan aplikasi tidak valid", Toast.LENGTH_SHORT).show()
                return
            }

            val options = if (requestWindow) {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                val width = (metrics.widthPixels * 0.84f).toInt().coerceAtLeast(1)
                val height = (metrics.heightPixels * 0.76f).toInt().coerceAtLeast(1)
                val left = (metrics.widthPixels - width) / 2
                val top = (metrics.heightPixels - height) / 2
                ActivityOptions.makeBasic()
                    .setLaunchBounds(Rect(left, top, left + width, top + height))
                    .toBundle()
            } else null

            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherApps.startMainActivity(
                component,
                android.os.Process.myUserHandle(),
                null,
                options
            )
            if (requestWindow) {
                Toast.makeText(
                    context,
                    "Ukuran jendela mengikuti dukungan freeform perangkat",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (_: SecurityException) {
            Toast.makeText(context, "Sistem menolak peluncuran aplikasi", Toast.LENGTH_SHORT).show()
        } catch (_: IllegalStateException) {
            Toast.makeText(context, "Aplikasi tidak dapat dibuka saat ini", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
        }
    }
}
