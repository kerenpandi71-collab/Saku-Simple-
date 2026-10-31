package com.saku.dex.model

import android.graphics.drawable.Drawable

/**
 * Informasi satu aplikasi yang mempunyai activity launcher.
 * Drawable dipakai langsung oleh UI untuk menampilkan ikon aplikasi.
 */
data class LauncherApp(
    val name: String,
    val packageName: String,
    val icon: Drawable
)
