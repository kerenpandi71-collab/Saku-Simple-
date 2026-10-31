package com.saku.dex.model

import android.graphics.Bitmap

/** Data siap-render untuk satu launcher activity, bukan hanya satu paket aplikasi. */
data class LauncherApp(
    val name: String,
    val packageName: String,
    val componentName: String,
    val icon: Bitmap
)
