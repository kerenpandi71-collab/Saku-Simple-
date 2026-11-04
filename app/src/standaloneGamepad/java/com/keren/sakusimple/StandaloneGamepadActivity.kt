package com.keren.sakusimple

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

private data class StandaloneGameEntry(val label: String, val packageName: String, val activityName: String)

/** Independent launcher and screen for the Saku Gamepad standalone APK flavor. */
class StandaloneGamepadActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var previewFrame: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var controlsView: StandaloneControlsView
    private lateinit var statusView: TextView
    private lateinit var shizuku: ShizukuBridge
    private lateinit var input: GamepadInputController

    private val previewRect = RectF()
    private val displayWidth = 1280
    private val displayHeight = 720
    private var displayId = -1
    private var selectedGame: StandaloneGameEntry? = null
    private var games: List<StandaloneGameEntry> = emptyList()
    private var profile = GamepadProfile()
    private var inputPaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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

        shizuku = ShizukuBridge(this) { message -> runOnUiThread { setStatus(message) } }
        input = GamepadInputController(
            displayId = { displayId },
            displayWidth = { displayWidth },
            displayHeight = { displayHeight },
            inject = { id, action, pointerIds, xs, ys -> shizuku.injectTouchPoints(id, action, pointerIds, xs, ys) }
        )
        buildScreen()
    }

    private fun buildScreen() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(2, 6, 19)) }
        root.addView(NeonWaveBackground(this), FrameLayout.LayoutParams(-1, -1))

        previewFrame = FrameLayout(this).apply {
            background = rounded(Color.BLACK, dp(25), Color.TRANSPARENT, dp(0))
            foreground = rounded(Color.TRANSPARENT, dp(25), Color.rgb(38, 104, 255), dp(2))
            foregroundGravity = Gravity.FILL
            clipToOutline = true
            contentDescription = "Layar game virtual 16 banding 9"
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
        root.addView(previewFrame, FrameLayout.LayoutParams(dp(320), dp(180), Gravity.CENTER))

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
            onStatus = ::setStatus
        )
        root.addView(controlsView, FrameLayout.LayoutParams(-1, -1))
        root.addView(buildTopDock(), FrameLayout.LayoutParams(-1, dp(66), Gravity.TOP).apply {
            leftMargin = dp(18); rightMargin = dp(18); topMargin = dp(10)
        })
        statusView = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.rgb(192, 218, 255))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = rounded(Color.argb(195, 5, 15, 39), dp(14), Color.argb(135, 62, 112, 226), dp(1))
        }
        root.addView(statusView, FrameLayout.LayoutParams(-1, dp(29), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(14)
        })
        setContentView(root)

        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return@addOnLayoutChangeListener
            val previewHeight = min(height * 0.57f, width * 0.57f / ASPECT).roundToInt().coerceAtLeast(dp(180))
            val previewWidth = (previewHeight * ASPECT).roundToInt()
            val previewLeft = (width - previewWidth) / 2f
            val previewTop = (height * 0.46f - previewHeight / 2f).coerceAtLeast(dp(82).toFloat())
            val params = previewFrame.layoutParams as FrameLayout.LayoutParams
            params.width = previewWidth
            params.height = previewHeight
            params.gravity = Gravity.TOP or Gravity.START
            params.leftMargin = previewLeft.roundToInt()
            params.topMargin = previewTop.roundToInt()
            previewFrame.layoutParams = params
            previewRect.set(previewLeft, previewTop, previewLeft + previewWidth, previewTop + previewHeight)
            controlsView.setPreviewRect(previewRect)
        }
        setStatus(if (selectedGame == null) "Pilih game • hubungkan Shizuku • buat display" else "${selectedGame!!.label} siap dipilih")
    }

    private fun buildTopDock(): View {
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(Color.argb(235, 5, 17, 43), dp(33), Color.argb(210, 58, 103, 225), dp(1))
            elevation = dp(8).toFloat()
        }
        addDockIcon(dock, "⚙", "Setelan") { showQuickSettings() }
        addDockSeparator(dock)
        addDockIcon(dock, "◉", "Shizuku") { connectShizuku() }
        addDockSeparator(dock)
        addDockIcon(dock, "▣", "Buat virtual display", active = true) { createDisplay() }
        addDockSeparator(dock)
        addDockIcon(dock, "⌂", "Mainkan game") { launchSelectedGame() }
        addDockSeparator(dock)
        addDockIcon(dock, "▦", "Pilih game") { showGamePicker() }
        addDockIcon(dock, "↻", "Recall") { tapRecall() }
        val spacer = View(this)
        dock.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        addDockIcon(dock, "⌁", "Status Shizuku") { connectShizuku() }
        addDockIcon(dock, "✈", "Lepas semua input") { releaseInputs() }
        addDockIcon(dock, "Ⅱ", "Jeda input") { togglePause() }
        return dock
    }

    private fun addDockIcon(parent: LinearLayout, glyph: String, description: String, active: Boolean = false, action: () -> Unit) {
        val icon = TextView(this).apply {
            text = glyph
            contentDescription = description
            textSize = 25f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(Color.rgb(237, 247, 255))
            background = if (active) rounded(Color.argb(180, 12, 58, 155), dp(22), Color.TRANSPARENT, dp(0)) else null
            setOnClickListener { action() }
            isClickable = true
            isFocusable = true
        }
        parent.addView(icon, LinearLayout.LayoutParams(dp(54), -1).apply { leftMargin = dp(2); rightMargin = dp(2) })
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
            "Pilih game",
            "Jalankan game pada display",
            "Atur mapping kontrol",
            "Tap Recall",
            if (inputPaused) "Lanjutkan input" else "Jeda input"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Saku Gamepad • kontrol")
            .setItems(entries) { _, index ->
                when (index) {
                    0 -> connectShizuku()
                    1 -> if (displayId >= 0) closeDisplay() else createDisplay()
                    2 -> showGamePicker()
                    3 -> launchSelectedGame()
                    4 -> showMappingPicker()
                    5 -> tapRecall()
                    6 -> togglePause()
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun discoverGames(): List<StandaloneGameEntry> {
        // QUERY_ALL_PACKAGES in the standalone manifest removes Android 11+ package
        // visibility filtering, so enumerate installed packages rather than only
        // apps that appear in the launcher query.
        return packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != packageName }
            .map { app ->
                val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                val activityName = launchIntent?.component?.className
                    ?: packageManager.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setPackage(app.packageName),
                        PackageManager.MATCH_DEFAULT_ONLY
                    ).firstOrNull()?.activityInfo?.name
                    ?: ""
                val label = runCatching {
                    packageManager.getApplicationLabel(app).toString()
                }.getOrDefault(app.packageName).ifBlank { app.packageName }
                StandaloneGameEntry(label, app.packageName, activityName)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()
    }

    private fun showGamePicker() {
        if (games.isEmpty()) {
            Toast.makeText(this, "Tidak ada aplikasi launcher yang terdeteksi.", Toast.LENGTH_LONG).show()
            return
        }
        val options = games.map { "${it.label}  •  ${it.packageName}" }.toTypedArray()
        val selected = games.indexOfFirst { it.packageName == selectedGame?.packageName }
        android.app.AlertDialog.Builder(this)
            .setTitle("Pilih game / aplikasi")
            .setSingleChoiceItems(options, selected) { dialog, which ->
                selectedGame = games[which]
                profile = GamepadProfile.load(this, selectedGame!!.packageName)
                controlsView.setProfile(profile)
                dialog.dismiss()
                setStatus("Dipilih • ${selectedGame!!.label}")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showMappingPicker() {
        if (selectedGame == null) {
            Toast.makeText(this, "Pilih game dahulu agar mapping tersimpan ke profil yang benar.", Toast.LENGTH_LONG).show()
            showGamePicker()
            return
        }
        val controls = GamepadControl.entries.map { it.label }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Mapping sentuh • ${selectedGame!!.label}")
            .setMessage("Pilih kontrol, lalu tap lokasi tombol target pada preview tengah. Analog dan tombol akan mengirim sentuhan Shizuku ke titik tersebut. Untuk skill arah, tahan tombol fisik XYBA, geser untuk membidik, lalu lepaskan.")
            .setItems(controls) { _, which ->
                controlsView.beginCalibration(GamepadControl.entries[which])
                setStatus("Kalibrasi ${GamepadControl.entries[which].label} • tap target di layar tengah")
            }
            .setNegativeButton("Batal", null)
            .show()
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
        setStatus("Meminta Shizuku membuat virtual display 1280 × 720…")
        shizuku.createVirtualDisplay(displayWidth, displayHeight, resources.displayMetrics.densityDpi.coerceIn(120, 640), surfaceView.holder.surface) { id, detail ->
            displayId = id
            if (id >= 0) setStatus("Display aktif • ID $id • ${displayWidth}×$displayHeight • $detail")
            else setStatus("Gagal membuat display • $detail")
        }
    }

    private fun closeDisplay() {
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

    private fun tapRecall() {
        if (inputPaused || displayId < 0) {
            Toast.makeText(this, "Hubungkan Shizuku dan buat display terlebih dahulu.", Toast.LENGTH_SHORT).show()
            return
        }
        val point = profile.pointFor(GamepadControl.RECALL)
        input.press(GamepadControl.RECALL.pointerId, point)
        input.release(GamepadControl.RECALL.pointerId)
    }

    private fun releaseInputs() {
        input.releaseAll()
        setStatus("Semua sentuhan dilepas")
    }

    private fun setStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
        else window.decorView.contentDescription = message
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int, strokeWidth: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(fill)
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        if (::input.isInitialized) input.close()
        if (::shizuku.isInitialized) shizuku.close()
        super.onDestroy()
    }

    companion object {
        private const val ML_PACKAGE = "com.mobile.legends"
        private const val ASPECT = 16f / 9f
    }
}

private class StandaloneControlsView(
    context: Context,
    private val input: GamepadInputController,
    bounds: RectF,
    initialProfile: GamepadProfile,
    private val onProfileChanged: (GamepadProfile) -> Unit,
    private val onStatus: (String) -> Unit
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
    private var calibrationTarget: GamepadControl? = null
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
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(236, 246, 255)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun setPreviewRect(rect: RectF) {
        preview.set(rect)
        invalidate()
    }

    fun setProfile(value: GamepadProfile) {
        profile = value
        invalidate()
    }

    fun beginCalibration(control: GamepadControl) {
        input.releaseAll()
        active.clear()
        calibrationTarget = control
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val alpha = if (inputEnabled) 255 else 105
        val stickX = width * 0.09f
        val stickY = height * 0.49f
        val radius = min(width * 0.075f, height * 0.12f)
        drawPad(canvas, stickX, stickY, radius, "", alpha)
        fill.shader = null
        fill.color = Color.argb(alpha, 4, 36, 105)
        canvas.drawCircle(stickX + stickDx, stickY + stickDy, radius * 0.36f, fill)
        outline.color = Color.argb(alpha, 78, 163, 255)
        canvas.drawCircle(stickX + stickDx, stickY + stickDy, radius * 0.36f, outline)

        val buttonRadius = min(width * 0.0265f, height * 0.048f)
        val controls = listOf(
            Triple("Y", width * 0.848f, height * 0.414f) to GamepadControl.SKILL_1,
            Triple("X", width * 0.800f, height * 0.489f) to GamepadControl.SKILL_2,
            Triple("B", width * 0.896f, height * 0.489f) to GamepadControl.SKILL_3,
            Triple("A", width * 0.848f, height * 0.565f) to GamepadControl.ATTACK
        )
        controls.forEach { (spec, control) ->
            drawPad(canvas, spec.second, spec.third, buttonRadius, spec.first, alpha)
        }

        calibrationTarget?.let { target ->
            fill.shader = null
            fill.color = Color.argb(230, 2, 8, 24)
            canvas.drawRoundRect(preview.left + dp(12f), preview.top + dp(12f), preview.right - dp(12f), preview.top + dp(51f), dp(12f), dp(12f), fill)
            label.textSize = dp(14f)
            canvas.drawText("MAP ${target.label.uppercase(Locale.getDefault())} • TAP TARGET", preview.centerX(), preview.top + dp(37f), label)
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (calibrationTarget != null) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (preview.contains(event.x, event.y)) {
                    val target = calibrationTarget ?: return true
                    profile = profile.withPoint(target, normalizedInPreview(event.x, event.y))
                    calibrationTarget = null
                    onProfileChanged(profile)
                    performClick()
                } else {
                    onStatus("Tap titik target di area layar tengah")
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
            isInStick(x, y) -> {
                if (active.values.any { it == Binding.Stick }) return
                active[id] = Binding.Stick
                updateStick(x, y)
                input.press(GamepadControl.MOVE.pointerId, movementPoint(x, y))
            }
            buttonAt(x, y) != null -> {
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

    private fun isInStick(x: Float, y: Float): Boolean {
        val cx = width * 0.09f
        val cy = height * 0.49f
        val radius = min(width * 0.09f, height * 0.15f)
        return hypot(x - cx, y - cy) <= radius
    }

    private fun buttonAt(x: Float, y: Float): GamepadControl? {
        val r = min(width * 0.042f, height * 0.073f)
        val buttons = listOf(
            width * 0.848f to height * 0.414f to GamepadControl.SKILL_1,
            width * 0.800f to height * 0.489f to GamepadControl.SKILL_2,
            width * 0.896f to height * 0.489f to GamepadControl.SKILL_3,
            width * 0.848f to height * 0.565f to GamepadControl.ATTACK
        )
        return buttons.firstOrNull { (point, _) -> hypot(x - point.first, y - point.second) <= r }?.second
    }

    private fun updateStick(x: Float, y: Float) {
        val cx = width * 0.09f
        val cy = height * 0.49f
        val max = min(width * 0.075f, height * 0.12f)
        var dx = x - cx
        var dy = y - cy
        val length = hypot(dx, dy)
        if (length > max && length > 0f) { dx *= max / length; dy *= max / length }
        stickDx = dx
        stickDy = dy
    }

    private fun movementPoint(x: Float, y: Float): ControlPoint {
        val cx = width * 0.09f
        val cy = height * 0.49f
        val max = min(width * 0.075f, height * 0.12f).coerceAtLeast(1f)
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
        this == GamepadControl.SKILL_1 || this == GamepadControl.SKILL_2 || this == GamepadControl.SKILL_3

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
