package com.saku.dex.data

import android.app.ActivityOptions
import android.content.Context
import android.content.ComponentName
import android.graphics.Rect
import android.content.pm.LauncherApps
import android.widget.Toast
import com.saku.dex.model.LauncherApp

object AppLauncher {
    fun launch(context: Context, app: LauncherApp) {
        launchInternal(context, app, null)
    }

    /**
     * Requests a centered, bounded launch. Android/ROM decides whether the task
     * is actually hosted in freeform; launch bounds alone cannot enable that mode.
     */
    fun launchInWindow(context: Context, app: LauncherApp) {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val windowWidth = (width * 0.84f).toInt().coerceAtLeast(320).coerceAtMost(width)
        val windowHeight = (height * 0.76f).toInt().coerceAtLeast(320).coerceAtMost(height)
        val left = ((width - windowWidth) / 2).coerceAtLeast(0)
        val top = ((height - windowHeight) / 2).coerceAtLeast(0)
        val bounds = Rect(left, top, left + windowWidth, top + windowHeight)
        val options = ActivityOptions.makeBasic().setLaunchBounds(bounds).toBundle()
        launchInternal(context, app, options)
        Toast.makeText(
            context,
            "Permintaan jendela dikirim. Mode freeform bergantung dukungan ROM.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun launchInternal(context: Context, app: LauncherApp, options: android.os.Bundle?) {
        try {
            val component = ComponentName.unflattenFromString(app.componentName)
            if (component == null) {
                Toast.makeText(context, "Tujuan aplikasi tidak valid", Toast.LENGTH_SHORT).show()
                return
            }
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherApps.startMainActivity(
                component,
                android.os.Process.myUserHandle(),
                null,
                options
            )
        } catch (_: SecurityException) {
            Toast.makeText(context, "Sistem menolak peluncuran aplikasi", Toast.LENGTH_SHORT).show()
        } catch (_: IllegalStateException) {
            Toast.makeText(context, "Aplikasi tidak dapat dibuka saat ini", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
        }
    }
}
