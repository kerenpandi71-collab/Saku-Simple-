package com.saku.dex.model

import android.graphics.Bitmap
import android.os.Process
import android.os.UserHandle

/** One launcher activity in one Android user/profile. */
data class LauncherApp(
    val name: String,
    val packageName: String,
    val componentName: String,
    val icon: Bitmap,
    val userHandle: UserHandle = Process.myUserHandle(),
    val isClone: Boolean = false
) {
    /** Distinguishes the original app from the same package installed in a clone profile. */
    val uniqueId: String
        get() = "${userHandle.hashCode()}:$componentName"
}
