package com.keren.sakusimple

import android.graphics.Canvas
import android.graphics.Paint
import java.util.concurrent.Executors
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.KeyCharacterMap
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.SeekBar
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
    private var targetWidth = 1280
    private var targetHeight = 720
    private var targetDpi = 160
    private var cursorSensitivity = 0.45f
    private var touchpadHeightPercent = 36
    private lateinit var resolutionWidthInput: EditText
    private lateinit var resolutionHeightInput: EditText
    private lateinit var resolutionDpiInput: EditText
    private lateinit var touchpadSizeValue: TextView
    private lateinit var sensitivityValue: TextView
    private lateinit var touchpadSizeSeekBar: SeekBar
    private lateinit var shizukuBridge: ShizukuBridge
    private var virtualDisplayController: VirtualDisplayController? = null
    private var preview: SurfaceView? = null
    private var cursorOverlay: View? = null
    private val mouseInputExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SakuDirectMouseInput").apply { isDaemon = true }
    }
    private lateinit var runtimeLogToggle: Button
    private lateinit var bottomPanel: LinearLayout
    private lateinit var panelToggleButton: Button
    private lateinit var directTouchButton: Button
    private lateinit var previewSizeSeekBar: SeekBar
    private lateinit var panelPositionSeekBar: SeekBar
    private lateinit var previewSizeValue: TextView
    private lateinit var panelPositionValue: TextView
    private var previewSizePercent = 100
    private var panelPositionPercent = 0
    private var directTouchDown = false
    private var directTouchEnabled = true
    private val gestureHandler = Handler(Looper.getMainLooper())
    private var longPressTask: Runnable? = null
    private var touchpadFingerDown = false
    private var dragActive = false
    private lateinit var keyboardInput: EditText
    private lateinit var runtimeLogPanel: LinearLayout
    private var suppressKeyboardWatcher = false
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
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        applyImmersiveMode()

        // SurfaceView tetap memenuhi root; toolbar, kursor, touchpad, dan log adalah overlay.
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(7, 24, 45)) }
        preview = SurfaceView(this).apply {
            contentDescription = "Desktop virtual display 1280 x 720"
            setOnTouchListener { view, event ->
                if (!directTouchEnabled) return@setOnTouchListener false
                handleDirectTouch(event, view.width, view.height)
            }
            holder.setFixedSize(targetWidth, targetHeight)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    virtualDisplayController?.updateSurface(holder.surface)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    virtualDisplayController?.updateSurface(holder.surface)
                    cursorOverlay?.invalidate()
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
        }
        root.addView(preview, FrameLayout.LayoutParams(-1, dp(300), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        cursorOverlay = object : View(this) {
            override fun onDraw(canvas: Canvas) { super.onDraw(canvas); drawDextopCursor(canvas) }
        }.apply {
            isClickable = false
            isFocusable = false
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Kursor putih Saku Desktop"
        }
        root.addView(cursorOverlay, FrameLayout.LayoutParams(-1, -1))

        shizukuBridge = ShizukuBridge(this) { message ->
            runOnUiThread {
                if (::status.isInitialized) status.text = message
                appendRuntimeLog(message)
            }
        }

        fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 9f
            isAllCaps = false
            minHeight = dp(32); minimumHeight = dp(32)
            minWidth = 0; minimumWidth = 0
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener { action() }
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = roundedPanel(220, 12)
        }
        topBar.addView(TextView(this).apply {
            text = "SAKU DESKTOP  •  1280 × 720"
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-1, dp(20)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(actionButton("Hubungkan") { shizukuBridge.connect() }, LinearLayout.LayoutParams(0, dp(34), 1.2f))
        actions.addView(actionButton("Mulai") { startOwnedVirtualDisplay() }, LinearLayout.LayoutParams(0, dp(34), 0.75f))
        actions.addView(actionButton("Cek") {
            val id = virtualDisplayController?.displayId ?: -1
            if (id < 0) status.text = "Mulai virtual display dahulu." else Thread({
                val detail = shizukuBridge.getDisplayDiagnostics(id)
                runOnUiThread {
                    appendRuntimeLog("Diagnostik display $id\n$detail")
                    status.text = "Diagnostik selesai • detail di Log"
                }
            }, "SakuDisplayDiagnostics").start()
        }, LinearLayout.LayoutParams(0, dp(34), 0.5f))
        actions.addView(actionButton("Tutup") {
            virtualDisplayController?.close()
            virtualDisplayController = null
            clearPreview()
            status.text = "Virtual display ditutup."
        }, LinearLayout.LayoutParams(0, dp(34), 0.65f))
        runtimeLogToggle = actionButton("Log") { toggleRuntimeLog() }
        actions.addView(runtimeLogToggle, LinearLayout.LayoutParams(0, dp(34), 0.48f))
        panelToggleButton = actionButton("Panel") { toggleInputPanel() }
        actions.addView(panelToggleButton, LinearLayout.LayoutParams(0, dp(34), 0.58f))
        directTouchButton = actionButton("Touch: ON") { toggleDirectTouch() }
        actions.addView(directTouchButton, LinearLayout.LayoutParams(0, dp(34), 0.78f))
        topBar.addView(actions)
        val previewSizeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        previewSizeValue = TextView(this).apply { text = "Desktop 100%"; textSize = 9f; setTextColor(Color.WHITE) }
        previewSizeRow.addView(previewSizeValue, LinearLayout.LayoutParams(dp(84), dp(26)))
        previewSizeSeekBar = SeekBar(this).apply {
            max = 50
            progress = 50
            contentDescription = "Ukuran preview desktop 50 sampai 100 persen"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    previewSizePercent = 50 + progress
                    previewSizeValue.text = "Desktop ${previewSizePercent}%"
                    updateDesktopLayout(root.width, root.height)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        previewSizeRow.addView(previewSizeSeekBar, LinearLayout.LayoutParams(0, dp(28), 1f))
        topBar.addView(previewSizeRow)
        val panelPositionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        panelPositionValue = TextView(this).apply { text = "Panel 0%"; textSize = 9f; setTextColor(Color.WHITE) }
        panelPositionRow.addView(panelPositionValue, LinearLayout.LayoutParams(dp(84), dp(26)))
        panelPositionSeekBar = SeekBar(this).apply {
            max = 100
            progress = 0
            contentDescription = "Posisi panel touchpad"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    panelPositionPercent = progress
                    panelPositionValue.text = "Panel ${panelPositionPercent}%"
                    updateDesktopLayout(root.width, root.height)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        panelPositionRow.addView(panelPositionSeekBar, LinearLayout.LayoutParams(0, dp(28), 1f))
        topBar.addView(panelPositionRow)
        val resolutionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun resolutionInput(initial: String, hintText: String, widthDp: Int) = EditText(this).apply {
            setText(initial); hint = hintText; textSize = 10f; setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dp(4), 0, dp(4), 0)
            background = roundedPanel(180, 6)
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(32)).apply { rightMargin = dp(3) }
        }
        resolutionWidthInput = resolutionInput("1280", "Lebar", 62)
        resolutionHeightInput = resolutionInput("720", "Tinggi", 58)
        resolutionDpiInput = resolutionInput("160", "DPI", 48)
        resolutionRow.addView(resolutionWidthInput)
        resolutionRow.addView(resolutionHeightInput)
        resolutionRow.addView(resolutionDpiInput)
        resolutionRow.addView(actionButton("Terapkan") { applyDisplaySettings() },
            LinearLayout.LayoutParams(0, dp(32), 1f))
        topBar.addView(resolutionRow)
        val controlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        touchpadSizeValue = TextView(this).apply { text = "Area 36%"; textSize = 9f; setTextColor(Color.WHITE) }
        controlRow.addView(touchpadSizeValue, LinearLayout.LayoutParams(dp(66), dp(25)))
        touchpadSizeSeekBar = SeekBar(this).apply {
            max = 30; progress = 16
            contentDescription = "Ukuran area touchpad"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    touchpadHeightPercent = 20 + progress
                    touchpadSizeValue.text = "Area ${touchpadHeightPercent}%"
                    updateDesktopLayout(root.width, root.height)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        controlRow.addView(touchpadSizeSeekBar, LinearLayout.LayoutParams(0, dp(25), 1f))
        sensitivityValue = TextView(this).apply { text = "Kursor 45%"; textSize = 9f; setTextColor(Color.WHITE) }
        controlRow.addView(sensitivityValue, LinearLayout.LayoutParams(dp(76), dp(25)))
        val sensitivitySeekBar = SeekBar(this).apply {
            max = 80; progress = 25
            contentDescription = "Sensitivitas kursor"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    cursorSensitivity = (20 + progress) / 100f
                    sensitivityValue.text = "Kursor ${(cursorSensitivity * 100).toInt()}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        controlRow.addView(sensitivitySeekBar, LinearLayout.LayoutParams(0, dp(25), 1f))
        topBar.addView(controlRow)
        status = TextView(this).apply {
            text = "Hubungkan Shizuku, lalu tekan Mulai."
            textSize = 9f
            maxLines = 2
            setTextColor(Color.rgb(105, 221, 245))
            setPadding(dp(2), dp(2), dp(2), 0)
        }
        topBar.addView(status, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Semua pengaturan berada di panel yang dibuka dari bola asisten.
        topBar.visibility = View.GONE
        root.addView(topBar, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = dp(7); rightMargin = dp(7); topMargin = dp(7)
        })
        val assistantBall = TextView(this).apply {
            text = "S"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(225, 24, 104, 164))
                setStroke(dp(2), Color.argb(220, 180, 230, 255))
            }
            elevation = dp(12).toFloat()
            contentDescription = "Bola asisten Saku Desktop. Ketuk untuk pengaturan, seret untuk memindahkan."
            isClickable = true
        }
        val ballParams = FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(12)
        }
        var ballDownX = 0f
        var ballDownY = 0f
        var ballStartX = 0f
        var ballStartY = 0f
        var ballDragged = false
        assistantBall.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ballDownX = event.rawX; ballDownY = event.rawY
                    ballStartX = ballParams.x.toFloat(); ballStartY = ballParams.y.toFloat()
                    ballDragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - ballDownX
                    val dy = event.rawY - ballDownY
                    if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) ballDragged = true
                    if (ballDragged) {
                        ballParams.gravity = Gravity.TOP or Gravity.START
                        ballParams.leftMargin = (ballStartX + dx).toInt().coerceIn(0, (root.width - view.width).coerceAtLeast(0))
                        ballParams.topMargin = (ballStartY + dy).toInt().coerceIn(0, (root.height - view.height).coerceAtLeast(0))
                        root.updateViewLayout(view, ballParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!ballDragged) {
                        topBar.visibility = if (topBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        root.addView(assistantBall, ballParams)

        bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(4), dp(7), dp(7))
            background = roundedPanel(250, 15)
            elevation = dp(8).toFloat()
        }
        val panelHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        panelHeader.addView(TextView(this).apply {
            text = "TOUCHPAD • 1 jari kursor / tap klik / 2 jari scroll"
            textSize = 9f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }, LinearLayout.LayoutParams(0, dp(30), 1f))
        panelHeader.addView(actionButton("⌨ Keyboard") { showVirtualKeyboard() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)))
        panelHeader.addView(actionButton("Sembunyi") { toggleInputPanel() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)))
        bottomPanel.addView(panelHeader)
        val virtualNavRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        virtualNavRow.addView(actionButton("◀ Back") { sendVirtualNavigationKey(android.view.KeyEvent.KEYCODE_BACK) },
            LinearLayout.LayoutParams(0, dp(30), 1f))
        virtualNavRow.addView(actionButton("⌂ Home") { sendVirtualNavigationKey(android.view.KeyEvent.KEYCODE_HOME) },
            LinearLayout.LayoutParams(0, dp(30), 1f))
        virtualNavRow.addView(actionButton("▢ Recent") { sendVirtualNavigationKey(android.view.KeyEvent.KEYCODE_APP_SWITCH) },
            LinearLayout.LayoutParams(0, dp(30), 1f))
        bottomPanel.addView(virtualNavRow)
        val stage = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(19, 39, 56))
            contentDescription = "Touchpad: gerak 1 jari, tap klik, 2 jari scroll, tahan 1 detik lalu geser untuk drag"
        }
        stage.setOnTouchListener { _, event -> handleTouch(event, stage.width, stage.height) }
        bottomPanel.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomPanel, FrameLayout.LayoutParams(-1, dp(228), Gravity.TOP).apply {
            leftMargin = dp(7); rightMargin = dp(7); topMargin = dp(300)
        })
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            updateDesktopLayout(right - left, bottom - top)
        }

        runtimeLog = TextView(this).apply {
            textSize = 9f
            setTextColor(Color.rgb(190, 236, 249))
            setTextIsSelectable(true)
            text = "Runtime log siap. Hasil Shizuku dan error muncul di sini."
            setPadding(dp(7), dp(5), dp(7), dp(5))
            setOnLongClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Saku Simple runtime log", runtimeLogBuffer.toString()))
                appendRuntimeLog("Log disalin ke clipboard.")
                true
            }
        }
        runtimeLogScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(runtimeLog, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        runtimeLogPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
            background = roundedPanel(245, 12)
            elevation = dp(12).toFloat()
            visibility = View.GONE
        }
        val logHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        logHeader.addView(TextView(this).apply {
            text = "RUNTIME LOG • tahan isi untuk salin"
            textSize = 10f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(28), 1f))
        logHeader.addView(actionButton("Tutup") { toggleRuntimeLog(false) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
        runtimeLogPanel.addView(logHeader)
        runtimeLogPanel.addView(runtimeLogScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(runtimeLogPanel, FrameLayout.LayoutParams(-1, dp(185), Gravity.TOP).apply {
            leftMargin = dp(8); rightMargin = dp(8); topMargin = dp(102)
        })

        // Text sink untuk IME Android; huruf diubah jadi KeyCharacterMap events lalu dikirim ke Shizuku.
        keyboardInput = EditText(this).apply {
            setSingleLine(true)
            textSize = 12f
            hint = "Ketik ke layar virtual"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(dp(8), 0, dp(8), 0)
            background = roundedPanel(230, 10)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            alpha = 0.02f
            isFocusableInTouchMode = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (suppressKeyboardWatcher || s == null) return
                    if (count > 0) {
                        val typed = s.subSequence(start, (start + count).coerceAtMost(s.length)).toString()
                        if (typed.isNotEmpty()) sendTypedText(typed)
                    } else if (before > 0) {
                        sendVirtualKey(android.view.KeyEvent.KEYCODE_DEL)
                    }
                    // Do not clear EditText after every character: resetting the composing buffer
                    // causes several IMEs to dismiss/reopen the keyboard while typing.
                    if (keyboardInput.text.length > 256) {
                        post {
                            suppressKeyboardWatcher = true
                            keyboardInput.setText("")
                            suppressKeyboardWatcher = false
                        }
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        root.addView(keyboardInput, FrameLayout.LayoutParams(dp(2), dp(2), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
    }

    private fun roundedPanel(alpha: Int, radius: Int) = GradientDrawable().apply {
        setColor(Color.argb(alpha.coerceIn(0, 255), 7, 25, 45))
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), Color.argb(105, 182, 225, 247))
    }

    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun sendVirtualNavigationKey(keyCode: Int) {
        val displayId = virtualDisplayController?.displayId ?: -1
        if (displayId < 0) {
            status.text = "Mulai virtual display dahulu."
            return
        }
        sendVirtualKey(keyCode)
    }

    private fun sendVirtualKey(keyCode: Int) {
        val displayId = virtualDisplayController?.displayId ?: -1
        if (displayId < 0) {
            status.text = "Virtual display belum aktif."
            return
        }
        mouseInputExecutor.execute {
            val accepted = virtualDisplayController?.injectKeyEvents(
                intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP),
                intArrayOf(keyCode, keyCode),
                intArrayOf(0, 0)
            ) ?: false
            runOnUiThread {
                if (!accepted) {
                    status.text = "Tombol tidak diterima display virtual."
                    appendRuntimeLog("KeyEvent keyCode=$keyCode gagal: ${shizukuBridge.getLastError()}")
                } else {
                    status.text = "Tombol dikirim ke display virtual • keyCode=$keyCode"
                }
            }
        }
    }

    private fun toggleDirectTouch() {
        directTouchEnabled = !directTouchEnabled
        directTouchButton.text = if (directTouchEnabled) "Touch: ON" else "Touch: OFF"
        preview?.isClickable = directTouchEnabled
        status.text = if (directTouchEnabled) {
            bottomPanel.visibility = View.GONE
            panelToggleButton.text = "Panel+"
            "Direct Touch aktif • sentuh dan geser desktop seperti layar sentuh."
        } else {
            bottomPanel.visibility = View.VISIBLE
            panelToggleButton.text = "Panel"
            updateDesktopLayout((preview?.parent as? View)?.width ?: 0, (preview?.parent as? View)?.height ?: 0)
            "Touchpad aktif • 1 jari kursor, tap layar, 2 jari scroll."
        }
    }

    private fun handleDirectTouch(event: MotionEvent, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return true
        if (event.pointerCount > 5) {
            status.text = "Direct Touch mendukung maksimal 5 jari."
            return true
        }
        val count = event.pointerCount
        val xs = FloatArray(count) { i ->
            (event.getX(i) / width.toFloat() * targetWidth).coerceIn(0f, (targetWidth - 1).toFloat())
        }
        val ys = FloatArray(count) { i ->
            (event.getY(i) / height.toFloat() * targetHeight).coerceIn(0f, (targetHeight - 1).toFloat())
        }
        cursorX = xs[0]
        cursorY = ys[0]
        cursorOverlay?.invalidate()
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_DOWN
            MotionEvent.ACTION_POINTER_DOWN -> MotionEvent.ACTION_POINTER_DOWN or
                (event.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            MotionEvent.ACTION_MOVE -> MotionEvent.ACTION_MOVE
            MotionEvent.ACTION_POINTER_UP -> MotionEvent.ACTION_POINTER_UP or
                (event.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            MotionEvent.ACTION_UP -> MotionEvent.ACTION_UP
            MotionEvent.ACTION_CANCEL -> MotionEvent.ACTION_CANCEL
            else -> return true
        }
        val controller = virtualDisplayController
        if (controller == null || controller.displayId < 0) {
            if (action == MotionEvent.ACTION_DOWN) status.text = "Mulai virtual display melalui Shizuku dahulu."
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) directTouchDown = true
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) directTouchDown = false
        val pointerIds = IntArray(count) { i -> event.getPointerId(i) }
        val isFinalUp = event.actionMasked == MotionEvent.ACTION_UP
        mouseInputExecutor.execute {
            val accepted = controller.injectTouchPoints(action, pointerIds, xs, ys)
            runOnUiThread {
                if (!accepted) {
                    appendRuntimeLog("Direct Touch multi-jari gagal • action=$action • ${shizukuBridge.getLastError()}")
                    status.text = "Direct Touch gagal • periksa Log."
                } else if (isFinalUp) {
                    pulseDextopCursor()
                }
            }
        }
        return true
    }

    private fun toggleInputPanel() {
        val show = bottomPanel.visibility != View.VISIBLE
        bottomPanel.visibility = if (show) View.VISIBLE else View.GONE
        panelToggleButton.text = if (show) "Panel" else "Panel+"
        if (!show) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(keyboardInput.windowToken, 0)
        }
    }

    private fun showVirtualKeyboard() {
        keyboardInput.requestFocus()
        keyboardInput.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(keyboardInput, InputMethodManager.SHOW_IMPLICIT)
        }
        status.text = "Keyboard Android aktif • teks diteruskan ke display virtual."
    }

    private fun sendTypedText(text: String) {
        val events = runCatching {
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
        }.getOrNull()
        if (events.isNullOrEmpty()) {
            appendRuntimeLog("Keyboard: karakter tidak didukung KeyCharacterMap: ${text.take(16)}")
            return
        }
        val actions = IntArray(events.size) { events[it].action }
        val codes = IntArray(events.size) { events[it].keyCode }
        val meta = IntArray(events.size) { events[it].metaState }
        mouseInputExecutor.execute {
            val accepted = virtualDisplayController?.injectKeyEvents(actions, codes, meta) ?: false
            if (!accepted) runOnUiThread {
                status.text = "Teks keyboard gagal dikirim. Periksa Log."
                appendRuntimeLog("Injeksi key event gagal: ${shizukuBridge.getLastError()}")
            }
        }
    }

    private fun toggleRuntimeLog(forceShow: Boolean? = null) {
        val show = forceShow ?: (runtimeLogPanel.visibility != View.VISIBLE)
        runtimeLogPanel.visibility = if (show) View.VISIBLE else View.GONE
        runtimeLogToggle.text = if (show) "Log−" else "Log"
    }

    private fun queueNativeMouseButton(action: Int, x: Float, y: Float) {
        mouseInputExecutor.execute {
            val accepted = virtualDisplayController?.injectMouseButton(action, x, y) ?: false
            if (!accepted && action != MotionEvent.ACTION_MOVE) runOnUiThread {
                appendRuntimeLog("Mouse button gagal: ${shizukuBridge.getLastError()}")
            }
        }
    }

    private fun queueNativeMouseClick(x: Float, y: Float) {
        mouseInputExecutor.execute {
            val accepted = virtualDisplayController?.injectTouchClick(x, y) ?: false
            runOnUiThread {
                if (accepted) {
                    pulseDextopCursor()
                    status.text = "Tap layar • x=${x.toInt()} y=${y.toInt()}"
                } else {
                    status.text = "Klik ditolak InputManager. Periksa Log."
                    appendRuntimeLog("Klik gagal: ${shizukuBridge.getLastError()}")
                }
            }
        }
    }

    override fun onDestroy() {
        longPressTask?.let(gestureHandler::removeCallbacks)
        if (dragActive) virtualDisplayController?.injectMouseButton(MotionEvent.ACTION_UP, cursorX, cursorY)
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
        status.text = "Membuat display langsung ke SurfaceView • ${targetWidth}×${targetHeight} • densityDpi=${targetDpi}…"
        appendRuntimeLog("Output langsung ke SurfaceView • ${targetWidth}×${targetHeight} • ${targetDpi}dpi; ImageReader/Bitmap copy tidak digunakan.")
        preview?.holder?.setFixedSize(targetWidth, targetHeight)
        virtualDisplayController?.start(
            width = targetWidth,
            height = targetHeight,
            densityDpi = targetDpi,
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
                touchpadFingerDown = true
                lastTouchX = event.x
                lastTouchY = event.y
                downX = event.x
                downY = event.y
                pointerMoved = false
                hadMultiTouch = false
                scrollGestureActive = false
                longPressTask?.let(gestureHandler::removeCallbacks)
                longPressTask = Runnable {
                    if (touchpadFingerDown && !hadMultiTouch && !pointerMoved &&
                        (virtualDisplayController?.displayId ?: -1) >= 0
                    ) {
                        dragActive = true
                        queueNativeMouseButton(MotionEvent.ACTION_DOWN, cursorX, cursorY)
                        status.text = "Drag aktif • geser jari lalu lepaskan."
                    }
                }.also { gestureHandler.postDelayed(it, 500L) }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                longPressTask?.let(gestureHandler::removeCallbacks)
                if (dragActive) {
                    queueNativeMouseButton(MotionEvent.ACTION_UP, cursorX, cursorY)
                    dragActive = false
                }
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
                            cursorX, cursorY,
                            horizontal = -dx / dp(12).toFloat(),
                            vertical = -dy / dp(12).toFloat()
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
                    // Do not cancel: timer decides whether the hold was stationary long enough.
                }
                cursorX = (cursorX + deltaX * targetWidth / width.toFloat() * cursorSensitivity)
                    .coerceIn(0f, (targetWidth - 1).toFloat())
                cursorY = (cursorY + deltaY * targetHeight / height.toFloat() * cursorSensitivity)
                    .coerceIn(0f, (targetHeight - 1).toFloat())
                lastTouchX = x
                lastTouchY = y
                cursorOverlay?.invalidate()
                if (dragActive) queueNativeMouseButton(MotionEvent.ACTION_MOVE, cursorX, cursorY)
                else queueNativeMouseMove(cursorX, cursorY)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                hadMultiTouch = true
                scrollGestureActive = event.pointerCount > 2
                return true
            }

            MotionEvent.ACTION_UP -> {
                touchpadFingerDown = false
                longPressTask?.let(gestureHandler::removeCallbacks)
                if (dragActive) {
                    queueNativeMouseButton(MotionEvent.ACTION_UP, cursorX, cursorY)
                    dragActive = false
                } else if (!hadMultiTouch && !pointerMoved) {
                    val controller = virtualDisplayController
                    if (controller == null || controller.displayId < 0) {
                        status.text = "Buat virtual display lewat Shizuku sebelum mengklik."
                    } else {
                        val clickX = cursorX.coerceIn(0f, (targetWidth - 1).toFloat())
                        val clickY = cursorY.coerceIn(0f, (targetHeight - 1).toFloat())
                        mouseInputExecutor.execute {
                            val clicked = controller.injectTouchClick(clickX, clickY)
                            runOnUiThread {
                                if (clicked) {
                                    pulseDextopCursor()
                                    status.text = "Tap layar • x=${clickX.toInt()} y=${clickY.toInt()}"
                                } else {
                                    status.text = "Klik mouse ditolak InputManager. Periksa Runtime Log."
                                    appendRuntimeLog("Injeksi tap layar gagal di x=${clickX.toInt()} y=${clickY.toInt()}: ${shizukuBridge.getLastError()}")
                                }
                            }
                        }
                    }
                }
                pointerMoved = false
                hadMultiTouch = false
                scrollGestureActive = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                touchpadFingerDown = false
                longPressTask?.let(gestureHandler::removeCallbacks)
                if (dragActive) {
                    queueNativeMouseButton(MotionEvent.ACTION_UP, cursorX, cursorY)
                    dragActive = false
                }
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

    private fun applyDisplaySettings() {
        val width = resolutionWidthInput.text.toString().toIntOrNull()
        val height = resolutionHeightInput.text.toString().toIntOrNull()
        val dpi = resolutionDpiInput.text.toString().toIntOrNull()
        if (width == null || height == null || dpi == null ||
            width !in 320..2560 || height !in 320..2560 || dpi !in 120..640
        ) {
            status.text = "Resolusi harus 320–2560 px dan DPI 120–640."
            return
        }
        targetWidth = width
        targetHeight = height
        targetDpi = dpi
        cursorX = cursorX.coerceIn(0f, (targetWidth - 1).toFloat())
        cursorY = cursorY.coerceIn(0f, (targetHeight - 1).toFloat())
        preview?.contentDescription = "Desktop virtual display ${targetWidth} x ${targetHeight} • ${targetDpi} DPI"
        preview?.holder?.setFixedSize(targetWidth, targetHeight)
        if ((virtualDisplayController?.displayId ?: -1) >= 0) {
            startOwnedVirtualDisplay()
        } else {
            status.text = "Pengaturan disimpan: ${targetWidth}×${targetHeight} • ${targetDpi} DPI. Tekan Mulai."
        }
        updateDesktopLayout((preview?.parent as? View)?.width ?: 0, (preview?.parent as? View)?.height ?: 0)
    }

    private fun updateDesktopLayout(rootWidth: Int, rootHeight: Int) {
        if (rootWidth <= 0 || rootHeight <= 0 || !::bottomPanel.isInitialized) return
        val desktopWidth = (rootWidth * previewSizePercent / 100).coerceIn(dp(180), rootWidth)
        val desktopHeight = (desktopWidth * targetHeight.toFloat() / targetWidth).toInt().coerceAtLeast(dp(90))
        val previewParams = preview?.layoutParams as? FrameLayout.LayoutParams
        if (previewParams != null &&
            (previewParams.width != desktopWidth || previewParams.height != desktopHeight ||
                previewParams.gravity != (Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        ) {
            previewParams.width = desktopWidth
            previewParams.height = desktopHeight
            previewParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            preview?.layoutParams = previewParams
        }
        val panelParams = bottomPanel.layoutParams as? FrameLayout.LayoutParams
        if (panelParams != null) {
            val dockHeight = (topBarHeight()).coerceAtLeast(dp(150))
            val panelHeight = (rootHeight * touchpadHeightPercent / 100).coerceIn(dp(170), (rootHeight * 0.52f).toInt().coerceAtLeast(dp(170)))
            val minTop = desktopHeight + dp(8)
            val maxTop = (rootHeight - dockHeight - panelHeight - dp(8)).coerceAtLeast(minTop)
            val desiredTop = minTop + ((maxTop - minTop) * panelPositionPercent / 100)
            if (panelParams.topMargin != desiredTop || panelParams.height != panelHeight) {
                panelParams.topMargin = desiredTop
                panelParams.height = panelHeight
                bottomPanel.layoutParams = panelParams
            }
        }
    }

    private fun topBarHeight(): Int {
        val parent = status.parent as? View
        return parent?.height ?: dp(170)
    }

    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else dp(24)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
