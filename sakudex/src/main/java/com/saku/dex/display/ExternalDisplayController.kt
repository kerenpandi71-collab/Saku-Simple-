package com.saku.dex.display

import android.app.Presentation
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.saku.dex.data.InstalledAppsRepository
import com.saku.dex.model.LauncherApp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** Hosts an independent DeX desktop on a real secondary display; it does not mirror the phone. */
class ExternalDisplayController(context: Context) : DisplayManager.DisplayListener {
    private val appContext = context.applicationContext
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var presentation: DesktopPresentation? = null
    private var registered = false

    fun start() {
        if (!registered) {
            displayManager.registerDisplayListener(this, Handler(Looper.getMainLooper()))
            registered = true
        }
        refreshPresentation()
    }

    fun stop() {
        if (registered) {
            displayManager.unregisterDisplayListener(this)
            registered = false
        }
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
        val target = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (target == null) {
            presentation?.dismiss()
            presentation = null
            return
        }
        if (presentation?.display?.displayId == target.displayId) return
        presentation?.dismiss()
        presentation = runCatching { DesktopPresentation(appContext, target).also { it.show() } }.getOrNull()
    }
}

private class DesktopPresentation(context: Context, display: Display) : Presentation(context, display) {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val apps = mutableListOf<LauncherApp>()
    private lateinit var appGrid: GridLayout
    private lateinit var searchField: EditText
    private lateinit var clockView: TextView

    private val clockUpdater = object : Runnable {
        override fun run() {
            if (::clockView.isInitialized && isShowing) {
                clockView.text = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("EEE, d MMM  •  HH:mm", Locale.getDefault())
                )
                handler.postDelayed(this, 30_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackground(GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(39, 58, 79), Color.rgb(13, 22, 32))
            ))
            setPadding(dp(28), dp(20), dp(28), dp(12))
        }

        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(TextView(context).apply {
            text = "Saku DeX"
            textSize = 25f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        heading.addView(TextView(context).apply {
            text = "EXTERNAL DESKTOP"
            textSize = 10f
            setTextColor(Color.rgb(183, 202, 220))
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(Color.argb(42, 255, 255, 255), dp(12))
        })
        root.addView(heading)

        searchField = EditText(context).apply {
            hint = "Search apps"
            setHintTextColor(Color.rgb(168, 185, 202))
            setTextColor(Color.WHITE)
            textSize = 14f
            singleLine = true
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(Color.rgb(31, 46, 62), dp(14), Color.rgb(66, 87, 108))
        }
        val searchParams = LinearLayout.LayoutParams(dp(420), dp(48))
        searchParams.topMargin = dp(14)
        searchParams.bottomMargin = dp(12)
        root.addView(searchField, searchParams)

        val scroll = ScrollView(context).apply { isFillViewport = true; clipToPadding = false }
        appGrid = GridLayout(context).apply {
            columnCount = 7
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        scroll.addView(appGrid, ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val taskbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(Color.argb(225, 14, 23, 32), dp(15))
        }
        taskbar.addView(TextView(context).apply {
            text = "▦  Saku"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(15), dp(9), dp(15), dp(9))
            background = rounded(Color.rgb(54, 83, 111), dp(11))
            setOnClickListener { searchField.requestFocus() }
        })
        taskbar.addView(TextView(context).apply {
            text = "Desktop"
            textSize = 13f
            setTextColor(Color.rgb(216, 226, 235))
            setPadding(dp(16), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        clockView = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        taskbar.addView(clockView)
        val taskbarParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        taskbarParams.topMargin = dp(10)
        root.addView(taskbar, taskbarParams)

        setContentView(root)
        clockUpdater.run()

        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderApps(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        worker.execute {
            val loaded = runCatching { InstalledAppsRepository(context).getLaunchableApps() }
                .getOrDefault(emptyList())
            handler.post {
                if (isShowing) {
                    apps.clear()
                    apps.addAll(loaded)
                    renderApps(searchField.text?.toString().orEmpty())
                }
            }
        }
    }

    private fun renderApps(query: String) {
        appGrid.removeAllViews()
        val matches = apps.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
        matches.forEach { app ->
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = rounded(Color.argb(24, 255, 255, 255), dp(14))
                isClickable = true
                isFocusable = true
                setOnClickListener { launch(app) }
            }
            tile.addView(ImageView(context).apply {
                setImageBitmap(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            tile.addView(TextView(context).apply {
                text = app.name
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.WHITE)
                setPadding(0, dp(6), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
            val params = GridLayout.LayoutParams().apply {
                width = dp(96)
                height = dp(104)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            appGrid.addView(tile, params)
        }
    }

    private fun launch(app: LauncherApp) {
        runCatching {
            val component = ComponentName.unflattenFromString(app.componentName) ?: return
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherApps.startMainActivity(component, Process.myUserHandle(), null, null)
        }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    override fun onStop() {
        handler.removeCallbacks(clockUpdater)
        worker.shutdownNow()
        super.onStop()
    }
}
