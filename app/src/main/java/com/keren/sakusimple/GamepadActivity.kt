package com.keren.sakusimple

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.util.Locale
import kotlin.math.hypot

private data class GameEntry(val label: String, val packageName: String, val activityName: String)

/** Landscape MOBA controller with a direct SurfaceView virtual-display preview. */
class GamepadActivity : ComponentActivity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: GamepadOverlayView
    private lateinit var shizukuBridge: ShizukuBridge
    private lateinit var displayController: VirtualDisplayController
    private lateinit var inputController: GamepadInputController
    private lateinit var statusView: TextView
    private lateinit var gameButton: Button
    private lateinit var pauseButton: Button
    private var selectedGame: GameEntry? = null
    private var installedGames: List<GameEntry> = emptyList()
    private var profile = GamepadProfile()
    private var inputPaused = false
    private val displayWidth = 1280
    private val displayHeight = 720

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.rgb(5, 13, 25)))
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )

        installedGames = loadLaunchableGames()
        selectedGame = installedGames.firstOrNull {
            it.packageName.contains("mobile.legends", ignoreCase = true) || it.label.contains("Mobile Legends", ignoreCase = true)
        } ?: installedGames.firstOrNull()
        profile = GamepadProfile.load(this, selectedGame?.packageName.orEmpty())

        shizukuBridge = ShizukuBridge(this) { message ->
            runOnUiThread { setStatus(message) }
        }
        displayController = VirtualDisplayController(this, shizukuBridge) { message ->
            runOnUiThread { setStatus(message) }
        }
        inputController = GamepadInputController(
            displayId = { displayController.displayId },
            displayWidth = { displayWidth },
            displayHeight = { displayHeight },
            inject = { id, action, pointerIds, xs, ys ->
                shizukuBridge.injectTouchPoints(id, action, pointerIds, xs, ys)
            }
        )
        buildScreen()
    }

    private fun buildScreen() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(5, 13, 25))
            keepScreenOn = true
        }
        val previewFrame = FrameLayout(this).apply {
            background = rounded(Color.rgb(8, 20, 35), 18, Color.rgb(51, 93, 130))
            clipToOutline = true
        }
        surfaceView = SurfaceView(this).apply {
            contentDescription = "Pratinjau game pada virtual display di tengah layar"
            holder.setFixedSize(displayWidth, displayHeight)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    setStatus("Layar siap • hubungkan Shizuku lalu buat virtual display.")
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    if (displayController.displayId >= 0) displayController.updateSurface(holder.surface)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
        }
        previewFrame.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        overlay = GamepadOverlayView(this, inputController, profile) { updated ->
            profile = updated
            selectedGame?.let { profile.save(this, it.packageName) }
            overlay.setProfile(updated)
        }
        overlay.setProfile(profile)
        previewFrame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(previewFrame, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = dp(10)
            rightMargin = dp(10)
            topMargin = dp(76)
            bottomMargin = dp(65)
        })

        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(4))
            background = rounded(Color.argb(236, 9, 24, 42), 14, Color.argb(170, 78, 147, 188))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        gameButton = makeButton("Pilih game") { showGamePicker() }
        addWeighted(controls, gameButton, 1.35f)
        addWeighted(controls, makeButton("Shizuku") { shizukuBridge.connect() }, 0.9f)
        addWeighted(controls, makeButton("Display") { createVirtualDisplay() }, 0.82f)
        addWeighted(controls, makeButton("Mainkan") { launchSelectedGame() }, 0.82f)
        pauseButton = makeButton("Jeda") { togglePause() }
        addWeighted(controls, pauseButton, 0.66f)
        addWeighted(controls, makeButton("Touchpad") { openTouchpad() }, 0.78f)
        topPanel.addView(controls, LinearLayout.LayoutParams(-1, dp(38)))
        statusView = TextView(this).apply {
            text = "Memuat game…"
            setTextColor(Color.rgb(188, 214, 237))
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(1), dp(3), 0)
        }
        topPanel.addView(statusView, LinearLayout.LayoutParams(-1, dp(20)))
        root.addView(topPanel, FrameLayout.LayoutParams(-1, dp(68), Gravity.TOP).apply {
            leftMargin = dp(10)
            rightMargin = dp(10)
            topMargin = dp(4)
        })

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = rounded(Color.argb(228, 9, 24, 42), 14, Color.argb(150, 78, 147, 188))
        }
        addWeighted(bottomPanel, makeButton("Atur kontrol") { showCalibrationPicker() }, 1.0f)
        val hint = TextView(this).apply {
            text = "Geser analog • tekan tombol • tap layar = direct touch"
            setTextColor(Color.rgb(174, 203, 228))
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bottomPanel.addView(hint, LinearLayout.LayoutParams(0, dp(34), 1.8f))
        root.addView(bottomPanel, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM).apply {
            leftMargin = dp(10)
            rightMargin = dp(10)
            bottomMargin = dp(5)
        })

        setContentView(root)
        updateGameButton()
        if (installedGames.isEmpty()) setStatus("Tidak menemukan game yang bisa dijalankan di perangkat ini.")
        else setStatus("Pilih game, hubungkan Shizuku, lalu buat virtual display.")
    }

    private fun loadLaunchableGames(): List<GameEntry> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcher, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                if (activity.packageName == packageName) return@mapNotNull null
                GameEntry(
                    info.loadLabel(packageManager)?.toString()?.ifBlank { activity.packageName } ?: activity.packageName,
                    activity.packageName,
                    activity.name
                )
            }
            .distinctBy { "${it.packageName}/${it.activityName}" }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun showGamePicker() {
        if (installedGames.isEmpty()) {
            Toast.makeText(this, "Tidak ada aplikasi game yang ditemukan.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = installedGames.map { "${it.label}  ·  ${it.packageName}" }.toTypedArray()
        val selectedIndex = installedGames.indexOfFirst { it.packageName == selectedGame?.packageName }.coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Pilih game")
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                selectedGame = installedGames[which]
                profile = GamepadProfile.load(this, selectedGame!!.packageName)
                overlay.setProfile(profile)
                updateGameButton()
                dialog.dismiss()
                setStatus("Profil dimuat • ${selectedGame!!.label}")
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun createVirtualDisplay() {
        if (!surfaceView.holder.surface.isValid) {
            Toast.makeText(this, "Surface pratinjau belum siap.", Toast.LENGTH_SHORT).show()
            return
        }
        displayController.start(
            displayWidth,
            displayHeight,
            resources.configuration.densityDpi.coerceIn(120, 640),
            surfaceView.holder.surface
        )
    }

    private fun launchSelectedGame() {
        val game = selectedGame
        if (game == null) {
            Toast.makeText(this, "Pilih game terlebih dahulu.", Toast.LENGTH_SHORT).show()
            return
        }
        val displayId = displayController.displayId
        if (displayId < 0) {
            Toast.makeText(this, "Buat virtual display terlebih dahulu.", Toast.LENGTH_SHORT).show()
            return
        }
        shizukuBridge.connect { connected ->
            if (!connected) return@connect
            Thread({
                val result = shizukuBridge.launchAppOnDisplay(displayId, game.packageName, game.activityName)
                runOnUiThread {
                    setStatus(result)
                    val launchFailed = result.startsWith("Game gagal", ignoreCase = true) ||
                        result.startsWith("Peluncuran ditolak", ignoreCase = true) ||
                        result.contains("Error", ignoreCase = true) ||
                        result.contains("Exception", ignoreCase = true)
                    if (launchFailed) {
                        Toast.makeText(this, "Game tidak dapat dibuka di virtual display. Lihat status.", Toast.LENGTH_LONG).show()
                    }
                }
            }, "SakuGamepadLaunch").apply { isDaemon = true; start() }
        }
    }

    private fun togglePause() {
        inputPaused = !inputPaused
        if (inputPaused) inputController.releaseAll()
        overlay.inputEnabled = !inputPaused
        pauseButton.text = if (inputPaused) "Lanjut" else "Jeda"
        setStatus(if (inputPaused) "Input dijeda • semua sentuhan dilepas." else "Input gamepad aktif.")
    }

    private fun showCalibrationPicker() {
        inputController.releaseAll()
        val options = GamepadControl.entries.map { it.label }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Pilih kontrol, lalu tap posisi target di layar game")
            .setItems(options) { _, which ->
                val control = GamepadControl.entries[which]
                overlay.beginCalibration(control)
                Toast.makeText(this, "Tap lokasi ${control.label} pada layar game", Toast.LENGTH_LONG).show()
                setStatus("Kalibrasi ${control.label} • tap posisi target pada preview.")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun openTouchpad() {
        inputController.releaseAll()
        displayController.close()
        startActivity(Intent(this, VirtualDisplayTouchActivity::class.java))
    }

    private fun updateGameButton() {
        gameButton.text = selectedGame?.label?.take(14) ?: "Pilih game"
    }

    private fun setStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    private fun makeButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 10f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(34)
        minimumHeight = dp(34)
        setPadding(dp(3), 0, dp(3), 0)
        setTextColor(Color.rgb(237, 247, 255))
        background = rounded(Color.rgb(22, 54, 82), 9, Color.rgb(66, 133, 171))
        setOnClickListener { action() }
    }

    private fun addWeighted(parent: LinearLayout, view: View, weight: Float) {
        parent.addView(view, LinearLayout.LayoutParams(0, dp(36), weight).apply {
            leftMargin = dp(2)
            rightMargin = dp(2)
        })
    }

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        if (::inputController.isInitialized) inputController.close()
        if (::displayController.isInitialized) displayController.close()
        if (::shizukuBridge.isInitialized) shizukuBridge.close()
        super.onDestroy()
    }
}

private class GamepadOverlayView(
    context: Context,
    private val input: GamepadInputController,
    initialProfile: GamepadProfile,
    private val onProfileChanged: (GamepadProfile) -> Unit
) : View(context) {
    private sealed interface Binding {
        data object Stick : Binding
        data class Button(val control: GamepadControl) : Binding
        data class Direct(val pointerId: Int) : Binding
    }

    private val density = resources.displayMetrics.density
    private val activeBindings = HashMap<Int, Binding>()
    private var profile = initialProfile
    private var calibrationTarget: GamepadControl? = null
    private var stickOffsetX = 0f
    private var stickOffsetY = 0f
    var inputEnabled = true
        set(value) {
            field = value
            if (!value) {
                input.releaseAll()
                activeBindings.clear()
                stickOffsetX = 0f
                stickOffsetY = 0f
            }
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun setProfile(value: GamepadProfile) {
        profile = value
        invalidate()
    }

    fun beginCalibration(control: GamepadControl) {
        input.releaseAll()
        activeBindings.clear()
        calibrationTarget = control
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val movement = profile.movement
        val centerX = movement.x * width
        val centerY = movement.y * height
        val radius = dp(54f)
        val alpha = if (inputEnabled) 150 else 72

        fillPaint.color = Color.argb(alpha, 10, 31, 52)
        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        strokePaint.color = Color.argb(220, 126, 211, 242)
        canvas.drawCircle(centerX, centerY, radius, strokePaint)
        canvas.drawCircle(centerX, centerY, radius * 0.58f, strokePaint)
        val knobX = centerX + stickOffsetX
        val knobY = centerY + stickOffsetY
        fillPaint.color = Color.argb(if (inputEnabled) 218 else 92, 71, 197, 224)
        canvas.drawCircle(knobX, knobY, radius * 0.37f, fillPaint)
        textPaint.textSize = dp(10f)
        textPaint.color = Color.WHITE
        canvas.drawText("MOVE", centerX, centerY + radius + dp(15f), textPaint)

        GamepadControl.entries.filter { it != GamepadControl.MOVE }.forEach { control ->
            val point = profile.pointFor(control)
            val x = point.x * width
            val y = point.y * height
            val buttonRadius = dp(if (control == GamepadControl.ATTACK) 36f else 31f)
            fillPaint.color = when (control) {
                GamepadControl.ATTACK -> Color.argb(alpha + 35, 145, 64, 49)
                GamepadControl.RECALL -> Color.argb(alpha, 54, 91, 135)
                else -> Color.argb(alpha + 20, 47, 94, 143)
            }
            canvas.drawCircle(x, y, buttonRadius, fillPaint)
            strokePaint.color = Color.argb(225, 133, 211, 244)
            canvas.drawCircle(x, y, buttonRadius, strokePaint)
            val label = when (control) {
                GamepadControl.ATTACK -> "ATK"
                GamepadControl.SKILL_1 -> "S1"
                GamepadControl.SKILL_2 -> "S2"
                GamepadControl.SKILL_3 -> "S3"
                GamepadControl.RECALL -> "REC"
                GamepadControl.MOVE -> ""
            }
            textPaint.textSize = dp(if (control == GamepadControl.ATTACK) 11f else 10f)
            canvas.drawText(label, x, y + dp(4f), textPaint)
        }

        calibrationTarget?.let { target ->
            fillPaint.color = Color.argb(185, 0, 0, 0)
            canvas.drawRoundRect(dp(12f), dp(12f), width - dp(12f), dp(45f), dp(12f), dp(12f), fillPaint)
            textPaint.textSize = dp(13f)
            canvas.drawText("KALIBRASI ${target.label.uppercase(Locale.getDefault())} • TAP TARGET", width / 2f, dp(34f), textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (calibrationTarget != null) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val target = calibrationTarget ?: return true
                profile = profile.withPoint(target, pointFromTouch(event.x, event.y))
                calibrationTarget = null
                onProfileChanged(profile)
                performClick()
                return true
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handlePointerDown(event, event.actionIndex)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event, event.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) handlePointerMove(event, index)
            }
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event, event.actionIndex)
            MotionEvent.ACTION_UP -> {
                handlePointerUp(event, event.actionIndex)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                input.releaseAll()
                activeBindings.clear()
                stickOffsetX = 0f
                stickOffsetY = 0f
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun handlePointerDown(event: MotionEvent, index: Int) {
        val androidId = event.getPointerId(index)
        if (!inputEnabled) return
        val x = event.getX(index)
        val y = event.getY(index)
        val control = hitControl(x, y)
        when (control) {
            GamepadControl.MOVE -> {
                if (activeBindings.values.any { it == Binding.Stick }) return
                activeBindings[androidId] = Binding.Stick
                updateStick(event.getX(index), event.getY(index))
                input.press(GamepadControl.MOVE.pointerId, movementPointAt(x, y))
            }
            null -> {
                val usedDirectIds = activeBindings.values.filterIsInstance<Binding.Direct>().map { it.pointerId }.toSet()
                val injectedId = (16..31).firstOrNull { it !in usedDirectIds } ?: return
                activeBindings[androidId] = Binding.Direct(injectedId)
                input.press(injectedId, pointFromTouch(x, y))
            }
            else -> {
                if (activeBindings.values.any { it is Binding.Button && it.control == control }) return
                activeBindings[androidId] = Binding.Button(control)
                input.press(control.pointerId, profile.pointFor(control))
            }
        }
        invalidate()
    }

    private fun handlePointerMove(event: MotionEvent, index: Int) {
        val androidId = event.getPointerId(index)
        when (val binding = activeBindings[androidId]) {
            Binding.Stick -> {
                val x = event.getX(index)
                val y = event.getY(index)
                updateStick(x, y)
                input.move(GamepadControl.MOVE.pointerId, movementPointAt(x, y))
                invalidate()
            }
            is Binding.Button -> Unit
            is Binding.Direct -> input.move(binding.pointerId, pointFromTouch(event.getX(index), event.getY(index)))
            null -> Unit
        }
    }

    private fun handlePointerUp(event: MotionEvent, index: Int) {
        val androidId = event.getPointerId(index)
        when (val binding = activeBindings.remove(androidId)) {
            Binding.Stick -> {
                input.release(GamepadControl.MOVE.pointerId)
                stickOffsetX = 0f
                stickOffsetY = 0f
                invalidate()
            }
            is Binding.Button -> input.release(binding.control.pointerId)
            is Binding.Direct -> input.release(binding.pointerId)
            null -> Unit
        }
    }

    private fun hitControl(x: Float, y: Float): GamepadControl? {
        val move = profile.movement
        val stickRadius = dp(70f)
        if (hypot(x - move.x * width, y - move.y * height) <= stickRadius) return GamepadControl.MOVE
        GamepadControl.entries.filter { it != GamepadControl.MOVE }
            .sortedByDescending { if (it == GamepadControl.ATTACK) 36 else 31 }
            .forEach { control ->
                val point = profile.pointFor(control)
                val radius = dp(if (control == GamepadControl.ATTACK) 50f else 44f)
                if (hypot(x - point.x * width, y - point.y * height) <= radius) return control
            }
        return null
    }

    private fun updateStick(x: Float, y: Float) {
        val point = profile.movement
        val centerX = point.x * width
        val centerY = point.y * height
        val maxRadius = dp(54f)
        var dx = x - centerX
        var dy = y - centerY
        val distance = hypot(dx, dy)
        if (distance > maxRadius && distance > 0f) {
            val scale = maxRadius / distance
            dx *= scale
            dy *= scale
        }
        stickOffsetX = dx
        stickOffsetY = dy
    }

    private fun movementPointAt(x: Float, y: Float): ControlPoint {
        val base = profile.movement
        val maxRadius = dp(54f).coerceAtLeast(1f)
        var dx = (x - base.x * width) / maxRadius
        var dy = (y - base.y * height) / maxRadius
        val distance = hypot(dx, dy)
        if (distance > 1f && distance > 0f) {
            dx /= distance
            dy /= distance
        }
        return ControlPoint(
            (base.x + dx * 0.075f).coerceIn(0.02f, 0.98f),
            (base.y + dy * 0.15f).coerceIn(0.02f, 0.98f)
        )
    }

    private fun pointFromTouch(x: Float, y: Float): ControlPoint = ControlPoint(
        (x / width.coerceAtLeast(1)).coerceIn(0.02f, 0.98f),
        (y / height.coerceAtLeast(1)).coerceIn(0.02f, 0.98f)
    )

    private fun dp(value: Float): Float = value * density
}
