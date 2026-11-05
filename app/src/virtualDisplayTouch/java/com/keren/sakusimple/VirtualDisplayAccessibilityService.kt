package com.keren.sakusimple

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/**
 * Routes touch gestures to a selected display. Enable manually in Android Accessibility settings.
 * Android/OEM policy may reject gestures on untrusted or protected displays.
 */
class VirtualDisplayAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun dispatchToDisplay(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, moved: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Display-specific gestures require Android 11 or newer")
            return
        }
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            if (moved) lineTo(x2.toFloat(), y2.toFloat())
        }
        val duration = if (moved) 220L else 70L
        val builder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
        try {
            builder.setDisplayId(displayId)
            val accepted = dispatchGesture(builder.build(), null, Handler(Looper.getMainLooper()))
            if (!accepted) Log.w(TAG, "Gesture rejected for display=$displayId")
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to dispatch gesture to display=$displayId", error)
        }
    }

    companion object {
        private const val TAG = "SakuVirtualDisplay"
        @Volatile private var instance: VirtualDisplayAccessibilityService? = null

        fun sendGesture(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, moved: Boolean) {
            val service = instance
            if (service == null) {
                Log.w(TAG, "Accessibility service is not enabled")
                return
            }
            service.dispatchToDisplay(displayId, x1, y1, x2, y2, moved)
        }
    }
}
