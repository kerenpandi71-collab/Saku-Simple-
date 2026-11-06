package com.keren.sakusimple

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.concurrent.Executors

private data class StandaloneGameEntry(val label: String, val packageName: String, val activityName: String, val icon: android.graphics.drawable.Drawable)

/** Independent launcher and screen for the Saku Gamepad standalone APK flavor. */
class StandaloneGamepadActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var previewFrame: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var controlsView: StandaloneControlsView
    private lateinit var statusView: TextView
    private lateinit var shizuku: ShizukuBridge
    private lateinit var input: GamepadInputController
    private lateinit var mouseCursorOverlay: View
    private lateinit var infoPanel: LinearLayout
    private lateinit var clockInfo: TextView
    private lateinit var batteryInfo: TextView
    private lateinit var fpsInfo: TextView
    private val infoHandler = Handler(Looper.getMainLooper())
    private val fpsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SakuGameFpsMonitor").apply { isDaemon = true }
    }
    @Volatile private var fpsPollPending = false
    private val infoUpdater = object : Runnable {
        override fun run() {
            if (!::infoPanel.isInitialized) return
            clockInfo.text = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
            val battery = getSystemService(android.os.BatteryManager::class.java)
                ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            batteryInfo.text = if (battery in 0..100) "${battery}%" else "--%"
            if (false && displayId >= 0 && selectedGame != null && !fpsPollPending) {
                fpsPollPending = true
                val targetDisplay = displayId
                val targetPackage = selectedGame?.packageName.orEmpty()
                fpsExecutor.execute {
                    val measured = shizuku.getDisplayFps(targetDisplay, targetPackage)
                    runOnUiThread {
                        fpsPollPending = false
                        if (displayId == targetDisplay && selectedGame?.packageName == targetPackage) {
                            fpsInfo.text = "⚙"
                        }
                    }
                }
            } else if (displayId < 0) {
                fpsInfo.text = "⚙"
            }
            infoHandler.postDelayed(this, 2000L)
        }
    }

    private val mouseInputExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SakuStandaloneMouseInput").apply { isDaemon = true }
    }
    private val mouseGestureHandler = Handler(Looper.getMainLooper())
    private val mouseMoveLock = Any()
    private var pendingMouseX = Float.NaN
    private var pendingMouseY = Float.NaN
    private var mouseMoveDrainScheduled = false
    private var displayWidth = 1152
    private var displayHeight = 720
    private var displayDensityDpi = 0
    private var cursorX = displayWidth / 2f
    private var cursorY = displayHeight / 2f
    private var lastTrackpadX = 0f
    private var lastTrackpadY = 0f
    private var trackpadFingerDown = false
    private var trackpadPointerMoved = false
    private var trackpadHadMultiTouch = false
    private var mouseDragActive = false
    private var cursorSensitivity = 0.55f
    private var scrollCentroidX = 0f
    private var scrollCentroidY = 0f
    private var mouseLongPressTask: Runnable? = null
    private var gamepadVisible = true
    private var trackpadVisible = true
    private var mouseTrackpadMode = true
    private var displayScaleX = 1f
    private var displayScaleY = 1f
    private var portraitMode = false
    private var portraitKeyboardVisible = true
    private var portraitTrackpadVisible = true
    private lateinit var portraitKeyboardView: View
    private lateinit var portraitTrackpadView: View
    private var portraitShift = false
    private var portraitCaps = false
    private var portraitCtrl = false
    private var portraitAlt = false
    private var portraitMeta = false

    private val previewRect = RectF()
    private var displayId = -1
    private var selectedGame: StandaloneGameEntry? = null
    private var games: List<StandaloneGameEntry> = emptyList()
    private var profile = GamepadProfile()
    private var inputPaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        portraitMode = getSharedPreferences("standalone_gamepad_ui", Context.MODE_PRIVATE)
            .getBoolean("portrait_mode", false)
        requestedOrientation = if (portraitMode) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.rgb(2, 6, 19)))
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )

        games = discoverGames()
        selectedGame = games.firstOrNull { it.packageName == ML_PACKAGE }
        profile = GamepadProfile.load(this, selectedGame?.packageName.orEmpty())
        val uiPrefs = getSharedPreferences("standalone_gamepad_ui", Context.MODE_PRIVATE)
        val modePrefix = if (portraitMode) "portrait" else "landscape"
        val legacyWidth = uiPrefs.getInt("display_width", if (portraitMode) 720 else 1152)
        val legacyHeight = uiPrefs.getInt("display_height", if (portraitMode) 1280 else 720)
        val legacyDpi = uiPrefs.getInt("display_density_dpi", resources.displayMetrics.densityDpi.coerceIn(120, 640))
        displayWidth = uiPrefs.getInt("${modePrefix}_display_width", legacyWidth).coerceIn(320, 2560)
        displayHeight = uiPrefs.getInt("${modePrefix}_display_height", legacyHeight).coerceIn(320, 2560)
        displayDensityDpi = uiPrefs.getInt("${modePrefix}_display_density_dpi", legacyDpi).coerceIn(120, 640)
        portraitKeyboardVisible = uiPrefs.getBoolean("portrait_keyboard_visible", true)
        portraitTrackpadVisible = uiPrefs.getBoolean("portrait_trackpad_visible", true)
        cursorX = displayWidth / 2f
        cursorY = displayHeight / 2f
        gamepadVisible = uiPrefs.getBoolean("gamepad_visible", true)
        trackpadVisible = uiPrefs.getBoolean("trackpad_visible", true)
        mouseTrackpadMode = uiPrefs.getBoolean("mouse_trackpad_mode", true)
        displayScaleX = uiPrefs.getFloat("display_scale_x", 1f).coerceIn(0.50f, 1.50f)
        displayScaleY = uiPrefs.getFloat("display_scale_y", 1f).coerceIn(0.50f, 1.50f)

        shizuku = ShizukuBridge(this) { message -> runOnUiThread { setStatus(message) } }
        input = GamepadInputController(
            displayId = { displayId },
            displayWidth = { displayWidth },
            displayHeight = { displayHeight },
            inject = { id, action, pointerIds, xs, ys ->
                if (pointerIds.size == 1 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                    shizuku.injectTouchEvent(id, action, xs[0], ys[0])
                } else {
                    shizuku.injectTouchPoints(id, action, pointerIds, xs, ys)
                }
            }
        )
        buildScreen()
    }

    private fun buildScreen() {
        if (portraitMode) {
            buildPortraitScreen()
            return
        }
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(2, 6, 19)) }
        root.addView(NeonWaveBackground(this), FrameLayout.LayoutParams(-1, -1))

        previewFrame = FrameLayout(this).apply {
            background = rounded(Color.rgb(2, 6, 19), dp(22), Color.TRANSPARENT, dp(0))
            foreground = rounded(Color.TRANSPARENT, dp(22), Color.rgb(38, 104, 255), dp(1))
            foregroundGravity = Gravity.FILL
            clipToOutline = true
            contentDescription = "Layar game virtual ${displayWidth} banding ${displayHeight}"
        }
        surfaceView = SurfaceView(this).apply {
            contentDescription = "Preview game pada layar Saku Gamepad"
            holder.setFixedSize(displayWidth, displayHeight)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    setStatus("Siap • pilih game lalu hubungkan Shizuku")
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    if (displayId >= 0 && holder.surface.isValid) shizuku.setVirtualDisplaySurface(holder.surface)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
        }
        previewFrame.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        root.addView(previewFrame, FrameLayout.LayoutParams(dp(1), dp(1), Gravity.CENTER))

        controlsView = StandaloneControlsView(
            context = this,
            input = input,
            bounds = previewRect,
            initialProfile = profile,
            onProfileChanged = { updated ->
                profile = updated
                selectedGame?.let { profile.save(this, it.packageName) }
                controlsView.setProfile(updated)
                setStatus("Mapping disimpan • ${selectedGame?.label ?: "pilih game"}")
            },
            onStatus = ::setStatus,
            onMouseTouch = { event, viewWidth, viewHeight ->
                if (trackpadVisible && mouseTrackpadMode) handleTrackpadTouch(event, viewWidth, viewHeight) else false
            },
        )
        controlsView.setLayoutPackage(selectedGame?.packageName.orEmpty())
        controlsView.setGamepadVisible(gamepadVisible)
        controlsView.setMouseTrackpadEnabled(mouseTrackpadMode && trackpadVisible)
        mouseCursorOverlay = object : View(this) {
            private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
            private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (displayId < 0 || previewRect.isEmpty) return
                val x = previewRect.left + cursorX / displayWidth * previewRect.width()
                val y = previewRect.top + cursorY / displayHeight * previewRect.height()
                canvas.drawCircle(x, y, dp(8).toFloat(), cursorFill)
                canvas.drawCircle(x, y, dp(8).toFloat(), cursorStroke)
            }
        }.apply {
            isClickable = false
            isFocusable = false
            contentDescription = "Indikator kursor mouse virtual"
        }
        root.addView(mouseCursorOverlay, FrameLayout.LayoutParams(-1, -1))

        root.addView(controlsView, FrameLayout.LayoutParams(-1, -1))
        // Input view tetap aktif saat visual gamepad disembunyikan.
        controlsView.visibility = View.VISIBLE

        // Navigasi virtual berdiri sendiri dan tidak ikut disembunyikan bersama gamepad.
        root.addView(VirtualNavigationView(this, ::sendVirtualNavigationKey),
            FrameLayout.LayoutParams(dp(112), dp(42), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(22)
                bottomMargin = dp(20)
            })
        infoPanel = buildInfoPanel()
        root.addView(infoPanel, FrameLayout.LayoutParams(-2, dp(42), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(14)
            rightMargin = dp(18)
        })
        infoHandler.removeCallbacks(infoUpdater)
        infoHandler.post(infoUpdater)
        setContentView(root)

        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return@addOnLayoutChangeListener

            // Skala X/Y terpisah: 100% mempertahankan rasio asli display.
            val displayAspect = displayWidth.toFloat() / displayHeight.coerceAtLeast(1)
            val baseHeight = min(height * 0.65f, width * 0.65f / displayAspect)
            val baseWidth = baseHeight * displayAspect
            val contentWidth = (baseWidth * displayScaleX).roundToInt().coerceIn(1, width)
            val contentHeight = (baseHeight * displayScaleY).roundToInt().coerceIn(1, height)
            val previewLeft = (width - contentWidth) / 2f
            val contentTop = ((height - contentHeight) / 2f).roundToInt()

            val params = previewFrame.layoutParams as FrameLayout.LayoutParams
            params.width = contentWidth
            params.height = contentHeight
            params.gravity = Gravity.TOP or Gravity.START
            params.leftMargin = previewLeft.roundToInt()
            params.topMargin = contentTop
            previewFrame.layoutParams = params

            val surfaceParams = surfaceView.layoutParams as FrameLayout.LayoutParams
            surfaceParams.width = contentWidth
            surfaceParams.height = contentHeight
            surfaceParams.gravity = Gravity.TOP or Gravity.START
            surfaceParams.leftMargin = 0
            surfaceParams.topMargin = 0
            surfaceView.layoutParams = surfaceParams

            previewRect.set(previewLeft, contentTop.toFloat(), previewLeft + contentWidth, contentTop + contentHeight.toFloat())
            controlsView.setPreviewRect(previewRect)
            mouseCursorOverlay.invalidate()
        }
        setStatus(if (selectedGame == null) "Pilih game • hubungkan Shizuku • buat display" else "${selectedGame!!.label} siap dipilih")
    }

    private fun buildPortraitScreen() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(2, 6, 19)) }
        root.addView(NeonWaveBackground(this), FrameLayout.LayoutParams(-1, -1))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(7), dp(5), dp(7), dp(7)) }
        column.addView(buildTopDock(), LinearLayout.LayoutParams(-1, dp(54)))
        infoPanel = buildInfoPanel()
        column.addView(infoPanel, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(4); bottomMargin = dp(4) })

        previewFrame = FrameLayout(this).apply {
            background = rounded(Color.rgb(2, 6, 19), dp(12), Color.rgb(38, 104, 255), dp(1))
            clipToOutline = true
        }
        surfaceView = SurfaceView(this).apply {
            contentDescription = "Virtual display preview portrait"
            holder.setFixedSize(displayWidth, displayHeight)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { setStatus("Siap • hubungkan Shizuku lalu buat virtual display") }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    if (displayId >= 0 && holder.surface.isValid) shizuku.setVirtualDisplaySurface(holder.surface)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
            setOnTouchListener { _, event ->
                val point = ControlPoint((event.x / width.coerceAtLeast(1)).coerceIn(.02f, .98f),
                    (event.y / height.coerceAtLeast(1)).coerceIn(.02f, .98f))
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> input.press(PORTRAIT_TOUCH_POINTER, point)
                    MotionEvent.ACTION_MOVE -> input.move(PORTRAIT_TOUCH_POINTER, point)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> input.release(PORTRAIT_TOUCH_POINTER)
                }
                true
            }
        }
        previewFrame.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        controlsView = StandaloneControlsView(
            context = this, input = input, bounds = previewRect, initialProfile = profile,
            onProfileChanged = { updated -> profile = updated; selectedGame?.let { profile.save(this, it.packageName) }; controlsView.setProfile(updated) },
            onStatus = ::setStatus,
            onMouseTouch = { _, _, _ -> false }
        ).apply {
            setLayoutPackage(selectedGame?.packageName.orEmpty())
            setGamepadVisible(false)
            setMouseTrackpadEnabled(false)
            visibility = View.GONE
        }
        previewFrame.addView(controlsView, FrameLayout.LayoutParams(-1, -1))
        column.addView(previewFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(5) })

        portraitKeyboardView = buildPortraitKeyboard().apply {
            visibility = if (portraitKeyboardVisible) View.VISIBLE else View.GONE
        }
        column.addView(portraitKeyboardView, LinearLayout.LayoutParams(-1, 0, 1.35f).apply { bottomMargin = dp(5) })
        portraitTrackpadView = buildPortraitTrackpadPanel().apply {
            visibility = if (portraitTrackpadVisible) View.VISIBLE else View.GONE
        }
        column.addView(portraitTrackpadView, LinearLayout.LayoutParams(-1, 0, .9f))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        mouseCursorOverlay = object : View(this) {
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
            override fun onDraw(canvas: Canvas) {
                if (displayId < 0 || previewRect.isEmpty) return
                val x = previewRect.left + cursorX / displayWidth * previewRect.width()
                val y = previewRect.top + cursorY / displayHeight * previewRect.height()
                canvas.drawCircle(x, y, dp(6).toFloat(), fill)
                canvas.drawCircle(x, y, dp(6).toFloat(), stroke)
            }
        }.apply { isClickable = false; isFocusable = false }
        root.addView(mouseCursorOverlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        infoHandler.removeCallbacks(infoUpdater)
        infoHandler.post(infoUpdater)
        root.addOnLayoutChangeListener { _, _, _, right, bottom, _, _, _, _ ->
            val width = right.coerceAtLeast(0)
            val height = bottom.coerceAtLeast(0)
            if (width <= 0 || height <= 0) return@addOnLayoutChangeListener
            val availableHeight = (height - dp(111)).coerceAtLeast(dp(240))
            val previewHeight = min(width / (displayWidth.toFloat() / displayHeight.coerceAtLeast(1)), availableHeight * .43f)
            val previewParams = previewFrame.layoutParams as LinearLayout.LayoutParams
            val keyboardParams = portraitKeyboardView.layoutParams as LinearLayout.LayoutParams
            val trackpadParams = portraitTrackpadView.layoutParams as LinearLayout.LayoutParams
            val showKeyboard = portraitKeyboardVisible
            val showTrackpad = portraitTrackpadVisible
            portraitKeyboardView.visibility = if (showKeyboard) View.VISIBLE else View.GONE
            portraitTrackpadView.visibility = if (showTrackpad) View.VISIBLE else View.GONE
            val remainder = (availableHeight - previewHeight - dp(10)).coerceAtLeast(dp(120).toFloat())
            if (previewParams.height != previewHeight.roundToInt() || previewParams.weight != 0f) {
                previewParams.height = previewHeight.roundToInt(); previewParams.weight = 0f; previewFrame.layoutParams = previewParams
            }
            keyboardParams.height = if (showKeyboard && showTrackpad) (remainder * .61f).roundToInt() else if (showKeyboard) remainder.roundToInt() else 0
            keyboardParams.weight = 0f
            portraitKeyboardView.layoutParams = keyboardParams
            trackpadParams.height = if (showKeyboard && showTrackpad) (remainder * .39f).roundToInt() else if (showTrackpad) remainder.roundToInt() else 0
            trackpadParams.weight = 0f
            portraitTrackpadView.layoutParams = trackpadParams
            val col = previewFrame.parent as View
            previewRect.set((col.left + previewFrame.left).toFloat(), (col.top + previewFrame.top).toFloat(),
                (col.left + previewFrame.left + previewFrame.width).toFloat(), (col.top + previewFrame.top + previewFrame.height).toFloat())
            controlsView.setPreviewRect(previewRect)
            mouseCursorOverlay.invalidate()
        }
        setStatus("Portrait workspace • virtual display, keyboard, trackpad")
    }

    private fun buildPortraitTrackpadPanel(): View = object : View(this) {
        private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 5, 18, 39) }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 76, 157, 255); style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
        private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(65, 153, 203, 255); strokeWidth = dp(1).toFloat() }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 239, 255); textAlign = Paint.Align.CENTER; textSize = dp(10).toFloat(); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
        override fun onDraw(canvas: Canvas) {
            val r = RectF(dp(2).toFloat(), dp(2).toFloat(), width - dp(2).toFloat(), height - dp(2).toFloat())
            canvas.drawRoundRect(r, dp(14).toFloat(), dp(14).toFloat(), panel)
            canvas.drawRoundRect(r, dp(14).toFloat(), dp(14).toFloat(), border)
            canvas.drawLine(r.left + dp(14), r.centerY(), r.right - dp(14), r.centerY(), guide)
            canvas.drawLine(r.centerX(), r.top + dp(22), r.centerX(), r.bottom - dp(22), guide)
            canvas.drawText("TRACKPAD", r.centerX(), r.top + dp(16), label)
            canvas.drawText("GESER • KURSOR   |   TAP • KLIK", r.centerX(), r.bottom - dp(20), label)
            canvas.drawText("2 JARI • SCROLL   •   TAHAN • DRAG", r.centerX(), r.bottom - dp(6), label)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean = handleTrackpadTouch(event, width, height)
        override fun performClick(): Boolean { super.performClick(); return true }
    }.apply { isClickable = true; isFocusable = true; contentDescription = "Trackpad virtual: geser, klik, drag, dua jari scroll" }

    private fun buildPortraitKeyboard(): View {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(Color.argb(236, 5, 17, 34), dp(11), Color.argb(190, 41, 112, 192), dp(1)); setPadding(dp(4), dp(3), dp(4), dp(3)) }
        val rows = listOf(
            listOf("Esc", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "Del"),
            listOf("`", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=", "⌫"),
            listOf("Tab", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]"),
            listOf("Caps", "A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'", "Enter"),
            listOf("Shift", "Z", "X", "C", "V", "B", "N", "M", ",", ".", "/", "Shift"),
            listOf("Ctrl", "Fn", "⊞", "Alt", "Space", "Alt", "←", "↓", "↑", "→")
        )
        rows.forEach { labels ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            labels.forEach { text ->
                val weight = when (text) { "Space" -> 3f; "Tab", "Caps", "Shift", "Enter", "⌫" -> 1.5f; else -> 1f }
                row.addView(TextView(this).apply {
                    this.text = text; textSize = if (text.length >= 3) 8f else 9f; gravity = Gravity.CENTER; maxLines = 1
                    setTextColor(Color.rgb(230, 243, 255)); background = rounded(Color.rgb(12, 34, 58), dp(5), Color.rgb(38, 88, 144), dp(1))
                    isClickable = true; isFocusable = true; setOnClickListener { sendPortraitKey(text) }
                }, LinearLayout.LayoutParams(0, -1, weight).apply { leftMargin = dp(1); rightMargin = dp(1); topMargin = dp(1); bottomMargin = dp(1) })
            }
            panel.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return panel
    }

    private fun sendPortraitKey(label: String) {
        when (label) {
            "Shift" -> { portraitShift = !portraitShift; setStatus(if (portraitShift) "Shift aktif" else "Shift mati"); return }
            "Caps" -> { portraitCaps = !portraitCaps; setStatus(if (portraitCaps) "Caps Lock aktif" else "Caps Lock mati"); return }
            "Ctrl" -> { portraitCtrl = !portraitCtrl; setStatus(if (portraitCtrl) "Ctrl aktif" else "Ctrl mati"); return }
            "Alt" -> { portraitAlt = !portraitAlt; setStatus(if (portraitAlt) "Alt aktif" else "Alt mati"); return }
            "⊞" -> { portraitMeta = !portraitMeta; setStatus(if (portraitMeta) "Meta aktif" else "Meta mati"); return }
        }
        val id = displayId
        if (id < 0) { setStatus("Buat virtual display sebelum mengetik"); return }
        val code = when (label) {
            "⌫" -> KeyEvent.KEYCODE_DEL; "Del" -> KeyEvent.KEYCODE_FORWARD_DEL; "Enter" -> KeyEvent.KEYCODE_ENTER
            "Tab" -> KeyEvent.KEYCODE_TAB; "Esc" -> KeyEvent.KEYCODE_ESCAPE; "Space" -> KeyEvent.KEYCODE_SPACE
            "←" -> KeyEvent.KEYCODE_DPAD_LEFT; "→" -> KeyEvent.KEYCODE_DPAD_RIGHT; "↑" -> KeyEvent.KEYCODE_DPAD_UP; "↓" -> KeyEvent.KEYCODE_DPAD_DOWN
            "F1" -> KeyEvent.KEYCODE_F1; "F2" -> KeyEvent.KEYCODE_F2; "F3" -> KeyEvent.KEYCODE_F3; "F4" -> KeyEvent.KEYCODE_F4
            "F5" -> KeyEvent.KEYCODE_F5; "F6" -> KeyEvent.KEYCODE_F6; "F7" -> KeyEvent.KEYCODE_F7; "F8" -> KeyEvent.KEYCODE_F8
            "F9" -> KeyEvent.KEYCODE_F9; "F10" -> KeyEvent.KEYCODE_F10
            else -> null
        }
        val shiftedSymbols = mapOf("1" to "!", "2" to "@", "3" to "#", "4" to "$", "5" to "%", "6" to "^", "7" to "&", "8" to "*", "9" to "(", "0" to ")", "-" to "_", "=" to "+", "`" to "~", "[" to "{", "]" to "}", ";" to ":", "'" to "\"", "," to "<", "." to ">", "/" to "?")
        val useShift = portraitShift || portraitCaps
        val keyText = when {
            label == "Space" -> " "
            label == "Enter" -> "\n"
            label.length == 1 && label[0].isLetter() -> if (useShift) label.uppercase() else label.lowercase()
            label.length == 1 && useShift -> shiftedSymbols[label] ?: label
            label.length == 1 -> label
            else -> null
        }
        val metaState = (if (portraitCtrl) KeyEvent.META_CTRL_ON else 0) or
            (if (portraitAlt) KeyEvent.META_ALT_ON else 0) or
            (if (portraitMeta) KeyEvent.META_META_ON else 0)
        if (portraitShift && label.length == 1) portraitShift = false
        mouseInputExecutor.execute {
            val events = if (code == null && keyText != null) runCatching { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(keyText.toCharArray()) }.getOrNull() else null
            val accepted = if (!events.isNullOrEmpty()) shizuku.injectKeyEvents(id, IntArray(events.size) { events[it].action }, IntArray(events.size) { events[it].keyCode }, IntArray(events.size) { events[it].metaState or metaState })
            else if (code != null) shizuku.injectKeyEvents(id, intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), intArrayOf(code, code), intArrayOf(metaState, metaState)) else true
            if (!accepted) runOnUiThread { setStatus("Tombol $label gagal dikirim ke virtual display") }
        }
    }

    private fun selectPortraitMode(portrait: Boolean) {
        if (portrait == portraitMode) return
        portraitMode = portrait
        getSharedPreferences("standalone_gamepad_ui", Context.MODE_PRIVATE).edit().putBoolean("portrait_mode", portrait).apply()
        input.releaseAll()
        requestedOrientation = if (portrait) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun showLayoutModePicker() {
        android.app.AlertDialog.Builder(this).setTitle("Mode tampilan")
            .setSingleChoiceItems(arrayOf("Landscape • gamepad", "Portrait • keyboard + trackpad"), if (portraitMode) 1 else 0) { dialog, which -> dialog.dismiss(); selectPortraitMode(which == 1) }
            .setNegativeButton("Batal", null).show()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::input.isInitialized || !::shizuku.isInitialized) return
        portraitMode = getSharedPreferences("standalone_gamepad_ui", Context.MODE_PRIVATE).getBoolean("portrait_mode", false)
        buildScreen()
    }


    private fun buildTopDock(): View {
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(Color.argb(235, 5, 17, 43), dp(33), Color.argb(210, 58, 103, 225), dp(1))
            elevation = dp(8).toFloat()
        }
        addDockIcon(dock, "▦", "Game", "Pilih game") { showGamePicker() }
        addDockIcon(dock, "◉", "Izin", "Hubungkan Shizuku") { connectShizuku() }
        addDockIcon(dock, "▣", "Layar", "Buat atau tutup layar game") {
            if (displayId >= 0) closeDisplay() else createDisplay()
        }
        addDockIcon(dock, "▶", "Mainkan", "Jalankan game di layar virtual") { launchSelectedGame() }
        addDockIcon(dock, "⚙", "Menu", "Pengaturan dan kontrol lainnya") { showQuickSettings() }
        addDockIcon(dock, "↶", "Back", "Kembali pada virtual display") { sendVirtualNavigationKey(KeyEvent.KEYCODE_BACK) }
        addDockIcon(dock, "⌂", "Home", "Home pada virtual display") { sendVirtualNavigationKey(KeyEvent.KEYCODE_HOME) }
        return dock
    }

    private fun addDockIcon(parent: LinearLayout, glyph: String, shortLabel: String, description: String, action: () -> Unit) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = description
            background = rounded(Color.argb(145, 13, 39, 86), dp(17), Color.argb(95, 111, 164, 255), dp(1))
            setPadding(dp(4), dp(1), dp(4), dp(1))
            setOnClickListener { action() }
            isClickable = true
            isFocusable = true
        }
        item.addView(TextView(this).apply {
            text = glyph
            textSize = 21f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(Color.rgb(237, 247, 255))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        item.addView(TextView(this).apply {
            text = shortLabel
            textSize = 9f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(Color.rgb(194, 216, 255))
        }, LinearLayout.LayoutParams(-1, dp(15)))
        val itemWidth = if (shortLabel == "Back" || shortLabel == "Home") dp(40) else dp(54)
        parent.addView(item, LinearLayout.LayoutParams(itemWidth, -1).apply { leftMargin = dp(1); rightMargin = dp(1) })
    }

    private fun addDockSeparator(parent: LinearLayout) {
        val separator = View(this).apply { setBackgroundColor(Color.argb(135, 126, 177, 255)) }
        parent.addView(separator, LinearLayout.LayoutParams(dp(1), dp(37)).apply {
            leftMargin = dp(6); rightMargin = dp(6)
        })
    }

    private fun showQuickSettings() {
        val entries = arrayOf(
            "Hubungkan Shizuku",
            "Buat / tutup virtual display",
            "Atur resolusi & DPI display",
            "Pilih game",
            "Jalankan game pada display",
            if (gamepadVisible) "Sembunyikan gamepad" else "Tampilkan gamepad",
            if (mouseTrackpadMode) "Mode input: Mouse trackpad (ubah ke Touch)" else "Mode input: Touch langsung (ubah ke Mouse)",
            if (trackpadVisible) "Matikan trackpad layar penuh" else "Aktifkan trackpad layar penuh",
            if (controlsView.isMappingMode) "Selesai atur mapping titik sentuh" else "Atur titik sentuh game",
            if (controlsView.isOverlayEditMode) "Selesai atur posisi analog/tombol" else "Atur posisi analog & tombol",
            "Atur ukuran masing-masing tombol",
            if (inputPaused) "Lanjutkan input" else "Jeda input",
            "Atur peregangan tampilan",
            "Pilih mode Landscape / Portrait",
            if (portraitKeyboardVisible) "Sembunyikan keyboard Portrait" else "Tampilkan keyboard Portrait",
            if (portraitTrackpadVisible) "Sembunyikan trackpad Portrait" else "Tampilkan trackpad Portrait"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Saku Gamepad • kontrol")
            .setItems(entries) { _, index ->
                when (index) {
                    0 -> connectShizuku()
                    1 -> if (displayId >= 0) closeDisplay() else createDisplay()
                    2 -> showDisplaySettings()
                    3 -> showGamePicker()
                    4 -> launchSelectedGame()
                    5 -> toggleGamepadVisibility()
                    6 -> toggleInputMode()
                    7 -> toggleTrackpadVisibility()
                    8 -> toggleMappingMode()
                    9 -> controlsView.setOverlayEditMode(!controlsView.isOverlayEditMode)
                    10 -> showControlSizeSettings()
                    11 -> togglePause()
                    12 -> showDisplayStretchSettings()
                    13 -> showLayoutModePicker()
                    14 -> togglePortraitKeyboardVisibility()
                    15 -> togglePortraitTrackpadVisibility()
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun discoverGames(): List<StandaloneGameEntry> {
        // Tampilkan semua aplikasi non-system yang terpasang, ditambah aplikasi
        // system/updated-system yang memang mempunyai ikon/aktivitas di launcher.
        // Jangan masukkan service, provider, framework, atau paket sistem tersembunyi.
        return packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != packageName }
            .mapNotNull { app ->
                val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                val launcherActivity = launchIntent?.component?.className
                    ?: packageManager.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setPackage(app.packageName),
                        PackageManager.MATCH_DEFAULT_ONLY
                    ).firstOrNull()?.activityInfo?.name
                val isSystemApp = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystemApp = (app.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val hasLauncherEntry = !launcherActivity.isNullOrBlank()
                if (isSystemApp && !isUpdatedSystemApp && !hasLauncherEntry) return@mapNotNull null

                val label = runCatching { packageManager.getApplicationLabel(app).toString() }
                    .getOrDefault(app.packageName).ifBlank { app.packageName }
                val icon = runCatching { packageManager.getApplicationIcon(app) }
                    .getOrElse { getDrawable(android.R.drawable.sym_def_app_icon)!! }
                StandaloneGameEntry(label, app.packageName, launcherActivity.orEmpty(), icon)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()
    }

    private fun showGamePicker() {
        // Refresh on every open so games installed/updated while the app was running appear.
        games = discoverGames()
        if (games.isEmpty()) {
            Toast.makeText(this, "Tidak ada paket aplikasi yang terdeteksi. Pastikan izin QUERY_ALL_PACKAGES tersedia.", Toast.LENGTH_LONG).show()
            return
        }
        val selected = games.indexOfFirst { it.packageName == selectedGame?.packageName }
        val appAdapter = object : BaseAdapter() {
            override fun getCount(): Int = games.size
            override fun getItem(position: Int): StandaloneGameEntry = games[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@StandaloneGamepadActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                    minimumHeight = dp(64)
                    val iconView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                    addView(iconView, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(14) })
                    val nameView = TextView(context).apply {
                        textSize = 15f
                        setTextColor(Color.WHITE)
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    addView(nameView, LinearLayout.LayoutParams(0, -2, 1f))
                }
                (row.getChildAt(0) as ImageView).setImageDrawable(getItem(position).icon)
                (row.getChildAt(1) as TextView).text = buildString {
                    append(getItem(position).label)
                    append("\n")
                    append(getItem(position).packageName)
                    if (getItem(position).activityName.isBlank()) append(" • Komponen sistem (tidak bisa dibuka langsung)")
                }
                return row
            }
        }
        val list = android.widget.ListView(this).apply {
            adapter = appAdapter
            choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
            if (selected >= 0) setItemChecked(selected, true)
            divider = android.graphics.drawable.ColorDrawable(Color.argb(45, 255, 255, 255))
            dividerHeight = dp(1)
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Pilih aplikasi Android (${games.size})")
            .setView(list)
            .setNegativeButton("Batal", null)
            .create()
        list.setOnItemClickListener { _, _, which, _ ->
            selectedGame = games[which]
            profile = GamepadProfile.load(this, selectedGame!!.packageName)
            controlsView.setProfile(profile)
            controlsView.setLayoutPackage(selectedGame!!.packageName)
            dialog.dismiss()
            setStatus("Dipilih • " + selectedGame!!.label)
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.82f).toInt(), (resources.displayMetrics.heightPixels * 0.78f).toInt())
    }

    private fun showControlSizeSettings() {
        val controls = listOf(GamepadControl.MOVE, GamepadControl.ATTACK, GamepadControl.SKILL_1,
            GamepadControl.SKILL_2, GamepadControl.SKILL_3, GamepadControl.BATTLE_SPELL,
            GamepadControl.REGEN)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(panel) }
        controls.forEach { control ->
            panel.addView(TextView(this).apply {
                text = if (control == GamepadControl.MOVE) "Analog kiri" else control.label
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(0, dp(10), 0, dp(2))
            })
            panel.addView(SeekBar(this).apply {
                max = 100
                progress = (((controlsView.controlScale(control) - 0.6f) / 1.0f) * 100f).roundToInt().coerceIn(0, 100)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        controlsView.setControlScale(control, 0.6f + value / 100f)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            }, LinearLayout.LayoutParams(-1, dp(38)))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Ukuran kontrol")
            .setView(scroll)
            .setPositiveButton("Selesai", null)
            .setNeutralButton("Reset ukuran") { _, _ -> controls.forEach { controlsView.setControlScale(it, 1f) } }
            .show()
    }

    private fun showDisplayStretchSettings() {
        val prefs = getSharedPreferences("standalone_gamepad_ui", Context.MODE_PRIVATE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun addScaleControl(title: String, initial: Float, onChange: (Float) -> Unit) {
            val value = TextView(this).apply {
                text = "$title • ${(initial * 100).roundToInt()}%"
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(0, dp(12), 0, dp(2))
            }
            val slider = SeekBar(this).apply {
                max = 100
                progress = ((initial - 0.50f) * 100f).roundToInt().coerceIn(0, 100)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val scale = 0.50f + progress / 100f
                        value.text = "$title • ${(scale * 100).roundToInt()}%"
                        onChange(scale)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            }
            panel.addView(value)
            panel.addView(slider, LinearLayout.LayoutParams(-1, dp(42)))
        }
        addScaleControl("Lebar horizontal", displayScaleX) {
            displayScaleX = it
            prefs.edit().putFloat("display_scale_x", it).apply()
            root.requestLayout()
        }
        addScaleControl("Tinggi vertikal", displayScaleY) {
            displayScaleY = it
            prefs.edit().putFloat("display_scale_y", it).apply()
            root.requestLayout()
        }
        panel.addView(TextView(this).apply {
            text = "100% = rasio asli. Atur lebar dan tinggi secara terpisah untuk menyesuaikan tampilan game."
            textSize = 12f
            setTextColor(Color.rgb(190, 211, 240))
            setPadding(0, dp(10), 0, dp(4))
        })
        android.app.AlertDialog.Builder(this)
            .setTitle("Peregangan tampilan virtual")
            .setView(panel)
            .setNegativeButton("Tutup", null)
            .setNeutralButton("Reset 100%") { _, _ ->
                displayScaleX = 1f
                displayScaleY = 1f
                prefs.edit().putFloat("display_scale_x", 1f).putFloat("display_scale_y", 1f).apply()
                root.requestLayout()
            }
            .setPositiveButton("Simpan", null)
            .show()
        prefs.edit().putFloat("display_scale_x", displayScaleX).putFloat("display_scale_y", displayScaleY).apply()
    }

    private fun showDisplaySettings() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        fun addField(title: String, value: Int): android.widget.EditText {
            panel.addView(TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(0, dp(10), 0, dp(3))
            })
            return android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                setText(value.toString())
                setTextColor(Color.WHITE)
                setHintTextColor(Color.LTGRAY)
                background = rounded(Color.argb(85, 110, 150, 220), dp(10), Color.argb(150, 140, 190, 255), dp(1))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                panel.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
            }
        }
        val widthInput = addField("Lebar (px)", displayWidth)
        val heightInput = addField("Tinggi (px)", displayHeight)
        val dpiInput = addField("DPI", displayDensityDpi)
        panel.addView(TextView(this).apply {
            text = "Rentang: lebar/tinggi 320–2560 px, DPI 120–640. Jika display aktif, pengaturan diterapkan dengan membuat ulang display."
            textSize = 12f
            setTextColor(Color.rgb(190, 211, 240))
            setPadding(0, dp(12), 0, dp(4))
        })
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Resolusi & DPI display")
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton("Batal", null)
            .setNeutralButton("Default 1152×720", null)
            .setPositiveButton("Terapkan", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val w = widthInput.text.toString().toIntOrNull()
                val h = heightInput.text.toString().toIntOrNull()
                val dpi = dpiInput.text.toString().toIntOrNull()
                if (w == null || w !in 320..2560 || h == null || h !in 320..2560 || dpi == null || dpi !in 120..640) {
                    Toast.makeText(this, "Isi lebar/tinggi 320–2560 dan DPI 120–640.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                applyDisplaySettings(w, h, dpi)
                dialog.dismiss()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                widthInput.setText("1152")
                heightInput.setText("720")
                dpiInput.setText(resources.displayMetrics.densityDpi.coerceIn(120, 640).toString())
            }
        }
        dialog.show()
    }

    private fun applyDisplaySettings(width: Int, height: Int, dpi: Int) {
        val hadDisplay = displayId >= 0
        val oldWidth = displayWidth
        val oldHeight = displayHeight
        val oldDpi = displayDensityDpi
        displayWidth = width
        displayHeight = height
        displayDensityDpi = dpi
        cursorX = width / 2f
        cursorY = height / 2f
        val prefix = if (portraitMode) "portrait" else "landscape"
        val prefs = getSharedPreferences("standalone_gamepad_ui", Context.MODE_PRIVATE)
        prefs.edit().putInt("${prefix}_display_width", width)
            .putInt("${prefix}_display_height", height)
            .putInt("${prefix}_display_density_dpi", dpi).apply()
        surfaceView.holder.setFixedSize(width, height)
        root.requestLayout()
        if (hadDisplay) {
            if (!surfaceView.holder.surface.isValid || !shizuku.resizeVirtualDisplay(width, height, dpi, surfaceView.holder.surface)) {
                displayWidth = oldWidth
                displayHeight = oldHeight
                displayDensityDpi = oldDpi
                prefs.edit().putInt("${prefix}_display_width", oldWidth)
                    .putInt("${prefix}_display_height", oldHeight)
                    .putInt("${prefix}_display_density_dpi", oldDpi).apply()
                surfaceView.holder.setFixedSize(oldWidth, oldHeight)
                root.requestLayout()
                setStatus("Gagal mengubah display tanpa memindahkan aplikasi: ${shizuku.getLastError()}")
                return
            }
            setStatus("Display diperbarui • ID $displayId • ${width}×${height} • ${dpi} DPI")
        } else {
            setStatus("Pengaturan ${if (portraitMode) "Portrait" else "Landscape"} tersimpan: ${width}×${height} • ${dpi} DPI")
        }
    }

    private fun toggleMappingMode() {
        if (selectedGame == null) {
            Toast.makeText(this, "Pilih game dahulu agar posisi kontrol disimpan ke profil yang benar.", Toast.LENGTH_LONG).show()
            showGamePicker()
            return
        }
        controlsView.setMappingMode(!controlsView.isMappingMode)
    }

    private fun buildInfoPanel(): LinearLayout {
        fun makeInfoText(size: Float) = TextView(this).apply {
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(9), 0, dp(9), 0)
        }
        clockInfo = makeInfoText(14f)
        batteryInfo = makeInfoText(13f)
        fpsInfo = makeInfoText(20f).apply { text = "⚙"; contentDescription = "Pengaturan"; isClickable = true; setOnClickListener { showQuickSettings() } }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), 0, dp(5), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(218, 5, 18, 48))
                setStroke(dp(1), Color.argb(190, 79, 155, 255))
            }
            elevation = dp(7).toFloat()
            addView(clockInfo, LinearLayout.LayoutParams(-2, -1))
            addView(dividerInfo())
            addView(batteryInfo, LinearLayout.LayoutParams(-2, -1))
            addView(dividerInfo())
            addView(fpsInfo, LinearLayout.LayoutParams(dp(44), -1))
            setOnClickListener { showAssistantMenu(this) }
            contentDescription = "Panel informasi • jam, baterai, pengaturan"
        }
    }

    private fun dividerInfo(): View = View(this).apply {
        setBackgroundColor(Color.argb(100, 180, 210, 255))
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(20))
    }

    private fun showAssistantMenu(anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Pilih game / aplikasi")
        menu.menu.add(0, 2, 1, "Hubungkan Shizuku")
        menu.menu.add(0, 3, 2, if (displayId >= 0) "Tutup virtual display" else "Buat virtual display")
        menu.menu.add(0, 4, 3, "Jalankan aplikasi pada display")
        menu.menu.add(0, 5, 4, if (gamepadVisible) "Sembunyikan gamepad" else "Tampilkan gamepad")
        menu.menu.add(0, 6, 5, if (controlsView.isMappingMode) "Selesai atur mapping" else "Atur mapping titik sentuh")
        menu.menu.add(0, 7, 6, if (controlsView.isOverlayEditMode) "Selesai atur posisi kontrol" else "Atur posisi analog & tombol")
        menu.menu.add(0, 8, 7, "Atur ukuran kontrol")
        menu.menu.add(0, 9, 8, if (trackpadVisible) "Matikan trackpad layar penuh" else "Aktifkan trackpad layar penuh")
        menu.menu.add(0, 15, 14, if (mouseTrackpadMode) "Mode input: Mouse trackpad (ubah ke Touch)" else "Mode input: Touch langsung (ubah ke Mouse)")
        menu.menu.add(0, 10, 9, if (inputPaused) "Lanjutkan input gamepad" else "Jeda input gamepad")
        menu.menu.add(0, 12, 11, "Sensitivitas mouse")
        menu.menu.add(0, 13, 12, "Resolusi & DPI display")
        menu.menu.add(0, 14, 13, "Atur peregangan tampilan")
        menu.menu.add(0, 16, 15, "Pilih mode Landscape / Portrait")
        if (portraitMode) {
            menu.menu.add(0, 17, 16, if (portraitKeyboardVisible) "Sembunyikan keyboard Portrait" else "Tampilkan keyboard Portrait")
            menu.menu.add(0, 18, 17, if (portraitTrackpadVisible) "Sembunyikan trackpad Portrait" else "Tampilkan trackpad Portrait")
        }
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showGamePicker()
                2 -> connectShizuku()
                3 -> if (displayId >= 0) closeDisplay() else createDisplay()
                4 -> launchSelectedGame()
                5 -> toggleGamepadVisibility()
                6 -> toggleMappingMode()
                7 -> controlsView.setOverlayEditMode(!controlsView.isOverlayEditMode)
                8 -> showControlSizeSettings()
                9 -> toggleTrackpadVisibility()
                10 -> togglePause()
                12 -> showMouseSensitivitySettings()
                13 -> showDisplaySettings()
                14 -> showDisplayStretchSettings()
                15 -> toggleInputMode()
                16 -> showLayoutModePicker()
                17 -> togglePortraitKeyboardVisibility()
                18 -> togglePortraitTrackpadVisibility()
            }
            true
        }
        menu.show()
    }

    private fun toggleInputMode() {
        mouseTrackpadMode = !mouseTrackpadMode
        getSharedPreferences("standalone_gamepad_ui", Context.MODE_PRIVATE).edit()
            .putBoolean("mouse_trackpad_mode", mouseTrackpadMode).apply()
        releaseMouseDrag()
        input.releaseAll()
        controlsView.setMouseTrackpadEnabled(mouseTrackpadMode && trackpadVisible)
        setStatus(if (mouseTrackpadMode) "Mode mouse trackpad aktif • gerakkan kursor dengan jari"
            else "Mode touch langsung aktif • sentuh layar game secara langsung")
    }

    private fun sendVirtualNavigationKey(keyCode: Int) {
        val id = displayId
        if (id < 0) {
            Toast.makeText(this, "Buat virtual display dahulu.", Toast.LENGTH_SHORT).show()
            return
        }
        mouseInputExecutor.execute {
            val accepted = shizuku.injectKeyEvents(
                id,
                intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP),
                intArrayOf(keyCode, keyCode),
                intArrayOf(0, 0)
            )
            if (!accepted) runOnUiThread { setStatus("Tombol navigasi virtual gagal: ${shizuku.getLastError()}") }
        }
    }

    private fun handleTrackpadTouch(event: MotionEvent, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return true
        if (displayId < 0) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                Toast.makeText(this, "Buat virtual display dan hubungkan Shizuku dahulu.", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackpadFingerDown = true
                trackpadPointerMoved = false
                trackpadHadMultiTouch = false
                lastTrackpadX = event.x
                lastTrackpadY = event.y
                mouseLongPressTask?.let(mouseGestureHandler::removeCallbacks)
                mouseLongPressTask = Runnable {
                    if (trackpadFingerDown && !trackpadHadMultiTouch && !trackpadPointerMoved && displayId >= 0) {
                        mouseDragActive = true
                        queueMouseButton(MotionEvent.ACTION_DOWN, cursorX, cursorY)
                        setStatus("Drag mouse aktif")
                    }
                }.also { mouseGestureHandler.postDelayed(it, 500L) }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                mouseLongPressTask?.let(mouseGestureHandler::removeCallbacks)
                if (mouseDragActive) {
                    queueMouseButton(MotionEvent.ACTION_UP, cursorX, cursorY)
                    mouseDragActive = false
                }
                if (event.pointerCount >= 2) {
                    trackpadHadMultiTouch = true
                    val centroid = trackpadCentroid(event)
                    scrollCentroidX = centroid.first
                    scrollCentroidY = centroid.second
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (trackpadHadMultiTouch) {
                    if (event.pointerCount >= 2) {
                        val centroid = trackpadCentroid(event)
                        val dx = centroid.first - scrollCentroidX
                        val dy = centroid.second - scrollCentroidY
                        scrollCentroidX = centroid.first
                        scrollCentroidY = centroid.second
                        queueMouseScroll(-dx / dp(12).toFloat(), -dy / dp(12).toFloat())
                    }
                    return true
                }
                if (!trackpadFingerDown) return true
                var moved = false
                for (historyIndex in 0 until event.historySize) {
                    moved = updateTrackpadCursor(event.getHistoricalX(0, historyIndex), event.getHistoricalY(0, historyIndex), width, height) || moved
                }
                moved = updateTrackpadCursor(event.getX(0), event.getY(0), width, height) || moved
                if (moved) mouseCursorOverlay.invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                trackpadHadMultiTouch = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                trackpadFingerDown = false
                mouseLongPressTask?.let(mouseGestureHandler::removeCallbacks)
                if (mouseDragActive) {
                    queueMouseButton(MotionEvent.ACTION_UP, cursorX, cursorY)
                    mouseDragActive = false
                } else if (!trackpadHadMultiTouch && !trackpadPointerMoved) {
                    queueMouseClick(cursorX, cursorY)
                }
                trackpadPointerMoved = false
                trackpadHadMultiTouch = false
                performClickForTrackpad()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                trackpadFingerDown = false
                mouseLongPressTask?.let(mouseGestureHandler::removeCallbacks)
                if (mouseDragActive) {
                    queueMouseButton(MotionEvent.ACTION_UP, cursorX, cursorY)
                    mouseDragActive = false
                }
                trackpadPointerMoved = false
                trackpadHadMultiTouch = false
                return true
            }
        }
        return true
    }

    private fun updateTrackpadCursor(x: Float, y: Float, width: Int, height: Int): Boolean {
        val dx = x - lastTrackpadX
        val dy = y - lastTrackpadY
        lastTrackpadX = x
        lastTrackpadY = y
        val distance = kotlin.math.hypot(dx, dy)
        if (distance < 0.01f) return false
        if (distance > dp(2)) trackpadPointerMoved = true
        // Acceleration ringan: gerakan lambat presisi, sapuan cepat menjangkau layar lebih jauh.
        val speed = (distance / dp(18).toFloat()).coerceIn(0f, 1.25f)
        val gain = cursorSensitivity * (0.85f + speed * 0.55f)
        cursorX = (cursorX + dx * displayWidth / width.toFloat() * gain)
            .coerceIn(0f, (displayWidth - 1).toFloat())
        cursorY = (cursorY + dy * displayHeight / height.toFloat() * gain)
            .coerceIn(0f, (displayHeight - 1).toFloat())
        if (mouseDragActive) queueMouseButton(MotionEvent.ACTION_MOVE, cursorX, cursorY)
        else queueMouseMove(cursorX, cursorY)
        return true
    }

    private fun trackpadCentroid(event: MotionEvent): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        val count = event.pointerCount.coerceAtLeast(1)
        for (index in 0 until count) {
            x += event.getX(index)
            y += event.getY(index)
        }
        return (x / count) to (y / count)
    }

    private fun performClickForTrackpad() {
        controlsView.performClick()
    }

    private fun queueMouseMove(x: Float, y: Float) {
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
                val id = displayId
                if (id >= 0) shizuku.injectHover(id, point.first, point.second)
            }
        }
    }

    private fun queueMouseClick(x: Float, y: Float) {
        mouseInputExecutor.execute {
            val id = displayId
            val accepted = id >= 0 && shizuku.injectMouseClick(id, x, y)
            if (!accepted) runOnUiThread { setStatus("Klik mouse gagal: ${shizuku.getLastError()}") }
        }
    }

    private fun queueMouseButton(action: Int, x: Float, y: Float) {
        mouseInputExecutor.execute {
            val id = displayId
            val accepted = id >= 0 && shizuku.injectMouseButton(id, action, x, y)
            if (!accepted && action != MotionEvent.ACTION_MOVE) {
                runOnUiThread { setStatus("Input tombol mouse gagal: ${shizuku.getLastError()}") }
            }
        }
    }

    private fun queueMouseScroll(horizontal: Float, vertical: Float) {
        if (kotlin.math.abs(horizontal) < 0.001f && kotlin.math.abs(vertical) < 0.001f) return
        mouseInputExecutor.execute {
            val id = displayId
            if (id >= 0) shizuku.injectMouseScroll(id, cursorX, cursorY, horizontal, vertical)
        }
    }

    private fun showMouseSensitivitySettings() {
        val valueLabel = TextView(this).apply {
            text = "Sensitivitas ${(cursorSensitivity * 100).roundToInt()}%"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(12), dp(18), dp(2))
        }
        val slider = SeekBar(this).apply {
            max = 130
            progress = ((cursorSensitivity - 0.20f) * 100f).roundToInt().coerceIn(0, 130)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    cursorSensitivity = 0.20f + progress / 100f
                    valueLabel.text = "Sensitivitas ${(cursorSensitivity * 100).roundToInt()}%"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(valueLabel)
            addView(slider, LinearLayout.LayoutParams(-1, dp(48)).apply {
                leftMargin = dp(12); rightMargin = dp(12)
            })
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Sensitivitas kursor mouse")
            .setView(panel)
            .setPositiveButton("Simpan", null)
            .setNeutralButton("Reset") { _, _ ->
                cursorSensitivity = 0.55f
                valueLabel.text = "Sensitivitas 55%"
                slider.progress = 35
            }
            .show()
    }

    private fun releaseMouseDrag() {
        mouseLongPressTask?.let(mouseGestureHandler::removeCallbacks)
        trackpadFingerDown = false
        if (mouseDragActive && displayId >= 0 && ::shizuku.isInitialized) {
            runCatching { shizuku.injectMouseButton(displayId, MotionEvent.ACTION_UP, cursorX, cursorY) }
        }
        mouseDragActive = false
    }

    private fun toggleGamepadVisibility() {
        gamepadVisible = !gamepadVisible
        controlsView.setGamepadVisible(gamepadVisible)
        controlsView.visibility = View.VISIBLE
        if (!gamepadVisible) {
            controlsView.setOverlayEditMode(false)
            controlsView.setMappingMode(false)
            input.releaseAll()
        }
        getSharedPreferences("standalone_gamepad_ui", Context.MODE_PRIVATE).edit()
            .putBoolean("gamepad_visible", gamepadVisible).apply()
        setStatus(if (gamepadVisible) "Gamepad ditampilkan" else "Gamepad disembunyikan")
    }

    private fun togglePortraitKeyboardVisibility() {
        portraitKeyboardVisible = !portraitKeyboardVisible
        getSharedPreferences("standalone_gamepad_ui", Context.MODE_PRIVATE).edit().putBoolean("portrait_keyboard_visible", portraitKeyboardVisible).apply()
        if (::portraitKeyboardView.isInitialized) {
            portraitKeyboardView.visibility = if (portraitKeyboardVisible) View.VISIBLE else View.GONE
            root.requestLayout()
        }
        setStatus(if (portraitKeyboardVisible) "Keyboard Portrait ditampilkan" else "Keyboard Portrait disembunyikan")
    }

    private fun togglePortraitTrackpadVisibility() {
        portraitTrackpadVisible = !portraitTrackpadVisible
        getSharedPreferences("standalone_gamepad_ui", Context.MODE_PRIVATE).edit().putBoolean("portrait_trackpad_visible", portraitTrackpadVisible).apply()
        if (::portraitTrackpadView.isInitialized) {
            portraitTrackpadView.visibility = if (portraitTrackpadVisible) View.VISIBLE else View.GONE
            if (!portraitTrackpadVisible) releaseMouseDrag()
            root.requestLayout()
        }
        setStatus(if (portraitTrackpadVisible) "Trackpad Portrait ditampilkan" else "Trackpad Portrait disembunyikan")
    }

    private fun toggleTrackpadVisibility() {
        trackpadVisible = !trackpadVisible
        controlsView.setMouseTrackpadEnabled(mouseTrackpadMode && trackpadVisible)
        if (!trackpadVisible) {
            trackpadFingerDown = false
            trackpadHadMultiTouch = false
            mouseLongPressTask?.let(mouseGestureHandler::removeCallbacks)
            releaseMouseDrag()
        }
        getSharedPreferences("standalone_gamepad_ui", Context.MODE_PRIVATE).edit()
            .putBoolean("trackpad_visible", trackpadVisible).apply()
        setStatus(if (trackpadVisible) "Trackpad layar penuh aktif" else "Trackpad layar penuh nonaktif")
    }

    private fun connectShizuku() {
        shizuku.connect { connected ->
            if (connected) setStatus("Shizuku terhubung • siap membuat display")
        }
    }

    private fun createDisplay() {
        if (!surfaceView.holder.surface.isValid) {
            Toast.makeText(this, "Tunggu layar tengah selesai dimuat.", Toast.LENGTH_SHORT).show()
            return
        }
        releaseMouseDrag()
        if (displayId >= 0) {
            setStatus("Melepas sentuhan dan mengganti virtual display…")
            input.releaseAll {
                runOnUiThread {
                    shizuku.releaseVirtualDisplay()
                    displayId = -1
                    startDisplayOnSurface()
                }
            }
        } else {
            startDisplayOnSurface()
        }
    }

    private fun startDisplayOnSurface() {
        setStatus("Meminta Shizuku membuat virtual display ${displayWidth} × ${displayHeight} • ${displayDensityDpi} DPI…")
        shizuku.createVirtualDisplay(displayWidth, displayHeight, displayDensityDpi, surfaceView.holder.surface) { id, detail ->
            displayId = id
            if (id >= 0) setStatus("Display aktif • ID $id • ${displayWidth}×$displayHeight • $detail")
            else setStatus("Gagal membuat display • $detail")
        }
    }

    private fun closeDisplay() {
        releaseMouseDrag()
        setStatus("Melepas sentuhan lalu menutup virtual display…")
        input.releaseAll {
            runOnUiThread {
                shizuku.releaseVirtualDisplay()
                displayId = -1
                setStatus("Virtual display ditutup.")
            }
        }
    }

    private fun launchSelectedGame() {
        val game = selectedGame
        if (game == null) {
            showGamePicker()
            return
        }
        if (game.activityName.isBlank()) {
            Toast.makeText(this, "Aplikasi ini terpasang, tetapi tidak menyediakan aktivitas launcher yang bisa dibuka.", Toast.LENGTH_LONG).show()
            return
        }
        val id = displayId
        if (id < 0) {
            Toast.makeText(this, "Buat display terlebih dahulu dari ikon monitor.", Toast.LENGTH_LONG).show()
            return
        }
        shizuku.connect { connected ->
            if (!connected) return@connect
            Thread({
                val message = shizuku.launchAppOnDisplay(id, game.packageName, game.activityName)
                runOnUiThread {
                    setStatus(message)
                    if (message.startsWith("Game gagal", true) || message.startsWith("Peluncuran ditolak", true)) {
                        Toast.makeText(this, "Aplikasi tidak dapat dibuka pada virtual display ini.", Toast.LENGTH_LONG).show()
                    }
                }
            }, "StandaloneGameLaunch").apply { isDaemon = true; start() }
        }
    }

    private fun togglePause() {
        inputPaused = !inputPaused
        controlsView.inputEnabled = !inputPaused
        if (inputPaused) input.releaseAll()
        setStatus(if (inputPaused) "Input dijeda • semua sentuhan dilepas" else "Input aktif")
    }

    private fun releaseInputs() {
        input.releaseAll()
        setStatus("Semua sentuhan dilepas")
    }

    private fun setStatus(message: String) {
        if (::root.isInitialized) root.contentDescription = message
        else window.decorView.contentDescription = message
        if (message.contains("gagal", ignoreCase = true) || message.contains("ditolak", ignoreCase = true)) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int, strokeWidth: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(fill)
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        releaseMouseDrag()
        mouseInputExecutor.shutdownNow()
        infoHandler.removeCallbacks(infoUpdater)
        fpsExecutor.shutdownNow()
        if (::input.isInitialized) input.close()
        if (::shizuku.isInitialized) shizuku.close()
        super.onDestroy()
    }

    companion object {
        private const val ML_PACKAGE = "com.mobile.legends"
        private const val PORTRAIT_TOUCH_POINTER = 30
    }
}


