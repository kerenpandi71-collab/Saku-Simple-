package com.keren.sakusimple

import android.graphics.Canvas
import android.graphics.Paint
import java.util.concurrent.Executors
import android.graphics.Color
import android.graphics.Typeface
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
    private val mouseInputExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SakuDirectMouseInput").apply { isDaemon = true }
    }
    private lateinit var runtimeLogToggle: Button
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
    private var pointerMoved = false
    private var hadMultiTouch = false
    private var scrollGestureActive = false
    private var lastScrollCentroidX = 0f
    private var lastScrollCentroidY = 0f

    private val mouseMoveLock = Any()
    private var pendingMouseX = Float.NaN
    private var pendingMouseY = Float.NaN
    private var mouseMoveDrainScheduled = false

    private val scrollLock = Any()
    private var pendingScrollHorizontal = 0f
    private var pendingScrollVertical = 0f
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f
    private var scrollDrainScheduled = false

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
            holder.setFixedSize(targetWidth, targetHeight)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    virtualDisplayController?.updateSurface(holder.surface)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    virtualDisplayController?.updateSurface(holder.surface)
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
        val surface = preview?.holder?.surface
        if (surface == null || !surface.isValid) {
            status.text = "Surface preview belum siap. Tunggu sebentar lalu coba lagi."
            return
        }
        if (virtualDisplayController == null) {
            virtualDisplayController = VirtualDisplayController(
                this,
                shizukuBridge,
                onStatus = { message ->
                    status.text = message
                    appendRuntimeLog(message)
                }
            )
        }
        status.text = "Membuat display langsung ke SurfaceView • 1280×720 • densityDpi=160…"
        appendRuntimeLog("Output virtual display diarahkan langsung ke SurfaceView; ImageReader/Bitmap copy tidak digunakan.")
        virtualDisplayController?.start(
            width = targetWidth,
            height = targetHeight,
            densityDpi = 160,
            outputSurface = surface
        )
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
        val holder = preview?.holder ?: return
        if (!holder.surface.isValid) return
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            canvas?.drawColor(Color.rgb(7, 24, 45))
        } catch (error: Throwable) {
            Log.w("SakuPreview", "Gagal membersihkan preview", error)
        } finally {
            if (canvas != null) runCatching { holder.unlockCanvasAndPost(canvas) }
        }
        cursorOverlay?.invalidate()
    }

    /** SakuDextop-style CursorView: filled white dot, dark outline, and click pulse. */
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

    /** Dispatch the newest pointer coordinate through the Shizuku InputManager path. */
    private fun queueNativeMouseMove(x: Float, y: Float) {
        var scheduleDrain = false
        synchronized(mouseMoveLock) {
            pendingMouseX = x
            pendingMouseY = y
            if (!mouseMoveDrainScheduled) {
                mouseMoveDrainScheduled = true
                scheduleDrain = true
            }
        }
        if (!scheduleDrain) return
        mouseInputExecutor.execute {
            while (true) {
                val point = synchronized(mouseMoveLock) {
                    if (pendingMouseX.isNaN() || pendingMouseY.isNaN()) {
                        mouseMoveDrainScheduled = false
                        null
                    } else {
                        val latest = pendingMouseX to pendingMouseY
                        pendingMouseX = Float.NaN
                        pendingMouseY = Float.NaN
                        latest
                    }
                } ?: break
                virtualDisplayController?.injectMouseMove(point.first, point.second)
            }
        }
    }

    /** Accumulate fractional two-finger scroll deltas so input stays smooth without a backlog. */
    private fun queueNativeMouseScroll(x: Float, y: Float, horizontal: Float, vertical: Float) {
        if (kotlin.math.abs(horizontal) < 0.0005f && kotlin.math.abs(vertical) < 0.0005f) return
        var scheduleDrain = false
        synchronized(scrollLock) {
            pendingScrollX = x
            pendingScrollY = y
            pendingScrollHorizontal += horizontal
            pendingScrollVertical += vertical
            if (!scrollDrainScheduled) {
                scrollDrainScheduled = true
                scheduleDrain = true
            }
        }
        if (!scheduleDrain) return
        mouseInputExecutor.execute {
            while (true) {
                val batch = synchronized(scrollLock) {
                    if (kotlin.math.abs(pendingScrollHorizontal) < 0.0005f &&
                        kotlin.math.abs(pendingScrollVertical) < 0.0005f
                    ) {
                        scrollDrainScheduled = false
                        null
                    } else {
                        val values = floatArrayOf(
                            pendingScrollX, pendingScrollY,
                            pendingScrollHorizontal, pendingScrollVertical
                        )
                        pendingScrollHorizontal = 0f
                        pendingScrollVertical = 0f
                        values
                    }
                } ?: break
                virtualDisplayController?.injectMouseScroll(batch[0], batch[1], batch[2], batch[3])
            }
        }
    }

    private fun handleTouch(event: MotionEvent, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                downX = event.x
                downY = event.y
                pointerMoved = false
                hadMultiTouch = false
                scrollGestureActive = false
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    hadMultiTouch = true
                    scrollGestureActive = true
                    val centroid = touchCentroid(event)
                    lastScrollCentroidX = centroid.first
                    lastScrollCentroidY = centroid.second
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (hadMultiTouch) {
                    if (scrollGestureActive && event.pointerCount >= 2) {
                        val centroid = touchCentroid(event)
                        val dx = centroid.first - lastScrollCentroidX
                        val dy = centroid.second - lastScrollCentroidY
                        lastScrollCentroidX = centroid.first
                        lastScrollCentroidY = centroid.second
                        queueNativeMouseScroll(
                            cursorX,
                            cursorY,
                            horizontal = -dx / dp(32).toFloat(),
                            vertical = -dy / dp(32).toFloat()
                        )
                    }
                    return true
                }

                val x = event.x
                val y = event.y
                val deltaX = x - lastTouchX
                val deltaY = y - lastTouchY
                if (kotlin.math.abs(x - downX) > dp(6) || kotlin.math.abs(y - downY) > dp(6)) {
                    pointerMoved = true
                }
                cursorX = (cursorX + deltaX * targetWidth / width.toFloat())
                    .coerceIn(0f, (targetWidth - 1).toFloat())
                cursorY = (cursorY + deltaY * targetHeight / height.toFloat())
                    .coerceIn(0f, (targetHeight - 1).toFloat())
                lastTouchX = x
                lastTouchY = y
                cursorOverlay?.invalidate()
                queueNativeMouseMove(cursorX, cursorY)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                hadMultiTouch = true
                scrollGestureActive = false
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!hadMultiTouch && !pointerMoved) {
                    val controller = virtualDisplayController
                    if (controller == null || controller.displayId < 0) {
                        status.text = "Buat virtual display lewat Shizuku sebelum mengklik."
                    } else {
                        val clickX = cursorX.coerceIn(0f, (targetWidth - 1).toFloat())
                        val clickY = cursorY.coerceIn(0f, (targetHeight - 1).toFloat())
                        mouseInputExecutor.execute {
                            val clicked = controller.injectMouseClick(clickX, clickY)
                            runOnUiThread {
                                if (clicked) {
                                    pulseDextopCursor()
                                    status.text = "Klik mouse • x=${clickX.toInt()} y=${clickY.toInt()}"
                                } else {
                                    status.text = "Klik mouse ditolak InputManager. Periksa Runtime Log."
                                    appendRuntimeLog("InputManager menolak klik mouse di x=${clickX.toInt()} y=${clickY.toInt()}")
                                }
                            }
                        }
                    }
                }
                // Finger-up only ends the gesture. There is deliberately no swipe/drag injection.
                pointerMoved = false
                hadMultiTouch = false
                scrollGestureActive = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pointerMoved = false
                hadMultiTouch = false
                scrollGestureActive = false
                return true
            }
        }
        return true
    }

    private fun touchCentroid(event: MotionEvent): Pair<Float, Float> =
        ((event.getX(0) + event.getX(1)) / 2f) to ((event.getY(0) + event.getY(1)) / 2f)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
