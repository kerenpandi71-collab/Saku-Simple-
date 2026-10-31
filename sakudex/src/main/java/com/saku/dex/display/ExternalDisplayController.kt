package com.saku.dex.display

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.widget.TextView

/**
 * Detects presentation-capable secondary displays and hosts a basic desktop shell.
 * This does not mirror the phone screen; the external display gets its own Presentation.
 */
class ExternalDisplayController(context: Context) : DisplayManager.DisplayListener {
    private val appContext = context.applicationContext
    private val displayManager =
        appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var presentation: DesktopPresentation? = null

    fun start() {
        displayManager.registerDisplayListener(this, null)
        refreshPresentation()
    }

    fun stop() {
        displayManager.unregisterDisplayListener(this)
        presentation?.dismiss()
        presentation = null
    }

    override fun onDisplayAdded(displayId: Int) = refreshPresentation()
    override fun onDisplayChanged(displayId: Int) = refreshPresentation()

    override fun onDisplayRemoved(displayId: Int) {
        if (presentation?.display?.displayId == displayId) {
            presentation?.dismiss()
            presentation = null
        }
        refreshPresentation()
    }

    private fun refreshPresentation() {
        val target = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

        if (target == null) {
            presentation?.dismiss()
            presentation = null
            return
        }

        if (presentation?.display?.displayId == target.displayId) return

        presentation?.dismiss()
        presentation = runCatching {
            DesktopPresentation(appContext, target).also { it.show() }
        }.getOrNull()
    }
}

private class DesktopPresentation(
    context: Context,
    display: Display
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 24, 32))
            setPadding(48, 40, 48, 40)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(TextView(context).apply {
            text = "Saku DeX"
            textSize = 30f
            setTextColor(Color.WHITE)
        })

        root.addView(TextView(context).apply {
            text = "External desktop display detected"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, 16, 0, 0)
        })

        setContentView(root)
    }
}