private class VirtualNavigationView(
    context: Context,
    private val onNavigate: (Int) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 15, 39, 82) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(104, 174, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 18f * density
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private fun homeRect() = RectF(0f, 0f, 48f * density, 40f * density)
    private fun backRect() = RectF(56f * density, 0f, 104f * density, 40f * density)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val home = homeRect()
        val back = backRect()
        canvas.drawRoundRect(home, 9f * density, 9f * density, fill)
        canvas.drawRoundRect(back, 9f * density, 9f * density, fill)
        canvas.drawRoundRect(home, 9f * density, 9f * density, stroke)
        canvas.drawRoundRect(back, 9f * density, 9f * density, stroke)
        val baseline = 26f * density
        canvas.drawText("⌂", home.centerX(), baseline, text)
        canvas.drawText("‹", back.centerX(), baseline, text)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            when {
                homeRect().contains(event.x, event.y) -> onNavigate(KeyEvent.KEYCODE_HOME)
                backRect().contains(event.x, event.y) -> onNavigate(KeyEvent.KEYCODE_BACK)
            }
            performClick()
            return true
        }
        return event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_CANCEL
    }
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

private class StandaloneControlsView(
    context: Context,
    private val input: GamepadInputController,
    bounds: RectF,
    initialProfile: GamepadProfile,
    private val onProfileChanged: (GamepadProfile) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onMouseTouch: (MotionEvent, Int, Int) -> Boolean
) : View(context) {
    private sealed interface Binding {
        data object Stick : Binding
        data class Action(val control: GamepadControl, val startX: Float, val startY: Float, val mapped: ControlPoint) : Binding
        data class Direct(val pointerId: Int) : Binding
    }

    private val density = resources.displayMetrics.density
    private val preview = RectF(bounds)
    private val active = HashMap<Int, Binding>()
    private var profile = initialProfile
    var isMappingMode = false
        private set
    private var draggingMapping: GamepadControl? = null
    private var draggingOverlay: GamepadControl? = null
    var isOverlayEditMode = false
        private set
    private var layoutPackage = ""
    private val overlayPositions = HashMap<GamepadControl, ControlPoint>()
    private val overlayScales = HashMap<GamepadControl, Float>()
    private var stickDx = 0f
    private var stickDy = 0f
    var inputEnabled = true
        set(value) {
            field = value
            if (!value) {
                input.releaseAll()
                active.clear()
                stickDx = 0f
                stickDy = 0f
            }
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
    private val mobaButtons = listOf(
        GamepadControl.SKILL_1 to ControlPoint(0.80f, 0.75f),
        GamepadControl.SKILL_2 to ControlPoint(0.86f, 0.65f),
        GamepadControl.SKILL_3 to ControlPoint(0.92f, 0.56f),
        GamepadControl.ATTACK to ControlPoint(0.93f, 0.80f),
        GamepadControl.BATTLE_SPELL to ControlPoint(0.96f, 0.43f),
        GamepadControl.REGEN to ControlPoint(0.86f, 0.45f),
        GamepadControl.RECALL to ControlPoint(0.76f, 0.90f)
    )
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(236, 246, 255)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private var showGamepadControls = true
    private var mouseTouchActive = false
    private var mouseTrackpadEnabled = true

    fun setMouseTrackpadEnabled(enabled: Boolean) {
        mouseTrackpadEnabled = enabled
        mouseTouchActive = false
        invalidate()
    }

    fun setGamepadVisible(visible: Boolean) {
        showGamepadControls = visible
        invalidate()
    }

    fun setPreviewRect(rect: RectF) {
        preview.set(rect)
        invalidate()
    }

    fun setProfile(value: GamepadProfile) {
        profile = value
        invalidate()
    }

    fun setLayoutPackage(packageName: String) {
        layoutPackage = packageName
        val prefs = context.getSharedPreferences("saku_gamepad_overlay_layout", Context.MODE_PRIVATE)
        GamepadControl.entries.forEach { control ->
            val fallback = defaultOverlayPoint(control)
            overlayPositions[control] = ControlPoint(
                prefs.getFloat(layoutKey(control, "x"), fallback.x),
                prefs.getFloat(layoutKey(control, "y"), fallback.y)
            ).bounded()
            overlayScales[control] = prefs.getFloat(layoutKey(control, "scale"), 1f).coerceIn(0.6f, 1.6f)
        }
        invalidate()
    }

    fun controlScale(control: GamepadControl): Float = overlayScales[control] ?: 1f

    fun setControlScale(control: GamepadControl, scale: Float) {
        overlayScales[control] = scale.coerceIn(0.6f, 1.6f)
        saveOverlayLayout(control)
        invalidate()
    }

    fun setOverlayEditMode(enabled: Boolean) {
        isOverlayEditMode = enabled
        draggingOverlay = null
        active.clear()
        input.releaseAll()
        onStatus(if (enabled) "Seret analog dan tombol ke posisi yang nyaman" else "Posisi kontrol disimpan otomatis")
        invalidate()
    }

    private fun defaultOverlayPoint(control: GamepadControl): ControlPoint = when (control) {
        GamepadControl.MOVE -> ControlPoint(0.09f, 0.53f)
        GamepadControl.RECALL -> ControlPoint(0.76f, 0.90f)
        else -> mobaButtons.firstOrNull { it.first == control }?.second ?: ControlPoint(0.9f, 0.7f)
    }

    private fun layoutKey(control: GamepadControl, field: String): String =
        "$layoutPackage.${control.name.lowercase()}.$field"

    private fun saveOverlayLayout(control: GamepadControl) {
        if (layoutPackage.isBlank()) return
        val point = overlayPositions[control] ?: defaultOverlayPoint(control)
        context.getSharedPreferences("saku_gamepad_overlay_layout", Context.MODE_PRIVATE).edit()
            .putFloat(layoutKey(control, "x"), point.x)
            .putFloat(layoutKey(control, "y"), point.y)
            .putFloat(layoutKey(control, "scale"), controlScale(control))
            .apply()
    }

    fun setMappingMode(enabled: Boolean) {
        isMappingMode = enabled
        draggingMapping = null
        active.clear()
        input.releaseAll()
        onStatus(if (enabled) "Atur kontrol • seret angka/label ke tombol pada layar game" else "Mapping tersimpan")
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val alpha = if (inputEnabled) 255 else 105
        if (!showGamepadControls) return
        val stickPoint = overlayPositions[GamepadControl.MOVE] ?: defaultOverlayPoint(GamepadControl.MOVE)
        val stickX = width * stickPoint.x
        val stickY = height * stickPoint.y
        val radius = min(width * 0.075f, height * 0.12f) * controlScale(GamepadControl.MOVE)
        drawPad(canvas, stickX, stickY, radius, "", alpha)
        fill.shader = null
        fill.color = Color.argb(alpha, 4, 36, 105)
        canvas.drawCircle(stickX + stickDx, stickY + stickDy, radius * 0.36f, fill)
        outline.color = Color.argb(alpha, 78, 163, 255)
        canvas.drawCircle(stickX + stickDx, stickY + stickDy, radius * 0.36f, outline)
        val buttonRadius = min(width * 0.032f, height * 0.060f)
        mobaButtons.forEach { (control, _) ->
            val center = overlayPositions[control] ?: defaultOverlayPoint(control)
            val radius = buttonRadius * (if (control == GamepadControl.ATTACK) 1.22f else 0.88f) * controlScale(control)
            drawMobaButton(canvas, control, width * center.x, height * center.y, radius, alpha)
        }

        if (isOverlayEditMode) {
            fill.shader = null
            fill.color = Color.argb(185, 2, 8, 24)
            canvas.drawRoundRect(dp(12f), dp(76f), width - dp(12f), dp(108f), dp(10f), dp(10f), fill)
            label.textSize = dp(12f)
            canvas.drawText("MODE ATUR POSISI • SERET ANALOG / TOMBOL", width / 2f, dp(97f), label)
            overlayPositions.forEach { (control, point) ->
                val x = width * point.x
                val y = height * point.y
                outline.color = if (draggingOverlay == control) Color.WHITE else Color.argb(220, 118, 203, 255)
                outline.strokeWidth = dp(2f)
                val radius = if (control == GamepadControl.MOVE) min(width * 0.075f, height * 0.12f) * controlScale(control)
                    else buttonRadius * (if (control == GamepadControl.ATTACK) 1.22f else 0.88f) * controlScale(control)
                canvas.drawCircle(x, y, radius + dp(5f), outline)
            }
        }

        if (isMappingMode) {
            fill.shader = null
            fill.color = Color.argb(190, 2, 8, 24)
            canvas.drawRoundRect(preview.left + dp(7f), preview.top + dp(7f), preview.right - dp(7f), preview.top + dp(35f), dp(9f), dp(9f), fill)
            label.textSize = dp(11f)
            canvas.drawText("SERET ANGKA KE SKILL • SERET LABEL KE TOMBOL", preview.centerX(), preview.top + dp(25f), label)
            mappingControls().forEach { control ->
                val point = profile.pointFor(control)
                val x = preview.left + preview.width() * point.x
                val y = preview.top + preview.height() * point.y
                val selected = draggingMapping == control
                val radius = dp(if (selected) 23f else 20f)
                val baseColor = markerColor(control)
                fill.color = Color.argb(242, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                canvas.drawCircle(x, y, radius, fill)
                outline.color = if (selected) Color.WHITE else Color.argb(230, 216, 237, 255)
                outline.strokeWidth = dp(if (selected) 2.5f else 1.5f)
                canvas.drawCircle(x, y, radius, outline)
                label.textSize = dp(if (control == GamepadControl.MOVE) 8f else 11f)
                canvas.drawText(mappingLabel(control), x, y + dp(4f), label)
            }
        }
    }

    private fun drawPad(canvas: Canvas, x: Float, y: Float, radius: Float, text: String, alpha: Int) {
        fill.shader = RadialGradient(
            x - radius * 0.3f, y - radius * 0.45f, radius * 1.5f,
            intArrayOf(Color.argb(alpha, 42, 115, 255), Color.argb(alpha, 8, 32, 100), Color.argb(alpha, 3, 12, 43)),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, radius, fill)
        fill.shader = null
        outline.color = Color.argb(alpha, 68, 154, 255)
        canvas.drawCircle(x, y, radius, outline)
        if (text.isNotEmpty()) {
            label.textSize = radius * 0.76f
            canvas.drawText(text, x, y + radius * 0.28f, label)
        }
    }

    private fun drawMobaButton(canvas: Canvas, control: GamepadControl, x: Float, y: Float, radius: Float, alpha: Int) {
        val colors = when (control) {
            GamepadControl.ATTACK -> intArrayOf(Color.rgb(255, 194, 91), Color.rgb(179, 75, 32), Color.rgb(78, 27, 28))
            GamepadControl.BATTLE_SPELL -> intArrayOf(Color.rgb(120, 225, 255), Color.rgb(23, 118, 186), Color.rgb(13, 39, 100))
            GamepadControl.REGEN -> intArrayOf(Color.rgb(150, 255, 191), Color.rgb(35, 153, 110), Color.rgb(12, 57, 62))
            GamepadControl.RECALL -> intArrayOf(Color.rgb(218, 178, 255), Color.rgb(108, 75, 190), Color.rgb(41, 31, 94))
            else -> intArrayOf(Color.rgb(145, 202, 255), Color.rgb(38, 105, 215), Color.rgb(11, 30, 83))
        }
        fun withAlpha(color: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        fill.shader = RadialGradient(x - radius * 0.3f, y - radius * 0.4f, radius * 1.5f,
            intArrayOf(withAlpha(colors[0]), withAlpha(colors[1]), withAlpha(colors[2])),
            floatArrayOf(0f, 0.64f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, radius, fill)
        fill.shader = null
        outline.color = withAlpha(colors[0])
        outline.strokeWidth = dp(1.4f)
        canvas.drawCircle(x, y, radius, outline)
        val text = when (control) {
            GamepadControl.ATTACK -> "ATK"
            GamepadControl.SKILL_1 -> "S1"
            GamepadControl.SKILL_2 -> "S2"
            GamepadControl.SKILL_3 -> "ULT"
            GamepadControl.BATTLE_SPELL -> "SP"
            GamepadControl.REGEN -> "HP"
            GamepadControl.RECALL -> "RECAL"
            GamepadControl.MOVE -> ""
        }
        label.textSize = min(dp(if (control == GamepadControl.ATTACK) 12f else 10f), radius * 0.72f)
        canvas.drawText(text, x, y + radius * 0.30f, label)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isOverlayEditMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    draggingOverlay = overlayControlAt(event.x, event.y)
                    if (draggingOverlay == null) onStatus("Sentuh analog atau tombol, lalu seret")
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> draggingOverlay?.let { control ->
                    overlayPositions[control] = ControlPoint(
                        (event.x / width.coerceAtLeast(1)).coerceIn(0.04f, 0.96f),
                        (event.y / height.coerceAtLeast(1)).coerceIn(0.12f, 0.90f)
                    )
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    draggingOverlay?.let { control ->
                        saveOverlayLayout(control)
                        onStatus("${control.label} • posisi tersimpan")
                    }
                    draggingOverlay = null
                    invalidate()
                    performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    draggingOverlay = null
                    invalidate()
                }
            }
            return true
        }
        if (mouseTrackpadEnabled && (mouseTouchActive || (
                event.actionMasked == MotionEvent.ACTION_DOWN &&
                    !isMappingMode && !isOverlayEditMode && preview.contains(event.x, event.y) &&
                    (!showGamepadControls || (!isInStick(event.x, event.y) && buttonAt(event.x, event.y) == null))
            ))) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) mouseTouchActive = true
            onMouseTouch(event, width, height)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                mouseTouchActive = false
            }
            return true
        }
        if (isMappingMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    draggingMapping = mappingControlAt(event.x, event.y)
                    if (draggingMapping == null) onStatus("Sentuh indikator angka/label di dalam layar, lalu seret")
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> draggingMapping?.let { control ->
                    profile = profile.withPoint(control, normalizedInPreview(event.x, event.y))
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (draggingMapping != null) onProfileChanged(profile)
                    draggingMapping = null
                    invalidate()
                    performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    draggingMapping = null
                    invalidate()
                }
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> handleDown(event, event.actionIndex)
            MotionEvent.ACTION_MOVE -> for (index in 0 until event.pointerCount) handleMove(event, index)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                handleUp(event, event.actionIndex)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                input.releaseAll()
                active.clear()
                stickDx = 0f
                stickDy = 0f
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun handleDown(event: MotionEvent, index: Int) {
        if (!inputEnabled) return
        val id = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)
        when {
            showGamepadControls && isInStick(x, y) -> {
                if (active.values.any { it == Binding.Stick }) return
                active[id] = Binding.Stick
                updateStick(x, y)
                input.press(GamepadControl.MOVE.pointerId, movementPoint(x, y))
            }
            showGamepadControls && buttonAt(x, y) != null -> {
                val control = buttonAt(x, y) ?: return
                if (active.values.any { it is Binding.Action && it.control == control }) return
                val point = profile.pointFor(control)
                active[id] = Binding.Action(control, x, y, point)
                input.press(control.pointerId, point)
            }
            preview.contains(x, y) -> {
                val used = active.values.filterIsInstance<Binding.Direct>().map { it.pointerId }.toSet()
                val injectedId = (16..31).firstOrNull { it !in used } ?: return
                active[id] = Binding.Direct(injectedId)
                input.press(injectedId, normalizedInPreview(x, y))
            }
        }
        invalidate()
    }

    private fun handleMove(event: MotionEvent, index: Int) {
        when (val binding = active[event.getPointerId(index)]) {
            Binding.Stick -> {
                updateStick(event.getX(index), event.getY(index))
                input.move(GamepadControl.MOVE.pointerId, movementPoint(event.getX(index), event.getY(index)))
                invalidate()
            }
            is Binding.Action -> if (binding.control.isSkill()) {
                val point = ControlPoint(
                    binding.mapped.x + (event.getX(index) - binding.startX) / preview.width().coerceAtLeast(1f),
                    binding.mapped.y + (event.getY(index) - binding.startY) / preview.height().coerceAtLeast(1f)
                ).bounded()
                input.move(binding.control.pointerId, point)
            }
            is Binding.Direct -> input.move(binding.pointerId, normalizedInPreview(event.getX(index), event.getY(index)))
            null -> Unit
        }
    }

    private fun handleUp(event: MotionEvent, index: Int) {
        when (val binding = active.remove(event.getPointerId(index))) {
            Binding.Stick -> { input.release(GamepadControl.MOVE.pointerId); stickDx = 0f; stickDy = 0f }
            is Binding.Action -> input.release(binding.control.pointerId)
            is Binding.Direct -> input.release(binding.pointerId)
            null -> Unit
        }
        invalidate()
    }

    private fun overlayControlAt(x: Float, y: Float): GamepadControl? {
        val base = min(width * 0.042f, height * 0.073f)
        return GamepadControl.entries.minByOrNull { control ->
            val point = overlayPositions[control] ?: defaultOverlayPoint(control)
            hypot(x - width * point.x, y - height * point.y)
        }?.takeIf { control ->
            val point = overlayPositions[control] ?: defaultOverlayPoint(control)
            val radius = if (control == GamepadControl.MOVE) min(width * 0.09f, height * 0.15f) * controlScale(control)
                else base * (if (control == GamepadControl.ATTACK) 1.22f else 0.88f) * controlScale(control)
            hypot(x - width * point.x, y - height * point.y) <= radius + dp(18f)
        }
    }

    private fun isInStick(x: Float, y: Float): Boolean {
        val point = overlayPositions[GamepadControl.MOVE] ?: defaultOverlayPoint(GamepadControl.MOVE)
        val cx = width * point.x
        val cy = height * point.y
        val radius = min(width * 0.09f, height * 0.15f) * controlScale(GamepadControl.MOVE)
        return hypot(x - cx, y - cy) <= radius
    }

    private fun buttonAt(x: Float, y: Float): GamepadControl? {
        val base = min(width * 0.042f, height * 0.073f)
        return mobaButtons.firstOrNull { (control, _) ->
            val point = overlayPositions[control] ?: defaultOverlayPoint(control)
            val radius = base * (if (control == GamepadControl.ATTACK) 1.22f else 0.88f) * controlScale(control)
            hypot(x - width * point.x, y - height * point.y) <= radius
        }?.first
    }

    private fun mappingControls(): List<GamepadControl> = listOf(
        GamepadControl.SKILL_1, GamepadControl.SKILL_2, GamepadControl.SKILL_3,
        GamepadControl.ATTACK, GamepadControl.BATTLE_SPELL,
        GamepadControl.REGEN, GamepadControl.RECALL, GamepadControl.MOVE
    )

    private fun mappingLabel(control: GamepadControl): String = when (control) {
        GamepadControl.SKILL_1 -> "1"
        GamepadControl.SKILL_2 -> "2"
        GamepadControl.SKILL_3 -> "3"
        GamepadControl.ATTACK -> "ATK"
        GamepadControl.RECALL -> "REC"
        GamepadControl.BATTLE_SPELL -> "SP"
        GamepadControl.REGEN -> "HP"
        GamepadControl.MOVE -> "MOVE"
    }

    private fun markerColor(control: GamepadControl): Int = when (control) {
        GamepadControl.ATTACK -> Color.rgb(230, 104, 48)
        GamepadControl.RECALL -> Color.rgb(145, 94, 216)
        GamepadControl.BATTLE_SPELL -> Color.rgb(23, 151, 210)
        GamepadControl.REGEN -> Color.rgb(40, 166, 108)
        GamepadControl.MOVE -> Color.rgb(28, 116, 210)
        else -> Color.rgb(44, 112, 226)
    }

    private fun mappingControlAt(x: Float, y: Float): GamepadControl? {
        if (!preview.contains(x, y)) return null
        val hit = dp(28f)
        return mappingControls().minByOrNull { control ->
            val point = profile.pointFor(control)
            hypot(x - (preview.left + preview.width() * point.x), y - (preview.top + preview.height() * point.y))
        }?.takeIf { control ->
            val point = profile.pointFor(control)
            hypot(x - (preview.left + preview.width() * point.x), y - (preview.top + preview.height() * point.y)) <= hit
        }
    }

    private fun updateStick(x: Float, y: Float) {
        val point = overlayPositions[GamepadControl.MOVE] ?: defaultOverlayPoint(GamepadControl.MOVE)
        val cx = width * point.x
        val cy = height * point.y
        val max = min(width * 0.075f, height * 0.12f) * controlScale(GamepadControl.MOVE)
        var dx = x - cx
        var dy = y - cy
        val length = hypot(dx, dy)
        if (length > max && length > 0f) { dx *= max / length; dy *= max / length }
        stickDx = dx
        stickDy = dy
    }

    private fun movementPoint(x: Float, y: Float): ControlPoint {
        val point = overlayPositions[GamepadControl.MOVE] ?: defaultOverlayPoint(GamepadControl.MOVE)
        val cx = width * point.x
        val cy = height * point.y
        val max = (min(width * 0.075f, height * 0.12f) * controlScale(GamepadControl.MOVE)).coerceAtLeast(1f)
        var dx = (x - cx) / max
        var dy = (y - cy) / max
        val length = hypot(dx, dy)
        if (length > 1f) { dx /= length; dy /= length }
        return ControlPoint(profile.movement.x + dx * 0.075f, profile.movement.y + dy * 0.15f).bounded()
    }

    private fun normalizedInPreview(x: Float, y: Float): ControlPoint = ControlPoint(
        ((x - preview.left) / preview.width().coerceAtLeast(1f)).coerceIn(0.02f, 0.98f),
        ((y - preview.top) / preview.height().coerceAtLeast(1f)).coerceIn(0.02f, 0.98f)
    )

    private fun GamepadControl.isSkill(): Boolean =
        this == GamepadControl.SKILL_1 || this == GamepadControl.SKILL_2 ||
            this == GamepadControl.SKILL_3 || this == GamepadControl.BATTLE_SPELL

    private fun dp(value: Float): Float = value * density
}

