package com.keren.sakusimple

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlin.math.max
import kotlin.math.min

/**
 * Minimal touch surface for sending taps/drags to a selected Android display.
 * Android requires the user to explicitly enable the companion AccessibilityService.
 */
class VirtualDisplayTouchActivity : ComponentActivity() {
    private lateinit var displayManager: DisplayManager
    private lateinit var displayPicker: LinearLayout
    private lateinit var status: TextView
    private var selectedDisplayId = Display.DEFAULT_DISPLAY
    private var targetWidth = 720
    private var targetHeight = 1600
    private var cursor: View? = null
    private var pad: View? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(7, 24, 45))
        }
        root.addView(TextView(this).apply {
            text = "SAKU • VIRTUAL DISPLAY"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Pilih layar target, lalu sentuh bidang di bawah. Gerakan dan tap dikirim lewat Accessibility."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(6), 0, dp(10))
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Aktifkan Aksesibilitas"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        buttons.addView(Button(this).apply {
            text = "Segarkan layar"
            setOnClickListener { refreshDisplays() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(buttons)
        displayPicker = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(displayPicker)
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(105, 221, 245))
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(status)

        val stage = FrameLayoutCompat(this)
        stage.setBackgroundColor(Color.rgb(18, 44, 70))
        stage.contentDescription = "Bidang sentuh virtual display"
        pad = stage
        cursor = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(105, 221, 245))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(8).toFloat()
            isClickable = false
        }
        stage.addView(cursor, android.widget.FrameLayout.LayoutParams(dp(18), dp(18)))
        stage.setOnTouchListener { _, event -> handleTouch(event, stage.width, stage.height) }
        root.addView(stage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(4) })

        root.addView(TextView(this).apply {
            text = "Tip: bidang ini memakai pemetaan langsung ke koordinat layar target. Jika layanan belum aktif, tap tidak akan terkirim."
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, 0)
        })
        setContentView(root)
        refreshDisplays()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        refreshDisplays()
    }

    private fun refreshDisplays() {
        displayPicker.removeAllViews()
        val displays = displayManager.displays
        if (displays.isEmpty()) {
            status.text = "Tidak ada display yang terdeteksi."
            return
        }
        displays.forEach { display ->
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            val button = Button(this).apply {
                text = "ID ${display.displayId}\n${metrics.widthPixels}×${metrics.heightPixels}"
                textSize = 10f
                isAllCaps = false
                setOnClickListener {
                    selectedDisplayId = display.displayId
                    targetWidth = max(1, metrics.widthPixels)
                    targetHeight = max(1, metrics.heightPixels)
                    updateStatus()
                    refreshDisplays()
                }
                isSelected = display.displayId == selectedDisplayId
                alpha = if (isSelected) 1f else 0.65f
            }
            displayPicker.addView(button, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                marginEnd = dp(4)
            })
        }
        updateStatus()
    }

    private fun updateStatus() {
        val enabled = isAccessibilityEnabled()
        status.text = "Target display ID $selectedDisplayId • ${targetWidth}×${targetHeight} • " +
            if (enabled) "Layanan aktif" else "Layanan belum aktif"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name.endsWith("VirtualDisplayAccessibilityService") }
    }

    private fun handleTouch(event: MotionEvent, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return true
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; lastX = x; lastY = y; moved = false
                moveCursor(x, y, width, height)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(x - downX) > dp(8) || kotlin.math.abs(y - downY) > dp(8)) moved = true
                lastX = x; lastY = y
                moveCursor(x, y, width, height)
                return true
            }
            MotionEvent.ACTION_UP -> {
                moveCursor(x, y, width, height)
                val startX = (downX / width * targetWidth).toInt().coerceIn(0, targetWidth - 1)
                val startY = (downY / height * targetHeight).toInt().coerceIn(0, targetHeight - 1)
                val endX = (x / width * targetWidth).toInt().coerceIn(0, targetWidth - 1)
                val endY = (y / height * targetHeight).toInt().coerceIn(0, targetHeight - 1)
                VirtualDisplayAccessibilityService.sendGesture(
                    selectedDisplayId, startX, startY, endX, endY, moved
                )
                return true
            }
        }
        return true
    }

    private fun moveCursor(x: Float, y: Float, width: Int, height: Int) {
        val view = cursor ?: return
        view.translationX = min(max(0f, x - dp(9)), max(0f, width - dp(18).toFloat()))
        view.translationY = min(max(0f, y - dp(9)), max(0f, height - dp(18).toFloat()))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    /** FrameLayout kept local to avoid coupling the feature to Compose. */
    private class FrameLayoutCompat(context: Context) : android.widget.FrameLayout(context)
}
