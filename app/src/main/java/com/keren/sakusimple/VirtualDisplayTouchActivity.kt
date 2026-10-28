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
import android.os.SystemClock
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
    private var receivedFrames = 0L
    private var lastHoverAtMs = 0L
    private var displayDiagnostics = "state belum diperiksa"

    private val targetWidth = 1280
    private val targetHeight = 720

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
            text = "Preview landscape 1280 × 720 • touchpad di bawah"
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
            text = "Buka Kalkulator / Browser"
            textSize = 12f
            isAllCaps = false
            setOnClickListener {
                val controller = virtualDisplayController
                if (controller == null || controller.displayId < 0) {
                    status.text = "Buat virtual display terlebih dahulu."
                } else {
                    controller.launchTestApp()
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

        val screenHeightPx = resources.displayMetrics.heightPixels
        val screenWidthPx = resources.displayMetrics.widthPixels
        // Preview panel takes at least 60% of screen height. The actual SurfaceView spans
        // available width in landscape 16:9 ratio, so the picture itself is never stretched.
        val previewPanelHeight = (screenHeightPx * 0.60f).toInt().coerceIn(dp(300), dp(560))
        val previewWidthPx = (screenWidthPx - dp(20)).coerceAtLeast(dp(180))
        val previewSurfaceHeight = (previewWidthPx.toFloat() * targetHeight / targetWidth)
            .toInt().coerceAtLeast(dp(90))
        val previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 24, 45))
            contentDescription = "Panel preview landscape 1280 x 720"
        }
        preview = SurfaceView(this).apply {
            contentDescription = "Preview virtual display 1280 x 720"
            holder.setFormat(PixelFormat.OPAQUE)
            holder.setFixedSize(targetWidth, targetHeight)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { renderPreviewFrame(holder) }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    renderPreviewFrame(holder)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
        }
        previewFrame.addView(
            preview,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, previewSurfaceHeight, Gravity.CENTER)
        )
        root.addView(previewFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, previewPanelHeight
        ).apply { topMargin = dp(3); bottomMargin = dp(4) })

        root.addView(TextView(this).apply {
            text = "TOUCHPAD • area biru"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(dp(2), 0, 0, dp(3))
        })

        val stage = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(18, 44, 70))
            contentDescription = "Bidang touchpad virtual display 1280 x 720"
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
                    receivedFrames++
                    renderPreviewFrame()
                    if (receivedFrames == 1L) {
                        displayDiagnostics = shizukuBridge.getDisplayDiagnostics(virtualDisplayController?.displayId ?: -1)
                    }
                    if (receivedFrames == 1L || receivedFrames % 30L == 0L) {
                        status.text = if (isMostlyWhite(bitmap)) {
                            "ImageReader menerima frame=$receivedFrames, tetapi gambar hampir seluruhnya putih • $displayDiagnostics"
                        } else {
                            "ImageReader OK • frame=$receivedFrames • 1280×720 • $displayDiagnostics"
                        }
                    }
                    if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
                },
                onStatus = { message -> status.text = message }
            )
        }
        receivedFrames = 0L
        displayDiagnostics = "memeriksa state display…"
        status.text = "Meminta Shizuku membuat display 1280 × 720 landscape…"
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

    /** Draw the real-resolution ImageReader frame into a 1280×720 SurfaceView buffer. */
    private fun renderPreviewFrame(targetHolder: SurfaceHolder? = null) {
        val holder = targetHolder ?: preview?.holder ?: return
        if (!holder.surface.isValid) return
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas() ?: return
            canvas.drawColor(Color.rgb(7, 24, 45))
            val bitmap = currentPreviewBitmap
            if (bitmap != null && !bitmap.isRecycled) {
                canvas.drawBitmap(bitmap, null, Rect(0, 0, canvas.width, canvas.height), previewPaint)
            }
        } catch (error: Throwable) {
            Log.w("SakuPreview", "Gagal merender frame virtual display", error)
        } finally {
            if (canvas != null) {
                try { holder.unlockCanvasAndPost(canvas) } catch (_: Throwable) {}
            }
        }
    }

    private fun isMostlyWhite(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        var sampled = 0
        var nearWhite = 0
        for (row in 0..5) {
            for (column in 0..7) {
                val pixel = bitmap.getPixel((bitmap.width - 1) * column / 7, (bitmap.height - 1) * row / 5)
                sampled++
                if (Color.red(pixel) > 245 && Color.green(pixel) > 245 && Color.blue(pixel) > 245) nearWhite++
            }
        }
        return sampled > 0 && nearWhite.toFloat() / sampled >= 0.92f
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
                val now = SystemClock.uptimeMillis()
                val controller = virtualDisplayController
                if (controller != null && controller.displayId >= 0 && now - lastHoverAtMs >= 24L) {
                    lastHoverAtMs = now
                    controller.injectHover(
                        mapTouchCoordinate(x, width, targetWidth),
                        mapTouchCoordinate(y, height, targetHeight)
                    )
                }
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
