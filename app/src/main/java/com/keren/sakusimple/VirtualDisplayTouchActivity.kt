package com.keren.sakusimple

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlin.math.max
import kotlin.math.min

/** Main touchpad UI. Preview fills its frame; touchpad maps to the fixed 720 x 1280 virtual display. */
class VirtualDisplayTouchActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var shizukuBridge: ShizukuBridge
    private var cursor: View? = null
    private var virtualDisplayController: VirtualDisplayController? = null
    private var preview: SurfaceView? = null
    private val previewPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var currentPreviewBitmap: Bitmap? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private val targetWidth = 720
    private val targetHeight = 1280

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.rgb(7, 24, 45))
        }
        root.addView(TextView(this).apply {
            text = "SAKU • VIRTUAL DISPLAY"
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Preview layar virtual 720 × 1280 • touchpad berada di bawah"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(2), 0, dp(6))
        })

        shizukuBridge = ShizukuBridge(this) { message ->
            runOnUiThread {
                if (::status.isInitialized) status.text = message
            }
        }

        val connectionButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        connectionButtons.addView(Button(this).apply {
            text = "Hubungkan Shizuku"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { shizukuBridge.connect() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        connectionButtons.addView(Button(this).apply {
            text = "Buat Layar Virtual"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { startOwnedVirtualDisplay() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(connectionButtons)

        val displayButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        displayButtons.addView(Button(this).apply {
            text = "Buka Pengaturan"
            textSize = 12f
            isAllCaps = false
            setOnClickListener {
                val controller = virtualDisplayController
                if (controller == null || controller.displayId < 0) {
                    status.text = "Buat virtual display terlebih dahulu."
                } else {
                    controller.launchSettings()
                }
            }
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        displayButtons.addView(Button(this).apply {
            text = "Tutup Layar Virtual"
            textSize = 12f
            isAllCaps = false
            setOnClickListener {
                virtualDisplayController?.close()
                virtualDisplayController = null
                clearPreview()
                status.text = "Virtual display ditutup."
            }
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(displayButtons)

        status = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(105, 221, 245))
            setPadding(dp(2), dp(3), dp(2), dp(3))
            text = "Jalankan Shizuku, lalu tekan Hubungkan Shizuku."
        }
        root.addView(status)

        val screenHeightDp = (resources.displayMetrics.heightPixels /
            resources.displayMetrics.density).toInt()
        // Keep the preview viewport portrait and proportional to the real 720×1280 buffer.
        // The surrounding area uses the app background, not black letterboxing.
        val previewHeight = (screenHeightDp * 0.34f).toInt()
            .coerceIn(dp(220), dp(360))
        val previewWidth = (previewHeight.toFloat() * targetWidth / targetHeight).toInt().coerceAtLeast(dp(124))
        val previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 24, 45))
        }
        preview = SurfaceView(this).apply {
            contentDescription = "Preview virtual display 720 x 1280"
            holder.setFormat(PixelFormat.OPAQUE)
            holder.setFixedSize(targetWidth, targetHeight)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    renderPreviewFrame(holder)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    renderPreviewFrame(holder)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
        }
        previewFrame.addView(
            preview,
            FrameLayout.LayoutParams(previewWidth, previewHeight, Gravity.CENTER)
        )
        root.addView(previewFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, previewHeight
        ).apply { topMargin = dp(3); bottomMargin = dp(4) })

        root.addView(TextView(this).apply {
            text = "TOUCHPAD • area biru"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(dp(2), 0, 0, dp(3))
        })

        val stage = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(18, 44, 70))
            contentDescription = "Bidang touchpad virtual display 720 x 1280"
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
        ))

        setContentView(root)
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
                    renderPreviewFrame()
                    if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
                },
                onStatus = { message -> status.text = message }
            )
        }
        status.text = "Meminta Shizuku membuat display 720 × 1280…"
        virtualDisplayController?.start(
            width = targetWidth,
            height = targetHeight,
            densityDpi = resources.displayMetrics.densityDpi
        )
    }

    private fun clearPreview() {
        val old = currentPreviewBitmap
        currentPreviewBitmap = null
        renderPreviewFrame()
        if (old != null && !old.isRecycled) old.recycle()
    }

    /** Draw the real-resolution ImageReader frame into a 720×1280 SurfaceView buffer. */
    private fun renderPreviewFrame(targetHolder: SurfaceHolder? = null) {
        val holder = targetHolder ?: preview?.holder ?: return
        if (!holder.surface.isValid) return
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas() ?: return
            canvas.drawColor(Color.rgb(7, 24, 45))
            val bitmap = currentPreviewBitmap
            if (bitmap != null && !bitmap.isRecycled) {
                canvas.drawBitmap(
                    bitmap,
                    null,
                    Rect(0, 0, canvas.width, canvas.height),
                    previewPaint
                )
            }
        } catch (error: Throwable) {
            Log.w("SakuPreview", "Gagal merender frame virtual display", error)
        } finally {
            if (canvas != null) {
                try { holder.unlockCanvasAndPost(canvas) } catch (_: Throwable) {}
            }
        }
    }

    /** Normalize touchpad-local coordinates and scale them to the virtual display resolution. */
    private fun mapTouchCoordinate(position: Float, touchpadExtent: Int, targetExtent: Int): Float {
        if (touchpadExtent <= 0 || targetExtent <= 1) return 0f
        val normalized = (position / touchpadExtent.toFloat()).coerceIn(0f, 1f)
        return normalized * (targetExtent - 1).toFloat()
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
                if (kotlin.math.abs(x - downX) > dp(8) || kotlin.math.abs(y - downY) > dp(8)) {
                    moved = true
                }
                moveCursor(x, y, width, height)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                moveCursor(x, y, width, height)
                val startX = mapTouchCoordinate(downX, width, targetWidth)
                val startY = mapTouchCoordinate(downY, height, targetHeight)
                val endX = mapTouchCoordinate(x, width, targetWidth)
                val endY = mapTouchCoordinate(y, height, targetHeight)
                val controller = virtualDisplayController
                if (controller == null || controller.displayId < 0) {
                    status.text = "Buat virtual display lewat Shizuku sebelum mengirim sentuhan."
                    return true
                }
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) return true
                val accepted = if (moved) {
                    controller.injectSwipe(startX, startY, endX, endY)
                } else {
                    controller.injectTap(endX, endY)
                }
                if (!accepted) {
                    status.text = "Input ditolak atau koneksi Shizuku terputus. Periksa status/izin Shizuku."
                }
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
