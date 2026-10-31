package com.saku.dex.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast

object AppLauncher {
    fun launch(context: Context, packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            val activity = context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .firstOrNull()?.activityInfo

            if (activity == null) {
                Toast.makeText(context, "Aplikasi tidak menyediakan launcher activity", Toast.LENGTH_SHORT).show()
                return
            }
            intent.component = ComponentName(activity.packageName, activity.name)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, "Sistem menolak peluncuran aplikasi", Toast.LENGTH_SHORT).show()
        }
    }
}
