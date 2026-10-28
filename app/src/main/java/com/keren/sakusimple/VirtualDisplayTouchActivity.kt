package com.keren.sakusimple

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.ScrollView
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity

/** Main touchpad UI. Preview fills its frame; touchpad maps to the fixed 720 x 1280 virtual display. */
class VirtualDisplayTouchActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var runtimeLog: TextView
    private lateinit var runtimeLogScroll: ScrollView
    private val runtimeLogBuffer = StringBuilder()
    private val targetWidth = 1280
    private val targetHeight = 720
    private lateinit var shizukuBridge: ShizukuBridge
    private var virtualDisplayController: VirtualDisplayController? = null
    private var preview: SurfaceView? = null
    private var cursorOverlay: View? = null
    private val mouseInputExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "SakuNativeMouseInput").apply { isDaemon = true } }
    private val mouseMovePending = AtomicBoolean(false)
    private lateinit var runtimeLogToggle: Button
    private val previewPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val cursorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.BLACK
    }
    private var cursorPulseRadius = 13f
    private var cursorX = targetWidth / 2f
    private var cursorY = targetHeight / 2f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var gestureStartX = targetWidth / 2f
    private var gestureStartY = targetHeight / 2f
    private var gestureStartedAtMs = 0L
    private var lastMoveAtMs = 0L
    private var longPressDragArmed = false
    private val cursorSensitivity = 1.15f
    private val longPressDragTimeoutMs = 450L
    private var currentPreviewBitmap: Bitmap? = null
    private var moved = false
    private var receivedFrames = 0L


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
                appendRuntimeLog(message)
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
            text = "Cek Launcher"
            textSize = 12f
            isAllCaps = false
            setOnClickListener {
                val controller = virtualDisplayController
                if (controller == null || controller.displayId < 0) {
                    status.text = "Buat virtual display terlebih dahulu."
                } else {
                    val targetDisplayId = controller.displayId
                    status.text = "Memeriksa launcher di display $targetDisplayId…"
                    Thread({
                        val diagnostics = shizukuBridge.getDisplayDiagnostics(targetDisplayId)
                        runOnUiThread {
                            appendRuntimeLog("Diagnostik launcher • display=$targetDisplayId\n$diagnostics")
                            status.text = "Diagnostik launcher selesai • display=$targetDisplayId • detail di Runtime Log"
                        }
                    }, "SakuDisplayDiagnostics").start()
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
        val previewWidthPx = (resources.displayMetrics.widthPixels - dp(20)).coerceAtLeast(dp(180))
        val previewHeightPx = (previewWidthPx.toFloat() * targetHeight / targetWidth).toInt().coerceAtLeast(dp(90))
        previewFrame.addView(
            preview,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        )
        cursorOverlay = object : View(this) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                drawDextopCursor(canvas)
            }
        }.apply {
            isClickable = false
            isFocusable = false
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Dextop-style pointer marker"
        }
        previewFrame.addView(
            cursorOverlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        previewFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            cursorOverlay?.invalidate()
        }
        root.addView(previewFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, previewHeightPx
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
        stage.setOnTouchListener { _, event -> handleTouch(event, stage.width, stage.height) }
        root.addView(stage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 4f
        ))

        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logHeader.addView(TextView(this).apply {
            text = "RUNTIME LOG • tahan lama untuk salin"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(dp(2), dp(2), 0, dp(2))
        }, LinearLayout.LayoutParams(0, dp(34), 1f))
        runtimeLogToggle = Button(this).apply {
            text = "Sembunyikan Log"
            textSize = 10f
            isAllCaps = false
            minHeight = dp(30)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                val showLog = runtimeLogScroll.visibility == View.GONE
                runtimeLogScroll.visibility = if (showLog) View.VISIBLE else View.GONE
                runtimeLogToggle.text = if (showLog) "Sembunyikan Log" else "Tampilkan Log"
            }
        }
        logHeader.addView(runtimeLogToggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)
        ))
        root.addView(logHeader, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(34)
        ))
        runtimeLog = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.rgb(180, 235, 248))
            setTextIsSelectable(true)
            text = "Log runtime siap. Hasil Shizuku dan error akan muncul di sini."
            setPadding(dp(6), dp(5), dp(6), dp(5))
            setOnLongClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Saku Simple runtime log", runtimeLogBuffer.toString()))
                appendRuntimeLog("Log disalin ke clipboard.")
                true
            }
        }
        runtimeLogScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(runtimeLog, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(runtimeLogScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(72)
        ))

        setContentView(root)
    }

    override fun onDestroy() {
        mouseInputExecutor.shutdownNow()
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
                        val targetDisplayId = virtualDisplayController?.displayId ?: -1
                        if (targetDisplayId >= 0) {
                            Thread({
                                val diagnostics = shizukuBridge.getDisplayDiagnostics(targetDisplayId)
                                runOnUiThread {
                                    appendRuntimeLog("Diagnostik awal launcher • display=$targetDisplayId\n$diagnostics")
                                }
                            }, "SakuInitialDisplayDiagnostics").start()
                        }
                    }
                    if (receivedFrames == 1L || receivedFrames % 30L == 0L) {
                        status.text = if (isMostlyWhite(bitmap)) {
                            "Preview menerima frame tetapi hampir putih • periksa launcher dan Runtime Log"
                        } else {
                            "Preview launcher aktif • frame=$receivedFrames • display=${virtualDisplayController?.displayId}"
                        }
                    }
                    if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
                },
                onStatus = { message ->
                    status.text = message
                    appendRuntimeLog(message)
                }
            )
        }
        receivedFrames = 0L
        status.text = "Meminta Shizuku membuat display 1280 × 720 landscape • densityDpi=160…"
        appendRuntimeLog("Meminta display 1280×720 dengan densityDpi=160.")
        virtualDisplayController?.start(
            width = targetWidth,
            height = targetHeight,
            densityDpi = 160
        )
        // If the display exists but no frame has arrived, expose that distinction explicitly.
        status.postDelayed({
            val controller = virtualDisplayController
            if (controller != null && controller.displayId >= 0 && receivedFrames == 0L) {
                val targetDisplayId = controller.displayId
                status.text = "Belum ada frame preview • memeriksa display $targetDisplayId…"
                Thread({
                    val diagnostics = shizukuBridge.getDisplayDiagnostics(targetDisplayId)
                    runOnUiThread {
                        appendRuntimeLog("Tidak ada frame setelah 4 detik • display=$targetDisplayId\n$diagnostics")
                        if (receivedFrames == 0L) {
                            status.text = "Belum ada frame dari display $targetDisplayId • detail di Runtime Log"
                        }
                    }
                }, "SakuNoFrameDiagnostics").start()
            }
        }, 4000L)
    }

    private fun appendRuntimeLog(message: String) {
        if (!::runtimeLog.isInitialized) return
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
        runtimeLogBuffer.append("[").append(timestamp).append("] ")
            .append(message).append('\n')
        if (runtimeLogBuffer.length > 12000) {
            runtimeLogBuffer.delete(0, runtimeLogBuffer.length - 9000)
        }
        runtimeLog.text = runtimeLogBuffer.toString()
        if (::runtimeLogScroll.isInitialized) {
            runtimeLogScroll.post { runtimeLogScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun clearPreview() {
        val old = currentPreviewBitmap
        currentPreviewBitmap = null
        renderPreviewFrame()
        if (old != null && !old.isRecycled) old.recycle()
    }

    /** CursorView-style marker adapted from SakuDextop: white dot with dark outline. */
    private fun drawDextopCursor(canvas: Canvas) {
        val surface = preview ?: return
        if (surface.width <= 0 || surface.height <= 0) return
        val pointerX = surface.left + (cursorX / targetWidth) * surface.width
        val pointerY = surface.top + (cursorY / targetHeight) * surface.height
        canvas.drawCircle(pointerX, pointerY, cursorPulseRadius, cursorPaint)
        canvas.drawCircle(pointerX, pointerY, cursorPulseRadius, cursorStrokePaint)
    }

    private fun pulseDextopCursor() {
        cursorPulseRadius = 18f
        cursorOverlay?.invalidate()
        cursorOverlay?.postDelayed({
            cursorPulseRadius = 13f
            cursorOverlay?.invalidate()
        }, 100L)
    }

    /** Send native mouse motion off the UI thread; drop intermediate samples while busy. */
    private fun queueNativeMouseMove(x: Float, y: Float) {
        val controller = virtualDisplayController ?: return
        val displayId = controller.displayId
        if (displayId < 0 || !mouseMovePending.compareAndSet(false, true)) return
        mouseInputExecutor.execute {
            try {
                controller.injectHover(x, y)
            } finally {
                mouseMovePending.set(false)
            }
        }
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

    /**
     * The touchpad is relative, like a laptop trackpad: finger position does not jump the
     * pointer. Each movement fraction is applied to the same fraction of the preview axis.
     */
    private fun handleTouch(event: MotionEvent, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return true
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                downX = x
                downY = y
                moved = false
                longPressDragArmed = false
                gestureStartedAtMs = SystemClock.uptimeMillis()
                lastMoveAtMs = gestureStartedAtMs
                gestureStartX = cursorX
                gestureStartY = cursorY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = x - lastTouchX
                val deltaY = y - lastTouchY
                if (kotlin.math.abs(x - downX) > dp(8) || kotlin.math.abs(y - downY) > dp(8)) moved = true
                val now = SystemClock.uptimeMillis()
                val elapsed = (now - lastMoveAtMs).coerceAtLeast(1L)
                lastMoveAtMs = now
                val distance = kotlin.math.sqrt(deltaX * deltaX + deltaY * deltaY)
                val speedPxPerMs = distance / elapsed.toFloat()
                val acceleration = when {
                    speedPxPerMs > 2.4f -> 1.65f
                    speedPxPerMs > 1.2f -> 1.3f
                    else -> 1.0f
                }
                cursorX = (cursorX + deltaX * targetWidth / width.toFloat() * cursorSensitivity * acceleration)
                    .coerceIn(0f, (targetWidth - 1).toFloat())
                cursorY = (cursorY + deltaY * targetHeight / height.toFloat() * cursorSensitivity * acceleration)
                    .coerceIn(0f, (targetHeight - 1).toFloat())
                lastTouchX = x
                lastTouchY = y
                val heldFor = now - gestureStartedAtMs
                longPressDragArmed = moved && heldFor >= longPressDragTimeoutMs
                cursorOverlay?.invalidate()
                // Native mouse motion goes through Shizuku asynchronously, never blocking the UI thread.
                queueNativeMouseMove(cursorX, cursorY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    longPressDragArmed = false
                    return true
                }
                val controller = virtualDisplayController
                if (controller == null || controller.displayId < 0) {
                    status.text = "Buat virtual display lewat Shizuku sebelum mengirim input."
                    return true
                }
                val endX = cursorX.coerceIn(0f, (targetWidth - 1).toFloat())
                val endY = cursorY.coerceIn(0f, (targetHeight - 1).toFloat())
                val heldFor = SystemClock.uptimeMillis() - gestureStartedAtMs
                if (longPressDragArmed && moved) {
                    // Drag stays a single display-scoped swipe to avoid shell startup per movement.
                    mouseInputExecutor.execute {
                        controller.injectSwipe(gestureStartX, gestureStartY, endX, endY, heldFor.coerceIn(180L, 1200L))
                    }
                } else if (!moved && heldFor < longPressDragTimeoutMs) {
                    // Use native mouse DOWN/UP instead of a touchscreen tap.
                    mouseInputExecutor.execute {
                        controller.injectHover(endX, endY)
                        val clicked = shizukuBridge.injectMouseClick(controller.displayId, endX, endY)
                        runOnUiThread {
                            if (clicked) {
                                pulseDextopCursor()
                                status.text = "Klik mouse • x=${endX.toInt()} y=${endY.toInt()}"
                            } else {
                                status.text = "Klik mouse gagal. Periksa Runtime Log."
                            }
                        }
                    }
                }
                longPressDragArmed = false
                return true
            }
        }
        return true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
