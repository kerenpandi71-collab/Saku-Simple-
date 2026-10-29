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
    private val keys = listOf("A", "B", "X", "Y", "START", "SELECT", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "L_STICK")
    private val labels = mapOf("A" to "A", "B" to "B", "X" to "X", "Y" to "Y", "START" to "≡", "SELECT" to "▣",
        "DPAD_UP" to "↑", "DPAD_DOWN" to "↓", "DPAD_LEFT" to "←", "DPAD_RIGHT" to "→", "L_STICK" to "◎")
    private val targets = mutableMapOf<String, Point>()
    private var visible = prefs.getBoolean("visible", true)
    private var mappingKey: String? = null
    private var heightFraction = prefs.getFloat("height_fraction", 0.36f).coerceIn(0.22f, 0.58f)
    private var scale = prefs.getFloat("scale", 1f).coerceIn(0.7f, 1.3f)
    private var panelX = prefs.getFloat("panel_x", 0.03f).coerceIn(0f, 0.7f)
    private var panelY = prefs.getFloat("panel_y", 0.61f).coerceIn(0.2f, 0.9f)
    private var draggingPanel = false
    private var dragRawX = 0f
    private var dragRawY = 0f
    private var startPanelX = 0f
    private var startPanelY = 0f
    private var analogPointer = false
    private var analogLastX = 0f
    private var analogLastY = 0f
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
        val spinner = Spinner(context).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, keys.map { it + " — " + (labels[it] ?: it) }) }
        box.addView(TextView(context).apply { text = "Pilih tombol yang akan dipetakan"; setTextColor(Color.WHITE) })
        box.addView(spinner)
        val dialog = AlertDialog.Builder(context).setTitle("Gamepad Bola Asisten").setView(box)
            .setPositiveButton("Simpan", null).setNeutralButton("Letakkan mapping", null).setNegativeButton("Tutup", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                heightFraction = (0.22f + height.progress / 100f).coerceIn(0.22f, 0.58f)
                scale = (0.7f + size.progress / 100f).coerceIn(0.7f, 1.3f)
                prefs.edit().putFloat("height_fraction", heightFraction).putFloat("scale", scale).apply()
                view.invalidate()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                mappingKey = keys[spinner.selectedItemPosition.coerceIn(0, keys.lastIndex)]
                visible = true
                prefs.edit().putBoolean("visible", true).apply()
                view.visibility = View.VISIBLE
                dialog.dismiss()
                onStatus("Mapping " + mappingKey + ": sentuh target di preview virtual display.")
                Toast.makeText(context, "Sentuh lokasi tombol tujuan pada preview", Toast.LENGTH_LONG).show()
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
                val size = displaySize()
                onStatus("Mapping " + key + " tersimpan di " + (sx * size.first).toInt() + ", " + (sy * size.second).toInt())
                return true
            }
            mappingKey = null
            onStatus("Preview belum tersedia; mulai virtual display dahulu.")
            view.invalidate()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!panelRect.contains(x, y)) return mappingKey != null
            if (headerRect.contains(x, y)) {
                draggingPanel = true
                dragRawX = event.rawX; dragRawY = event.rawY
                startPanelX = panelX; startPanelY = panelY
                return true
            }
            val hit = hitRects.entries.firstOrNull { it.value.contains(x, y) }?.key ?: return true
            if (hit == "L_STICK") {
                val target = targets[hit] ?: Point(0.15f, 0.72f)
                analogPointer = true
                analogLastX = target.x; analogLastY = target.y
                val size = displaySize()
                io.execute { injectTouch(MotionEvent.ACTION_DOWN, target.x * size.first, target.y * size.second) }
            } else sendMappedTap(hit)
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
            val rect = hitRects["L_STICK"] ?: return true
            val target = targets["L_STICK"] ?: Point(0.15f, 0.72f)
            val radius = min(rect.width(), rect.height()) * 0.34f
            val nx = (target.x + ((x - rect.centerX()) / max(1f, radius)).coerceIn(-1f, 1f) * 0.12f).coerceIn(0f, 1f)
            val ny = (target.y + ((y - rect.centerY()) / max(1f, radius)).coerceIn(-1f, 1f) * 0.12f).coerceIn(0f, 1f)
            analogLastX = nx; analogLastY = ny
            val size = displaySize()
            io.execute { injectTouch(MotionEvent.ACTION_MOVE, nx * size.first, ny * size.second) }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (draggingPanel) { draggingPanel = false; return true }
            if (analogPointer) {
                analogPointer = false
                val size = displaySize()
                io.execute { injectTouch(MotionEvent.ACTION_UP, analogLastX * size.first, analogLastY * size.second) }
                return true
            }
            return panelRect.contains(x, y) || mappingKey != null
        }
        return panelRect.contains(x, y) || mappingKey != null
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
        val pw = min(width * 0.94f, dp(660).toFloat() * scale)
        val ph = min(height * heightFraction, dp(285).toFloat() * scale)
        val left = (panelX * width).coerceIn(0f, max(0f, width - pw))
        val top = (panelY * height).coerceIn(0f, max(0f, height - ph))
        panelRect = RectF(left, top, left + pw, top + ph)
        headerRect = RectF(left, top, left + pw, top + ph * 0.15f)
        paint.style = Paint.Style.FILL; paint.color = Color.argb(218, 4, 5, 8)
        canvas.drawRoundRect(panelRect, ph * 0.22f, ph * 0.22f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); paint.color = Color.argb(170, 105, 115, 128)
        canvas.drawRoundRect(panelRect, ph * 0.22f, ph * 0.22f, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.LTGRAY; paint.textSize = dp(10).toFloat()
        canvas.drawText("≡  GAMEPAD • seret di sini", left + dp(14), top + ph * 0.115f, paint)
        val r = ph * 0.16f
        drawButton(canvas, "L_STICK", left + pw * 0.19f, top + ph * 0.59f, r * 1.08f, "◎")
        drawButton(canvas, "DPAD_UP", left + pw * 0.37f, top + ph * 0.43f, r * 0.55f, "↑")
        drawButton(canvas, "DPAD_LEFT", left + pw * 0.33f, top + ph * 0.59f, r * 0.55f, "←")
        drawButton(canvas, "DPAD_RIGHT", left + pw * 0.41f, top + ph * 0.59f, r * 0.55f, "→")
        drawButton(canvas, "DPAD_DOWN", left + pw * 0.37f, top + ph * 0.75f, r * 0.55f, "↓")
        drawButton(canvas, "SELECT", left + pw * 0.48f, top + ph * 0.42f, r * 0.42f, "▣")
        drawButton(canvas, "START", left + pw * 0.58f, top + ph * 0.42f, r * 0.42f, "≡")
        drawButton(canvas, "X", left + pw * 0.72f, top + ph * 0.58f, r * 0.68f, "X", Color.rgb(60, 155, 235))
        drawButton(canvas, "Y", left + pw * 0.80f, top + ph * 0.38f, r * 0.68f, "Y", Color.rgb(244, 211, 74))
        drawButton(canvas, "B", left + pw * 0.88f, top + ph * 0.58f, r * 0.68f, "B", Color.rgb(236, 79, 89))
        drawButton(canvas, "A", left + pw * 0.80f, top + ph * 0.78f, r * 0.68f, "A", Color.rgb(67, 208, 120))
        if (mappingKey != null) {
            paint.style = Paint.Style.FILL; paint.color = Color.argb(220, 15, 90, 140)
            canvas.drawRoundRect(RectF(left + dp(8), max(0f, top - dp(32)), left + pw - dp(8), max(0f, top - dp(5))), dp(8).toFloat(), dp(8).toFloat(), paint)
            paint.color = Color.WHITE; paint.textSize = dp(12).toFloat()
            canvas.drawText("Pilih target untuk " + mappingKey + " pada preview", left + dp(16), max(dp(18).toFloat(), top - dp(14).toFloat()), paint)
        }
    }

    private fun drawButton(canvas: Canvas, key: String, cx: Float, cy: Float, radius: Float, label: String, tint: Int = Color.rgb(150, 160, 174)) {
        hitRects[key] = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.style = Paint.Style.FILL; paint.color = Color.argb(145, 26, 29, 35)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); paint.color = Color.argb(180, 105, 111, 123)
        canvas.drawCircle(cx, cy, radius, paint)
        if (key == "L_STICK") {
            canvas.drawCircle(cx, cy, radius * 0.64f, paint)
            canvas.drawCircle(cx, cy, radius * 0.42f, paint)
        } else {
            paint.style = Paint.Style.FILL; paint.color = tint; paint.textAlign = Paint.Align.CENTER; paint.textSize = radius * 1.05f
            canvas.drawText(label, cx, cy + radius * 0.36f, paint)
            paint.textAlign = Paint.Align.LEFT
        }
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
            io.execute { injectTouch(MotionEvent.ACTION_UP, analogLastX * size.first, analogLastY * size.second) }
        }
        io.shutdown()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
