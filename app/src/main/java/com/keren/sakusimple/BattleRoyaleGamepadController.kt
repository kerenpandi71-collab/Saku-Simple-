package com.keren.sakusimple

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Standalone Battle Royale controls; independent from the MOBA controller. */
class BattleRoyaleGamepadController(
    context: Context,
    root: FrameLayout,
    private val displaySize: () -> Pair<Int, Int>,
    private val inject: (Int, IntArray, FloatArray, FloatArray) -> Boolean
) {
    private val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences("saku_battle_royale_gamepad", Context.MODE_PRIVATE)
    private val points = linkedMapOf<Int, Pair<Float, Float>>()
    private val physical = mutableMapOf<Int, Int>()
    private val buttonIds = mapOf("FIRE" to 2, "ADS" to 3, "JUMP" to 4, "CROUCH" to 5,
        "RELOAD" to 6, "WEAPON" to 7, "INTERACT" to 8, "SPRINT" to 9)
    private val labels = listOf("FIRE", "ADS", "JUMP", "CROUCH", "RELOAD", "WEAPON", "INTERACT", "SPRINT")
    private val defaults = mapOf(
        "MOVE" to (.15f to .76f), "FIRE" to (.91f to .40f), "ADS" to (.78f to .31f),
        "JUMP" to (.91f to .60f), "CROUCH" to (.78f to .68f), "RELOAD" to (.65f to .48f),
        "WEAPON" to (.65f to .68f), "INTERACT" to (.55f to .57f), "SPRINT" to (.34f to .76f)
    )
    private val layout = defaults.mapValues { (key, fallback) ->
        val stored = prefs.getString("pos_$key", null)?.split(",")
        if (stored?.size == 2) (stored[0].toFloatOrNull() ?: fallback.first) to
            (stored[1].toFloatOrNull() ?: fallback.second) else fallback
    }.toMutableMap()
    private var visible = false
    private var editMode = false
    private var draggedControl: String? = null
    private var cameraSensitivity = prefs.getFloat("camera_sensitivity", 1.25f).coerceIn(.35f, 3f)
    private var cameraFinger = -1
    private var cameraLastX = 0f
    private var cameraLastY = 0f
    private var stickFinger = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val view = object : View(context) {
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (!visible) return
            val w = width.toFloat(); val h = height.toFloat()
            // The camera area is deliberately invisible: the game remains fully visible.
            val move = point("MOVE", w, h)
            val radius = min(w, h) * .105f
            paint.color = Color.argb(if (editMode) 55 else 24, 12, 22, 32)
            c.drawCircle(move.first, move.second, radius, paint)
            paint.color = Color.argb(190, 220, 240, 255)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3).toFloat()
            c.drawCircle(move.first, move.second, radius, paint)
            paint.style = Paint.Style.FILL
            label(c, if (editMode) "MOVE ↔" else "MOVE", move.first, move.second)
            labels.forEach { name ->
                val p = point(name, w, h)
                paint.color = when (name) {
                    "FIRE" -> Color.argb(175, 190, 55, 45)
                    else -> Color.argb(if (editMode) 145 else 100, 25, 45, 65)
                }
                c.drawCircle(p.first, p.second, dp(if (name == "FIRE") 30 else 25).toFloat(), paint)
                paint.color = Color.WHITE
                paint.textSize = dp(10).toFloat()
                c.drawText(name, p.first - paint.measureText(name) / 2f, p.second + dp(4), paint)
                if (editMode) {
                    paint.color = Color.YELLOW
                    c.drawCircle(p.first, p.second, dp(29).toFloat(), paint.apply { style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() })
                    paint.style = Paint.Style.FILL
                }
            }
            paint.color = if (editMode) Color.YELLOW else Color.argb(150, 255, 255, 255)
            paint.textSize = dp(10).toFloat()
            c.drawText(if (editMode) "ATUR POSISI • SERET KONTROL" else "BR • AREA KOSONG KANAN = KAMERA", dp(12).toFloat(), dp(22).toFloat(), paint)
        }
    }

    init {
        view.isClickable = true
        view.contentDescription = "Gamepad Battle Royale"
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.visibility = View.GONE
    }

    val isVisible: Boolean get() = visible

    fun toggle() {
        visible = !visible
        editMode = false
        view.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) releaseAll()
        view.invalidate()
        Toast.makeText(view.context, if (visible) "Gamepad Battle Royale aktif" else "Gamepad Battle Royale disembunyikan", Toast.LENGTH_SHORT).show()
    }

    fun showSettings() {
        val seek = android.widget.SeekBar(view.context).apply {
            max = 265
            progress = ((cameraSensitivity - .35f) * 100).toInt().coerceIn(0, 265)
        }
        android.app.AlertDialog.Builder(view.context)
            .setTitle("Gamepad Battle Royale")
            .setMessage("Atur sensitivitas kamera. Gunakan Atur posisi untuk memindahkan joystick dan tombol.")
            .setView(seek)
            .setNeutralButton(if (editMode) "Selesai atur" else "Atur posisi tombol") { _, _ ->
                if (!visible) {
                    visible = true
                    view.visibility = View.VISIBLE
                }
                editMode = !editMode
                draggedControl = null
                view.invalidate()
                Toast.makeText(view.context, if (editMode) "Seret joystick dan tombol ke posisi pilihanmu" else "Posisi kontrol tersimpan", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Simpan sensitivitas") { _, _ ->
                cameraSensitivity = (.35f + seek.progress / 100f).coerceIn(.35f, 3f)
                prefs.edit().putFloat("camera_sensitivity", cameraSensitivity).apply()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun point(name: String, w: Float, h: Float): Pair<Float, Float> {
        val p = layout[name] ?: (.5f to .5f)
        return w * p.first to h * p.second
    }

    private fun label(c: Canvas, text: String, x: Float, y: Float) {
        paint.color = Color.WHITE
        paint.textSize = dp(11).toFloat()
        c.drawText(text, x - paint.measureText(text) / 2f, y + dp(4), paint)
    }

    private fun handle(e: MotionEvent): Boolean {
        val idx = e.actionIndex.coerceIn(0, e.pointerCount - 1)
        val id = e.getPointerId(idx)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(idx); val y = e.getY(idx)
                val w = view.width.toFloat(); val h = view.height.toFloat()
                if (editMode) {
                    val nearest = (buttonCenters(w, h) + ("MOVE" to point("MOVE", w, h)))
                        .minByOrNull { (_, p) -> sq(p.first - x) + sq(p.second - y) }
                    draggedControl = nearest?.takeIf { (_, p) ->
                        kotlin.math.hypot((p.first - x).toDouble(), (p.second - y).toDouble()) < dp(75)
                    }?.key
                    physical[id] = -1
                    return true
                }
                // Check action buttons first so they are never swallowed by the camera swipe zone.
                val nearest = buttonCenters(w, h).minByOrNull { (_, p) -> sq(p.first - x) + sq(p.second - y) }
                if (nearest != null && kotlin.math.hypot((nearest.value.first - x).toDouble(), (nearest.value.second - y).toDouble()) < dp(48)) {
                    val key = buttonIds[nearest.key] ?: return true
                    physical[id] = key
                    val p = layout[nearest.key] ?: defaults.getValue(nearest.key)
                    down(key, p.first, p.second)
                } else if (x < w * .34f && y > h * .57f) {
                    if (stickFinger < 0) {
                        stickFinger = id; physical[id] = 1
                        updateStick(x, y)
                    }
                } else if (x > w * .40f && y > h * .16f && y < h * .90f) {
                    if (cameraFinger < 0) {
                        cameraFinger = id; cameraLastX = x; cameraLastY = y; physical[id] = 10
                        down(10, .72f, .56f)
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val pid = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                    if (editMode && physical[pid] == -1) {
                        draggedControl?.let { name ->
                            val nx = (x / max(1, view.width)).coerceIn(.04f, .96f)
                            val ny = (y / max(1, view.height)).coerceIn(.06f, .94f)
                            layout[name] = nx to ny
                            prefs.edit().putString("pos_$name", "$nx,$ny").apply()
                            view.invalidate()
                        }
                    } else if (pid == stickFinger) updateStick(x, y)
                    else if (pid == cameraFinger) {
                        val dx = x - cameraLastX; val dy = y - cameraLastY
                        cameraLastX = x; cameraLastY = y
                        cameraXMove(dx, dy)
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (editMode) {
                    physical.remove(id); draggedControl = null
                    return true
                }
                physical.remove(id)?.let { key ->
                    when (key) {
                        1 -> { up(1); stickFinger = -1 }
                        10 -> { up(10); cameraFinger = -1 }
                        -1 -> Unit
                        else -> up(key)
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { releaseAll(); draggedControl = null; return true }
        }
        return true
    }

    private fun cameraXMove(dx: Float, dy: Float) {
        // Relative camera swipe: keep the virtual point away from edges to avoid clamping dead-zones.
        cameraNormX = (cameraNormX + dx * cameraSensitivity / max(1, view.width) * .9f).coerceIn(.08f, .92f)
        cameraNormY = (cameraNormY + dy * cameraSensitivity / max(1, view.height) * .9f).coerceIn(.08f, .92f)
        move(10, cameraNormX, cameraNormY)
    }
    private var cameraNormX = .72f
    private var cameraNormY = .56f

    private fun buttonCenters(w: Float, h: Float): Map<String, Pair<Float, Float>> =
        labels.associateWith { point(it, w, h) }

    private fun updateStick(x: Float, y: Float) {
        val center = point("MOVE", view.width.toFloat(), view.height.toFloat())
        val radius = max(1f, min(view.width, view.height) * .105f)
        var dx = ((x - center.first) / radius).coerceIn(-1f, 1f)
        var dy = ((y - center.second) / radius).coerceIn(-1f, 1f)
        val magnitude = sqrt(dx * dx + dy * dy)
        if (magnitude > 1f) { dx /= magnitude; dy /= magnitude }
        val base = layout["MOVE"] ?: defaults.getValue("MOVE")
        setPoint(1, (base.first + dx * .12f).coerceIn(0f, 1f), (base.second + dy * .12f).coerceIn(0f, 1f))
    }

    private fun sq(v: Float) = v * v
    private fun down(key: Int, x: Float, y: Float) {
        val action = if (points.isEmpty()) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN or (points.size shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        points[key] = x to y; send(action)
    }
    private fun move(key: Int, x: Float, y: Float) { if (key in points) { points[key] = x to y; send(MotionEvent.ACTION_MOVE) } }
    private fun setPoint(key: Int, x: Float, y: Float) { if (key in points) move(key, x, y) else down(key, x, y) }
    private fun up(key: Int) {
        if (key !in points) return
        val index = points.keys.indexOf(key)
        val action = if (points.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        send(action); points.remove(key)
    }
    private fun send(action: Int) {
        val (w, h) = displaySize()
        if (w <= 0 || h <= 0) return
        inject(action, points.keys.toIntArray(), points.values.map { it.first * w }.toFloatArray(),
            points.values.map { it.second * h }.toFloatArray())
    }
    private fun releaseAll() {
        points.keys.toList().asReversed().forEach { up(it) }
        physical.clear(); stickFinger = -1; cameraFinger = -1
    }
    private fun dp(v: Int): Int = (v * appContext.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    fun close() { releaseAll(); (view.parent as? FrameLayout)?.removeView(view) }
}