private class NeonWaveBackground(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        paint.shader = android.graphics.LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.rgb(2, 6, 20), Color.rgb(4, 13, 43), Color.rgb(8, 8, 38)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        path.reset()
        path.moveTo(0f, h * 0.12f)
        path.cubicTo(w * 0.22f, h * 0.17f, w * 0.13f, h * 0.53f, w * 0.40f, h * 0.34f)
        path.cubicTo(w * 0.62f, h * 0.12f, w * 0.77f, h * 0.30f, w, h * 0.08f)
        path.lineTo(w, h * 0.62f)
        path.cubicTo(w * 0.76f, h * 0.46f, w * 0.67f, h * 0.59f, w * 0.50f, h * 0.61f)
        path.cubicTo(w * 0.28f, h * 0.63f, w * 0.19f, h * 0.48f, 0f, h * 0.49f)
        path.close()
        paint.shader = android.graphics.LinearGradient(0f, h * 0.15f, w, h * 0.7f,
            intArrayOf(Color.argb(180, 0, 30, 110), Color.argb(70, 9, 34, 122), Color.argb(145, 21, 25, 120)), null, Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null

        path.reset()
        path.moveTo(0f, h * 0.67f)
        path.cubicTo(w * 0.18f, h * 0.60f, w * 0.27f, h * 0.86f, w * 0.47f, h * 0.87f)
        path.cubicTo(w * 0.71f, h * 0.88f, w * 0.80f, h * 0.66f, w, h * 0.63f)
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        paint.shader = android.graphics.LinearGradient(0f, h * 0.62f, w, h,
            intArrayOf(Color.argb(225, 0, 42, 141), Color.argb(215, 4, 18, 78), Color.argb(230, 20, 10, 86)), null, Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.0022f
        paint.color = Color.argb(185, 43, 113, 255)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }
}
