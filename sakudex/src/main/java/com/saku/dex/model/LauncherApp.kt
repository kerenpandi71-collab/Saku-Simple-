package com.saku.dex.model

import android.graphics.Bitmap

/**
 * Data siap-render untuk satu launcher activity.
 * Bitmap ikon sudah dibuat di background saat repository memuat aplikasi.
 */
data class LauncherApp(
    val name: String,
    val packageName: String,
    val icon: Bitmap
)
