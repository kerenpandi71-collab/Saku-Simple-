package com.keren.sakusimple

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlin.math.max
import kotlin.math.min

/** Main touchpad UI. Display creation and input injection are routed through Shizuku. */
class VirtualDisplayTouchActivity : ComponentActivity() {
    private lateinit var displayManager: DisplayManager
    private lateinit var displayPicker: LinearLayout
    private lateinit var status: TextView
    private lateinit var shizukuBridge: ShizukuBridge
    private var selectedDisplayId = Display.DEFAULT_DISPLAY
    private var targetWidth = 720
    private var targetHeight = 1280
    private var cursor: View? = null
    private var virtualDisplayController: VirtualDisplayController? = null
    private var preview: ImageView? = null
    private var currentPreviewBitmap: Bitmap? = null
    private var downX = 0f
    private var downY = 0f
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
            text = "Buat layar virtual lewat Shizuku, lalu gunakan bidang di bawah sebagai touchpad."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(6), 0, dp(10))
        })

        shizukuBridge = ShizukuBridge(this) { message ->
            runOnUiThread {
                if (::status.isInitialized) status.text = message
            }
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Hubungkan Shizuku"
            isAllCaps = false
            setOnClickListener { shizukuBridge.connect() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        buttons.addView(Button(this).apply {
            text = "Segarkan layar"
            isAllCaps = false
            setOnClickListener { startOwnedVirtualDisplay() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(buttons)

        val virtualButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        virtualButtons.addView(Button(this).apply {
            text = "Buka Pengaturan"
            isAllCaps = false
            setOnClickListener {
                val controller = virtualDisplayController
                if (controller == null || controller.displayId < 0) {
                    status.text = "Buat virtual display terlebih dahulu."
                } else {
                    controller.launchSettings(this@VirtualDisplayTouchActivity)
                }
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        virtualButtons.addView(Button(this).apply {
            text = "Tutup layar virtual"
            isAllCaps = false
            setOnClickListener {
                virtualDisplayController?.close()
                virtualDisplayController = null
                clearPreview()
                status.text = "Virtual display ditutup."
                refreshDisplays()
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(virtualButtons)

        preview = ImageView(this).apply {
            contentDescription = "Preview konten virtual display"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(0, 10, 20))
        }
        root.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(120)
        ).apply { topMargin = dp(6) })

        displayPicker = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(displayPicker)
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(105, 221, 245))
            setPadding(0, 0, 0, dp(8))
            text = "Jalankan Shizuku, lalu tekan Hubungkan Shizuku."
        }
        root.addView(status)

        val stage = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(18, 44, 70))
            contentDescription = "Bidang touchpad virtual display"
        }
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
            text = "Tap dan swipe dikirim lewat Shizuku. Jika Android menolak izin privileged, pesan kesalahannya tampil di atas."
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, 0)
        })

        setContentView(root)
        refreshDisplays()
    }

    override fun onResume() {
        super.onResume()
        refreshDisplays()
    }

    override fun onDestroy() {
        virtualDisplayController?.close()
        virtualDisplayController = null
        clearPreview()
        if (::shizukuBridge.isInitialized) shizukuBridge.close()
        super.onDestroy()
    }

    private fun startOwnedVirtualDisplay() {
        if (virtualDisplayController == null) {
            virtualDisplayController = VirtualDisplayController(
                this,
                shizukuBridge,
                onFrame = { bitmap ->
                    val old = currentPreviewBitmap
                    currentPreviewBitmap = bitmap
                    preview?.setImageBitmap(bitmap)
                    if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
                },
                onStatus = { message ->
                    status.text = message
                    val id = virtualDisplayController?.displayId ?: Display.INVALID_DISPLAY
                    if (id >= 0) {
                        selectedDisplayId = id
                        targetWidth = 720
                        targetHeight = 1280
                        refreshDisplays()
                    }
                }
            )
        }
        virtualDisplayController?.start(
            width = 720,
            height = 1280,
            densityDpi = resources.displayMetrics.densityDpi
        )
    }

    private fun clearPreview() {
        preview?.setImageDrawable(null)
        currentPreviewBitmap?.let { if (!it.isRecycled) it.recycle() }
        currentPreviewBitmap = null
    }

    private fun refreshDisplays() {
        if (!::displayPicker.isInitialized) return
        displayPicker.removeAllViews()
        displayManager.displays.forEach { display ->
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
                    status.text = "Target dipilih: display $selectedDisplayId • ${targetWidth}×${targetHeight}"
                    refreshDisplays()
                }
                isSelected = display.displayId == selectedDisplayId
                alpha = if (isSelected) 1f else 0.65f
            }
            displayPicker.addView(button, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                marginEnd = dp(4)
            })
        }
    }

    private fun handleTouch(event: MotionEvent, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return true
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                moved = false
                moveCursor(x, y, width, height)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(x - downX) > dp(8) || kotlin.math.abs(y - downY) > dp(8)) moved = true
                moveCursor(x, y, width, height)
                return true
            }
            MotionEvent.ACTION_UP -> {
                moveCursor(x, y, width, height)
                val startX = (downX / width * targetWidth).coerceIn(0f, (targetWidth - 1).toFloat())
                val startY = (downY / height * targetHeight).coerceIn(0f, (targetHeight - 1).toFloat())
                val endX = (x / width * targetWidth).coerceIn(0f, (targetWidth - 1).toFloat())
                val endY = (y / height * targetHeight).coerceIn(0f, (targetHeight - 1).toFloat())
                val controller = virtualDisplayController
                if (controller == null || controller.displayId < 0) {
                    status.text = "Buat virtual display lewat Shizuku sebelum mengirim sentuhan."
                    return true
                }
                if (moved) controller.injectSwipe(startX, startY, endX, endY)
                else controller.injectTap(endX, endY)
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
}
