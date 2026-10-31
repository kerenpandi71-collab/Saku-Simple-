package com.saku.dex.data

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** Capability check adapted from Saku-Taskbar. Does not alter system settings. */
object FreeformSupport {
    fun isSupported(context: Context): Boolean {
        val feature = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT
        )
        val enabled = runCatching {
            Settings.Global.getInt(context.contentResolver, "enable_freeform_support", 0) != 0
        }.getOrDefault(false)
        return feature && enabled
    }
}
