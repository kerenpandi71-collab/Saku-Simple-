package com.keren.sakusimple

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/** Movable in-app gamepad with persisted virtual-display touch targets. */
class GamepadOverlayController(
    private val context: Context,
    private val root: android.widget.FrameLayout,
    private val displaySize: () -> Pair<Int, Int>,
    private val injectTap: (Float, Float) -> Boolean,
    private val injectTouch: (Int, Float, Float) -> Boolean,
    private val onStatus: (String) -> Unit
) {
    private data class Point(val x: Float, val y: Float)
    private val prefs = context.getSharedPreferences("saku_gamepad_overlay", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "SakuGamepadInput").apply { isDaemon = true } }
    private val keys = listOf("STICK", "SKILL_1", "SKILL_2", "ULTIMATE", "BATTLE_SPELL", "BLINK", "ATTACK", "ITEM_CUSTOM", "RECALL", "REGEN", "FLICKER")
    private val labels = mapOf(
        "STICK" to "Joystick gerak 360°", "SKILL_1" to "Skill 1", "SKILL_2" to "Skill 2",
        "ULTIMATE" to "Skill 3 / Ultimate", "BATTLE_SPELL" to "Battle Spell", "BLINK" to "Blink",
        "ATTACK" to "Serang dasar", "ITEM_CUSTOM" to "Item / tombol custom",
        "RECALL" to "Recall", "REGEN" to "Regen", "FLICKER" to "Flicker"
    )
    private val targets = mutableMapOf<String, Point>()
    private var visible = prefs.getBoolean("visible", true)
    private var mappingKey: String? = null
    private var heightFraction = prefs.getFloat("height_fraction", 0.36f).coerceIn(0.22f, 0.58f)
    private var scale = prefs.getFloat("scale", 1f).coerceIn(0.7f, 1.3f)
    private var transparencyPercent = prefs.getInt("transparency", 60).coerceIn(10, 90)
    private var panelX = prefs.getFloat("panel_x", 0.03f).coerceIn(0f, 0.7f)
    private var panelY = prefs.getFloat("panel_y", 0.61f).coerceIn(0.2f, 0.9f)
    private var draggingPanel = false
    private var draggingTargetKey: String? = null
    private val targetHitRects = mutableMapOf<String, RectF>()
    private var dragRawX = 0f
    private var dragRawY = 0f
    private var startPanelX = 0f
    private var startPanelY = 0f
    private var analogPointer = false
    private var analogLastX = 0f
    private var analogLastY = 0f
    private var analogFingerStartX = 0f
    private var analogFingerStartY = 0f
    private val pendingAnalogMove = AtomicReference<Point?>(null)
    private val analogMoveScheduled = AtomicBoolean(false)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hitRects = mutableMapOf<String, RectF>()
    private var panelRect = RectF()
    private var headerRect = RectF()
    private val view = object : View(context) {
        override fun onDraw(canvas: Canvas) { super.onDraw(canvas); drawGamepad(canvas) }
        override fun onTouchEvent(event: MotionEvent): Boolean = handleTouch(event)
    }

    init {
        loadTargets()
        view.isClickable = true
        view.contentDescription = "Gamepad melayang Saku Simple"
        root.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun toggleVisible(): Boolean {
        visible = !visible
        prefs.edit().putBoolean("visible", visible).apply()
        view.visibility = if (visible || mappingKey != null) View.VISIBLE else View.GONE
        view.invalidate()
        onStatus(if (visible) "Gamepad ditampilkan." else "Gamepad disembunyikan.")
        return visible
    }

    fun showSettings() {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(4)) }
        box.addView(TextView(context).apply { text = "Tinggi gamepad"; setTextColor(Color.WHITE) })
        val height = SeekBar(context).apply { max = 36; progress = ((heightFraction - 0.22f) * 100).toInt().coerceIn(0, 36) }
        box.addView(height)
        box.addView(TextView(context).apply { text = "Ukuran tombol"; setTextColor(Color.WHITE) })
        val size = SeekBar(context).apply { max = 60; progress = ((scale - 0.7f) * 100).toInt().coerceIn(0, 60) }
        box.addView(size)
        box.addView(TextView(context).apply { text = "Transparansi kontrol"; setTextColor(Color.WHITE) })
        val transparency = SeekBar(context).apply { max = 80; progress = transparencyPercent - 10 }
        box.addView(transparency)
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                keys.map { key -> key + " — " + (labels[key] ?: key) })
        }
        box.addView(TextView(context).apply {
            text = "Pilih fungsi, tekan Letakkan mapping, lalu sentuh lokasi tombolnya di preview. Marker mapping akan muncul dan bisa diseret untuk koreksi posisi. Untuk joystick, pilih Joystick gerak 360° lalu sentuh TENGAH joystick ML. Kontrol: Skill 1/2, Ultimate, Battle Spell, Blink, Serang, Item/Custom, Recall, Regen, Flicker."
            setTextColor(Color.WHITE)
        })
        box.addView(spinner)
        val dialog = AlertDialog.Builder(context).setTitle("Gamepad Bola Asisten").setView(box)
            .setPositiveButton("Simpan", null).setNeutralButton("Letakkan mapping", null).setNegativeButton("Tutup", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                heightFraction = (0.22f + height.progress / 100f).coerceIn(0.22f, 0.58f)
                scale = (0.7f + size.progress / 100f).coerceIn(0.7f, 1.3f)
                transparencyPercent = (10 + transparency.progress).coerceIn(10, 90)
                prefs.edit().putFloat("height_fraction", heightFraction).putFloat("scale", scale)
                    .putInt("transparency", transparencyPercent).apply()
                view.invalidate()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val selectedKey = keys[spinner.selectedItemPosition.coerceIn(0, keys.lastIndex)]
                mappingKey = selectedKey
                visible = true
                prefs.edit().putBoolean("visible", true).apply()
                view.visibility = View.VISIBLE
                dialog.dismiss()
                onStatus("Atur " + (labels[selectedKey] ?: selectedKey) + ": sentuh tombol tujuan pada preview virtual display.")
                val hint = if (selectedKey == "STICK") "Sentuh TENGAH joystick gerak ML" else "Sentuh tombol ML untuk: " + (labels[selectedKey] ?: selectedKey)
                Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
                view.invalidate()
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.background_dark)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        if (mappingKey != null && event.actionMasked == MotionEvent.ACTION_UP && !panelRect.contains(x, y)) {
            val preview = findPreview(root)
            val key = mappingKey ?: return true
            if (preview != null && preview.width > 0 && preview.height > 0) {
                val loc = IntArray(2)
                preview.getLocationOnScreen(loc)
                val sx = ((event.rawX - loc[0]) / preview.width).coerceIn(0f, 1f)
                val sy = ((event.rawY - loc[1]) / preview.height).coerceIn(0f, 1f)
                targets[key] = Point(sx, sy)
                saveTargets()
                mappingKey = null
                view.invalidate()
                view.visibility = if (visible) View.VISIBLE else View.GONE
                onStatus((labels[key] ?: key) + " tersimpan. Marker bisa diseret untuk mengoreksi posisi.")
                return true
            }
            mappingKey = null
            onStatus("Preview belum tersedia; mulai virtual display dahulu.")
            view.invalidate()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val marker = targetHitRects.entries.firstOrNull { it.value.contains(x, y) }?.key
            if (marker != null && mappingKey == null) {
                draggingTargetKey = marker
                return true
            }
            if (!panelRect.contains(x, y)) return mappingKey != null
            if (headerRect.contains(x, y)) {
                draggingPanel = true
                dragRawX = event.rawX; dragRawY = event.rawY
                startPanelX = panelX; startPanelY = panelY
                return true
            }
            val hit = hitRects.entries.firstOrNull { it.value.contains(x, y) }?.key ?: return true
            if (hit == "STICK") {
                val target = targets[hit] ?: Point(0.15f, 0.72f)
                analogPointer = true
                analogLastX = target.x; analogLastY = target.y
                analogFingerStartX = x
                analogFingerStartY = y
                val size = displaySize()
                io.execute { injectTouch(MotionEvent.ACTION_DOWN, target.x * (size.first - 1), target.y * (size.second - 1)) }
            } else sendMappedTap(hit)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            val index = event.actionIndex
            val px = event.getX(index); val py = event.getY(index)
            val hit = hitRects.entries.firstOrNull { it.key != "STICK" && it.value.contains(px, py) }?.key
            if (hit != null) sendMappedTap(hit)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && draggingTargetKey != null) {
            val key = draggingTargetKey ?: return true
            val preview = findPreview(root)
            if (preview != null && preview.width > 0 && preview.height > 0) {
                val loc = IntArray(2); preview.getLocationOnScreen(loc)
                val sx = ((event.rawX - loc[0]) / preview.width).coerceIn(0f, 1f)
                val sy = ((event.rawY - loc[1]) / preview.height).coerceIn(0f, 1f)
                targets[key] = Point(sx, sy)
                saveTargets()
                view.invalidate()
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && draggingPanel) {
            panelX = (startPanelX + (event.rawX - dragRawX) / max(1f, root.width.toFloat())).coerceIn(0f, 0.72f)
            panelY = (startPanelY + (event.rawY - dragRawY) / max(1f, root.height.toFloat())).coerceIn(0.2f, 0.9f)
            prefs.edit().putFloat("panel_x", panelX).putFloat("panel_y", panelY).apply()
            view.invalidate()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && analogPointer) {
            val rect = hitRects["STICK"] ?: return true
            val target = targets["STICK"] ?: Point(0.15f, 0.72f)
            val radius = max(1f, min(rect.width(), rect.height()) * 0.42f)
            val dx = (x - analogFingerStartX) / radius
            val dy = (y - analogFingerStartY) / radius
            val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val nx = (target.x + dx / length * min(1f, kotlin.math.sqrt(dx * dx + dy * dy)) * 0.095f).coerceIn(0f, 1f)
            val ny = (target.y + dy / length * min(1f, kotlin.math.sqrt(dx * dx + dy * dy)) * 0.095f).coerceIn(0f, 1f)
            analogLastX = nx; analogLastY = ny
            val size = displaySize()
            queueAnalogMove(nx * (size.first - 1), ny * (size.second - 1))
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (draggingTargetKey != null) { draggingTargetKey = null; return true }
            if (draggingPanel) { draggingPanel = false; return true }
            if (analogPointer) {
                analogPointer = false
                val size = displaySize()
                io.execute { injectTouch(MotionEvent.ACTION_UP, analogLastX * (size.first - 1), analogLastY * (size.second - 1)) }
                return true
            }
            return panelRect.contains(x, y) || mappingKey != null
        }
        return panelRect.contains(x, y) || mappingKey != null
    }

    /** Coalesce fast finger movement while keeping the injected touch stream ordered. */
    private fun queueAnalogMove(x: Float, y: Float) {
        pendingAnalogMove.set(Point(x, y))
        scheduleAnalogDrain()
    }

    private fun scheduleAnalogDrain() {
        if (!analogMoveScheduled.compareAndSet(false, true)) return
        io.execute {
            try {
                while (true) {
                    val next = pendingAnalogMove.getAndSet(null) ?: break
                    injectTouch(MotionEvent.ACTION_MOVE, next.x, next.y)
                }
            } finally {
                analogMoveScheduled.set(false)
                if (pendingAnalogMove.get() != null) scheduleAnalogDrain()
            }
        }
    }

    private fun sendMappedTap(key: String) {
        val p = targets[key]
        if (p == null) { onStatus("Tombol " + key + " belum dipetakan. Buka pengaturan gamepad."); return }
        val size = displaySize()
        io.execute {
            val ok = injectTap((p.x * size.first).coerceIn(0f, (size.first - 1).toFloat()), (p.y * size.second).coerceIn(0f, (size.second - 1).toFloat()))
            if (!ok) onStatus("Mapping " + key + " gagal dikirim. Periksa Shizuku dan virtual display.")
        }
    }

    private fun drawGamepad(canvas: Canvas) {
        hitRects.clear()
        targetHitRects.clear()
        val viewWidth = view.width.toFloat()
        val viewHeight = view.height.toFloat()
        val pw = min(viewWidth * 0.98f, dp(660).toFloat() * scale)
        val ph = min(viewHeight * heightFraction, dp(320).toFloat() * scale)
        val left = (panelX * viewWidth).coerceIn(0f, max(0f, viewWidth - pw))
        val top = (panelY * viewHeight).coerceIn(0f, max(0f, viewHeight - ph))
        panelRect = RectF(left, top, left + pw, top + ph)
        headerRect = RectF(left + pw * 0.34f, top, left + pw * 0.66f, top + ph * 0.13f)

        val bgAlpha = ((100 - transparencyPercent) * 1.55f).toInt().coerceIn(12, 150)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(bgAlpha, 4, 10, 22)
        canvas.drawRoundRect(panelRect, ph * 0.16f, ph * 0.16f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat()
        paint.color = Color.argb(75, 120, 175, 255)
        canvas.drawRoundRect(panelRect, ph * 0.16f, ph * 0.16f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(205, 20, 35, 58)
        canvas.drawRoundRect(headerRect, dp(10).toFloat(), dp(10).toFloat(), paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(10).toFloat()
        canvas.drawText("SAKU GAMEPAD  •  SERET", headerRect.centerX(), headerRect.centerY() + dp(3).toFloat(), paint)
        paint.textAlign = Paint.Align.LEFT

        val r = min(ph * 0.115f, dp(40).toFloat() * scale)
        drawButton(canvas, "STICK", left + pw * 0.17f, top + ph * 0.61f, r * 1.35f, "◎", Color.rgb(55, 155, 255), "360°")
        drawButton(canvas, "SKILL_1", left + pw * 0.70f, top + ph * 0.55f, r * 0.82f, "1", Color.rgb(64, 130, 255), "Skill 1")
        drawButton(canvas, "SKILL_2", left + pw * 0.80f, top + ph * 0.68f, r * 0.82f, "2", Color.rgb(153, 74, 255), "Skill 2")
        drawButton(canvas, "ULTIMATE", left + pw * 0.86f, top + ph * 0.38f, r * 0.94f, "ULT", Color.rgb(203, 93, 255), "Ultimate")
        drawButton(canvas, "ATTACK", left + pw * 0.88f, top + ph * 0.78f, r * 0.95f, "⚔", Color.rgb(55, 145, 255), "Serang")
        drawButton(canvas, "BATTLE_SPELL", left + pw * 0.59f, top + ph * 0.37f, r * 0.55f, "✦", Color.rgb(255, 190, 70), "Spell")
        drawButton(canvas, "BLINK", left + pw * 0.61f, top + ph * 0.57f, r * 0.52f, "➤", Color.rgb(75, 200, 255), "Blink")
        drawButton(canvas, "ITEM_CUSTOM", left + pw * 0.53f, top + ph * 0.76f, r * 0.48f, "+", Color.rgb(90, 210, 170), "Item")
        drawButton(canvas, "RECALL", left + pw * 0.31f, top + ph * 0.83f, r * 0.44f, "R", Color.rgb(80, 190, 255), "Recall")
        drawButton(canvas, "REGEN", left + pw * 0.40f, top + ph * 0.83f, r * 0.44f, "+", Color.rgb(75, 220, 115), "Regen")
        drawButton(canvas, "FLICKER", left + pw * 0.49f, top + ph * 0.83f, r * 0.44f, "✦", Color.rgb(255, 180, 65), "Flicker")

        val preview = findPreview(root)
        if (preview != null && preview.width > 0 && preview.height > 0) {
            val pLoc = IntArray(2); val vLoc = IntArray(2)
            preview.getLocationOnScreen(pLoc); view.getLocationOnScreen(vLoc)
            val pl = (pLoc[0] - vLoc[0]).toFloat()
            val pt = (pLoc[1] - vLoc[1]).toFloat()
            val markerRadius = dp(13).toFloat()
            targets.forEach { (key, point) ->
                val mx = pl + point.x * preview.width
                val my = pt + point.y * preview.height
                targetHitRects[key] = RectF(mx - markerRadius * 1.35f, my - markerRadius * 1.35f, mx + markerRadius * 1.35f, my + markerRadius * 1.35f)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(225, 28, 102, 230)
                canvas.drawCircle(mx, my, markerRadius, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); paint.color = Color.WHITE
                canvas.drawCircle(mx, my, markerRadius, paint)
                paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER; paint.textSize = dp(8).toFloat()
                val tag = when (key) {
                    "STICK" -> "◎"; "SKILL_1" -> "1"; "SKILL_2" -> "2"; "ULTIMATE" -> "U"
                    "BATTLE_SPELL" -> "S"; "BLINK" -> "B"; "ATTACK" -> "A"; "ITEM_CUSTOM" -> "+"
                    "RECALL" -> "R"; "REGEN" -> "G"; "FLICKER" -> "F"; else -> key.take(1)
                }
                canvas.drawText(tag, mx, my + dp(3).toFloat(), paint)
                paint.textAlign = Paint.Align.LEFT
                if (mappingKey == key) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3).toFloat(); paint.color = Color.YELLOW
                    canvas.drawCircle(mx, my, markerRadius + dp(4), paint)
                }
            }
        }
        if (mappingKey != null) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(225, 15, 70, 125)
            val hint = RectF(left + dp(6), max(0f, top - dp(30)), left + pw - dp(6), max(dp(24).toFloat(), top - dp(5)))
            canvas.drawRoundRect(hint, dp(8).toFloat(), dp(8).toFloat(), paint)
            paint.color = Color.WHITE; paint.textSize = dp(11).toFloat()
            canvas.drawText("Letakkan mapping di preview", hint.left + dp(8), hint.centerY() + dp(4).toFloat(), paint)
        }
    }

    private fun drawButton(canvas: Canvas, key: String, cx: Float, cy: Float, radius: Float, label: String, tint: Int, caption: String) {
        hitRects[key] = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(145, 16, 25, 42)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.color = Color.argb(220, 120, 175, 255)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.FILL
        paint.color = tint
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = max(dp(8).toFloat(), radius * 0.67f)
        canvas.drawText(label, cx, cy + radius * 0.20f, paint)
        paint.color = Color.WHITE
        paint.textSize = max(dp(6).toFloat(), radius * 0.24f)
        canvas.drawText(caption, cx, cy + radius * 0.73f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun findPreview(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.contentDescription?.toString()?.startsWith("Desktop virtual display") == true) return child
            if (child is ViewGroup) findPreview(child)?.let { return it }
        }
        return null
    }

    private fun loadTargets() {
        try {
            val json = JSONObject(prefs.getString("targets", "{}") ?: "{}")
            keys.forEach { key -> if (json.has(key)) {
                val item = json.getJSONObject(key)
                targets[key] = Point(item.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f), item.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f))
            } }
        } catch (_: Exception) { targets.clear() }
    }

    private fun saveTargets() {
        val json = JSONObject()
        targets.forEach { (key, p) -> json.put(key, JSONObject().put("x", p.x).put("y", p.y)) }
        prefs.edit().putString("targets", json.toString()).apply()
    }

    fun close() {
        if (analogPointer) {
            val size = displaySize()
            io.execute { injectTouch(MotionEvent.ACTION_UP, analogLastX * (size.first - 1), analogLastY * (size.second - 1)) }
        }
        io.shutdown()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
