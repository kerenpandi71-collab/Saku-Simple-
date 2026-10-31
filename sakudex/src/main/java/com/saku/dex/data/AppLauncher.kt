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

/** Taskbar-inspired launch-bounds support. The OS/ROM decides whether freeform is honored. */
object AppLauncher {
    fun launch(context: Context, app: LauncherApp) = launchInternal(context, app, false)
    fun launchInWindow(context: Context, app: LauncherApp) = launchInternal(context, app, true)

    private fun launchInternal(context: Context, app: LauncherApp, requestWindow: Boolean) {
        try {
            val component = ComponentName.unflattenFromString(app.componentName) ?: run {
                Toast.makeText(context, "Tujuan aplikasi tidak valid", Toast.LENGTH_SHORT).show()
                return
            }
            val options = if (requestWindow) createWindowOptions(context) else null
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherApps.startMainActivity(component, android.os.Process.myUserHandle(), null, options)

            if (requestWindow) {
                val message = if (FreeformSupport.isSupported(context)) {
                    "Permintaan jendela dikirim ke sistem"
                } else {
                    "Freeform belum terdeteksi; sistem mungkin membuka aplikasi layar penuh"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        } catch (_: SecurityException) {
            Toast.makeText(context, "Sistem menolak peluncuran aplikasi", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createWindowOptions(context: Context): android.os.Bundle {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = (metrics.widthPixels * 0.82f).toInt().coerceAtLeast(1)
        val height = (metrics.heightPixels * 0.78f).toInt().coerceAtLeast(1)
        val left = (metrics.widthPixels - width) / 2
        val top = (metrics.heightPixels - height) / 2
        return ActivityOptions.makeBasic()
            .setLaunchBounds(Rect(left, top, left + width, top + height))
            .toBundle()
    }
}
