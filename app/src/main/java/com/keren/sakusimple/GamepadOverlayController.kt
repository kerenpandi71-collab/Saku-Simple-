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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
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
    private val injectTouchPoints: (Int, IntArray, FloatArray, FloatArray) -> Boolean,
    private val onStatus: (String) -> Unit
) {
    private data class Point(val x: Float, val y: Float)
    private val prefs = context.getSharedPreferences("saku_gamepad_overlay", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "SakuGamepadInput").apply { isDaemon = true } }
    private val mobaKeys = listOf("STICK", "SKILL_1", "SKILL_2", "ULTIMATE", "BATTLE_SPELL", "BLINK", "ATTACK", "ITEM_CUSTOM", "RECALL", "REGEN", "FLICKER")
    private val keys get() = mobaKeys
    private val labels = mapOf(
        "STICK" to "Joystick gerak 360°", "SKILL_1" to "Skill 1", "SKILL_2" to "Skill 2",
        "ULTIMATE" to "Skill 3 / Ultimate", "BATTLE_SPELL" to "Battle Spell", "BLINK" to "Blink",
        "ATTACK" to "Serang dasar", "ITEM_CUSTOM" to "Item / tombol custom",
        "RECALL" to "Recall", "REGEN" to "Regen", "FLICKER" to "Flicker",
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
    private var analogPointerId = -1
    private var analogKnobDx = 0f
    private var analogKnobDy = 0f
    private val analogTravel = 0.12f
    private val activeTouchPoints = linkedMapOf<Int, Point>()
    private val controlPointerIds = mutableMapOf<String, Int>()
    private val physicalControls = mutableMapOf<Int, String>()
    private data class AimGesture(
        val control: String,
        val downX: Float,
        val downY: Float,
        val mappedOrigin: Point,
    )
    // Keep the saved mapping as the virtual finger's origin; drag direction
    // is derived from the physical finger's displacement on the gamepad.
    private val aimGestures = mutableMapOf<Int, AimGesture>()
    private val aimDragControls = setOf("SKILL_1", "SKILL_2", "ULTIMATE", "FLICKER")
    private val pendingTouchMove = AtomicReference<TouchFrame?>(null)
    private val touchMoveScheduled = AtomicBoolean(false)
    private data class TouchFrame(val action: Int, val ids: IntArray, val xs: FloatArray, val ys: FloatArray)
    private var dragPointerId = -1
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
        box.addView(TextView(context).apply {
            text = "Pilih fungsi → Letakkan mapping → sentuh lokasi tombol ML di preview. Setelah muncul, marker berlabel bisa diseret untuk mengoreksi posisi. Untuk joystick, letakkan di TENGAH joystick ML. Setiap tombol di panel punya mapping sendiri."
            setTextColor(Color.WHITE)
        })
        box.addView(TextView(context).apply {
            text = "SETIAP FUNGSI PUNYA TRIGGER SENDIRI — tekan Atur pada masing-masing tombol:"
            setTextColor(Color.WHITE)
            textSize = 12f
        })
        lateinit var dialog: AlertDialog
        val triggerList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        keys.forEach { key ->
            triggerList.addView(Button(context).apply {
                val state = if (targets.containsKey(key)) "✓" else "＋"
                text = state + "  " + (labels[key] ?: key) + "  •  Atur"
                isAllCaps = false
                setOnClickListener {
                    mappingKey = key
                    visible = true
                    prefs.edit().putBoolean("visible", true).apply()
                    view.visibility = View.VISIBLE
                    dialog.dismiss()
                    onStatus("Atur " + (labels[key] ?: key) + ": sentuh targetnya pada preview virtual display.")
                    Toast.makeText(context, "Sentuh posisi " + (labels[key] ?: key), Toast.LENGTH_LONG).show()
                    view.invalidate()
                }
            }, LinearLayout.LayoutParams(-1, dp(42)))
        }
        box.addView(ScrollView(context).apply {
            isFillViewport = true
            addView(triggerList)
        }, LinearLayout.LayoutParams(-1, dp(240)))
        box.addView(Button(context).apply {
            text = "Hapus semua mapping"
            isAllCaps = false
            setOnClickListener {
                targets.clear()
                saveTargets()
                view.invalidate()
                onStatus("Semua mapping dihapus. Pilih fungsi lalu letakkan ulang di preview.")
                Toast.makeText(context, "Mapping dihapus", Toast.LENGTH_SHORT).show()
            }
        })
        dialog = AlertDialog.Builder(context).setTitle("Gamepad MOBA • Mobile Legends").setView(box)
            .setPositiveButton("Simpan", null).setNeutralButton("Letakkan mapping", null).setNegativeButton("Tutup", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                mappingKey = null
                releaseAllTouchPoints()
                heightFraction = (0.22f + height.progress / 100f).coerceIn(0.22f, 0.58f)
                scale = (0.7f + size.progress / 100f).coerceIn(0.7f, 1.3f)
                transparencyPercent = (10 + transparency.progress).coerceIn(10, 90)
                prefs.edit().putFloat("height_fraction", heightFraction).putFloat("scale", scale)
                    .putInt("transparency", transparencyPercent).apply()
                view.invalidate()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val selectedKey = keys.firstOrNull() ?: return@setOnClickListener
                mappingKey = selectedKey
                visible = true
                prefs.edit().putBoolean("visible", true).apply()
                view.visibility = View.VISIBLE
                dialog.dismiss()
                onStatus("Atur " + (labels[selectedKey] ?: selectedKey) + ": sentuh lokasi tombolnya di preview, lalu seret marker jika perlu.")
                val hint = if (selectedKey == "STICK") "Sentuh pusat joystick ML; marker dapat diseret" else "Sentuh tombol ML untuk: " + (labels[selectedKey] ?: selectedKey)
                Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
                view.invalidate()
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.background_dark)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val physicalId = event.getPointerId(actionIndex)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                if (mappingKey != null && !panelRect.contains(x, y)) {
                    placeMapping(event, actionIndex)
                    return true
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val marker = targetHitRects.entries.firstOrNull { it.value.contains(x, y) }?.key
                    if (marker != null && mappingKey == null) {
                        draggingTargetKey = marker
                        dragPointerId = physicalId
                        return true
                    }
                    if (headerRect.contains(x, y) && panelRect.contains(x, y)) {
                        draggingPanel = true
                        dragPointerId = physicalId
                        dragRawX = event.rawX
                        dragRawY = event.rawY
                        startPanelX = panelX
                        startPanelY = panelY
                        return true
                    }
                }
                if (!panelRect.contains(x, y)) return mappingKey != null
                val hit = hitRects.entries.firstOrNull { it.value.contains(x, y) }?.key ?: return true
                if (hit == "STICK") {
                    if (analogPointer) return true
                    analogPointer = true
                    analogPointerId = physicalId
                    pressMappedControl("STICK")
                    updateAnalogFromFinger(x, y)
                } else {
                    physicalControls[physicalId] = hit
                    pressMappedControl(hit)
                    if (hit in aimDragControls) {
                        targets[hit]?.let { origin ->
                            aimGestures[physicalId] = AimGesture(hit, x, y, origin)
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val id = event.getPointerId(index)
                    val x = event.getX(index)
                    val y = event.getY(index)
                    if (id == analogPointerId && analogPointer) updateAnalogFromFinger(x, y)
                    val aim = aimGestures[id]
                    if (aim != null && aim.control in controlPointerIds) updateMappedAimFromFinger(aim, x, y)
                    if (id == dragPointerId) {
                        if (draggingTargetKey != null) moveTargetMarker(event, index)
                        else if (draggingPanel) movePanel(event, index)
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                if (physicalId == dragPointerId) {
                    if (draggingTargetKey != null) draggingTargetKey = null
                    if (draggingPanel) draggingPanel = false
                    dragPointerId = -1
                    return true
                }
                if (physicalId == analogPointerId && analogPointer) {
                    releaseMappedControl("STICK")
                    analogPointer = false
                    analogPointerId = -1
                    analogKnobDx = 0f
                    analogKnobDy = 0f
                    view.invalidate()
                    return true
                }
                aimGestures.remove(physicalId)
                val control = physicalControls.remove(physicalId)
                if (control != null) releaseMappedControl(control)
                if (mappingKey != null && !panelRect.contains(x, y)) placeMapping(event, actionIndex)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                physicalControls.clear()
                aimGestures.clear()
                analogPointer = false
                analogPointerId = -1
                analogKnobDx = 0f
                analogKnobDy = 0f
                draggingTargetKey = null
                draggingPanel = false
                dragPointerId = -1
                releaseAllTouchPoints()
                view.invalidate()
                return true
            }
        }
        return panelRect.contains(event.x, event.y) || mappingKey != null
    }

    private fun placeMapping(event: MotionEvent, index: Int) {
        val preview = findPreview(root)
        val key = mappingKey ?: return
        if (preview == null || preview.width <= 0 || preview.height <= 0) {
            mappingKey = null
            onStatus("Preview belum tersedia; mulai virtual display dahulu.")
            view.invalidate()
            return
        }
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
    }

    private fun moveTargetMarker(event: MotionEvent, index: Int) {
        val key = draggingTargetKey ?: return
        val preview = findPreview(root) ?: return
        if (preview.width <= 0 || preview.height <= 0) return
        val loc = IntArray(2)
        preview.getLocationOnScreen(loc)
        val sx = ((event.rawX - loc[0]) / preview.width).coerceIn(0f, 1f)
        val sy = ((event.rawY - loc[1]) / preview.height).coerceIn(0f, 1f)
        targets[key] = Point(sx, sy)
        saveTargets()
        view.invalidate()
    }

    private fun movePanel(event: MotionEvent, index: Int) {
        panelX = (startPanelX + (event.rawX - dragRawX) / max(1f, root.width.toFloat())).coerceIn(0f, 0.72f)
        panelY = (startPanelY + (event.rawY - dragRawY) / max(1f, root.height.toFloat())).coerceIn(0f, 0.9f)
        prefs.edit().putFloat("panel_x", panelX).putFloat("panel_y", panelY).apply()
        view.invalidate()
    }

    /** Dextop-style radial normalization, adapted to a virtual-display touch target. */
    private fun updateAnalogFromFinger(x: Float, y: Float) {
        val rect = hitRects["STICK"] ?: return
        val target = targets["STICK"] ?: Point(0.15f, 0.72f)
        val range = max(1f, min(rect.width(), rect.height()) * 0.42f)
        var vx = ((x - rect.centerX()) / range).coerceIn(-1f, 1f)
        var vy = ((y - rect.centerY()) / range).coerceIn(-1f, 1f)
        val magnitude = kotlin.math.sqrt(vx * vx + vy * vy)
        if (magnitude > 1f) { vx /= magnitude; vy /= magnitude }
        if (magnitude < 0.08f) { vx = 0f; vy = 0f }
        analogKnobDx = vx
        analogKnobDy = vy
        val nx = (target.x + vx * analogTravel).coerceIn(0f, 1f)
        val ny = (target.y + vy * analogTravel).coerceIn(0f, 1f)
        updateActiveTouchPoint("STICK", Point(nx, ny))
        view.invalidate()
    }


    /** Move the already-pressed virtual pointer around its saved mapping point. */
    private fun updateMappedAimFromFinger(gesture: AimGesture, x: Float, y: Float) {
        val rect = hitRects[gesture.control] ?: return
        val halfW = max(1f, rect.width() * 0.5f)
        val halfH = max(1f, rect.height() * 0.5f)
        // One full button radius reaches the full aiming radius. The target
        // remains anchored to the user's saved mapping, not the overlay's
        // screen coordinates, so moving the panel never shifts MLBB mapping.
        val aimRange = if (gesture.control == "FLICKER") 0.14f else 0.20f
        var dx = ((x - gesture.downX) / halfW).coerceIn(-1f, 1f)
        var dy = ((y - gesture.downY) / halfH).coerceIn(-1f, 1f)
        val magnitude = kotlin.math.sqrt(dx * dx + dy * dy)
        if (magnitude > 1f) { dx /= magnitude; dy /= magnitude }
        val nx = (gesture.mappedOrigin.x + dx * aimRange).coerceIn(0f, 1f)
        val ny = (gesture.mappedOrigin.y + dy * aimRange).coerceIn(0f, 1f)
        updateActiveTouchPoint(gesture.control, Point(nx, ny))
    }

    private fun pointerIdFor(key: String): Int = when (key) {
        "STICK" -> 0
        else -> (keys.indexOf(key) + 1).coerceAtLeast(1)
    }

    private fun pressMappedControl(key: String) {
        if (key in controlPointerIds) return
        val point = targets[key]
        if (point == null) {
            onStatus("Tombol " + (labels[key] ?: key) + " belum dipetakan. Buka pengaturan gamepad.")
            return
        }
        val id = pointerIdFor(key)
        if (activeTouchPoints.isEmpty()) {
            activeTouchPoints[id] = point
            dispatchTouchFrame(MotionEvent.ACTION_DOWN)
        } else {
            activeTouchPoints[id] = point
            val index = activeTouchPoints.keys.indexOf(id)
            dispatchTouchFrame(MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        }
        controlPointerIds[key] = id
    }

    private fun updateActiveTouchPoint(key: String, point: Point) {
        val id = controlPointerIds[key] ?: pointerIdFor(key)
        if (id !in activeTouchPoints) return
        activeTouchPoints[id] = point
        queueTouchMove()
    }

    private fun releaseMappedControl(key: String) {
        val id = controlPointerIds.remove(key) ?: pointerIdFor(key)
        if (id !in activeTouchPoints) return
        val ids = activeTouchPoints.keys.toList()
        val index = ids.indexOf(id)
        val action = if (ids.size == 1) MotionEvent.ACTION_UP else
            MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        dispatchTouchFrame(action)
        activeTouchPoints.remove(id)
    }

    private fun releaseAllTouchPoints() {
        if (activeTouchPoints.isEmpty()) return
        dispatchTouchFrame(MotionEvent.ACTION_CANCEL)
        activeTouchPoints.clear()
        controlPointerIds.clear()
    }

    private fun currentTouchFrame(action: Int): TouchFrame {
        val entries = activeTouchPoints.entries.toList()
        val size = displaySize()
        return TouchFrame(
            action,
            IntArray(entries.size) { entries[it].key },
            FloatArray(entries.size) { entries[it].value.x * (size.first - 1) },
            FloatArray(entries.size) { entries[it].value.y * (size.second - 1) }
        )
    }

    private fun dispatchTouchFrame(action: Int) {
        val frame = currentTouchFrame(action)
        io.execute {
            val accepted = injectTouchPoints(frame.action, frame.ids, frame.xs, frame.ys)
            if (!accepted) onStatus("Input gamepad gagal dikirim ke virtual display.")
        }
    }

    private fun queueTouchMove() {
        pendingTouchMove.set(currentTouchFrame(MotionEvent.ACTION_MOVE))
        if (!touchMoveScheduled.compareAndSet(false, true)) return
        io.execute {
            try {
                while (true) {
                    val frame = pendingTouchMove.getAndSet(null) ?: break
                    injectTouchPoints(frame.action, frame.ids, frame.xs, frame.ys)
                }
            } finally {
                touchMoveScheduled.set(false)
                if (pendingTouchMove.get() != null) queueTouchMove()
            }
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
        canvas.drawText("SAKU • ML PORTRAIT • SERET HEADER", headerRect.centerX(), headerRect.centerY() + dp(3).toFloat(), paint)
        paint.textAlign = Paint.Align.LEFT

        val r = min(ph * 0.115f, dp(40).toFloat() * scale)
        drawStick(canvas, left + pw * 0.17f, top + ph * 0.61f, r * 1.30f)
        // Skill cluster and large basic-attack button follow the supplied portrait reference.
        drawButton(canvas, "SKILL_1", left + pw * 0.68f, top + ph * 0.72f, r * 0.78f, "1", Color.rgb(64, 130, 255), "Skill 1")
        drawButton(canvas, "SKILL_2", left + pw * 0.77f, top + ph * 0.57f, r * 0.78f, "2", Color.rgb(153, 74, 255), "Skill 2")
        drawButton(canvas, "ULTIMATE", left + pw * 0.86f, top + ph * 0.39f, r * 0.94f, "ULT", Color.rgb(203, 93, 255), "Ultimate")
        drawButton(canvas, "ATTACK", left + pw * 0.88f, top + ph * 0.76f, r * 1.02f, "⚔", Color.rgb(55, 145, 255), "Serang")
        drawButton(canvas, "BATTLE_SPELL", left + pw * 0.56f, top + ph * 0.36f, r * 0.50f, "✦", Color.rgb(255, 190, 70), "Spell")
        drawButton(canvas, "BLINK", left + pw * 0.58f, top + ph * 0.53f, r * 0.48f, "➤", Color.rgb(75, 200, 255), "Blink")
        drawButton(canvas, "ITEM_CUSTOM", left + pw * 0.57f, top + ph * 0.70f, r * 0.46f, "+", Color.rgb(90, 210, 170), "Item")
        drawButton(canvas, "RECALL", left + pw * 0.34f, top + ph * 0.85f, r * 0.42f, "R", Color.rgb(80, 190, 255), "Recall")
        drawButton(canvas, "REGEN", left + pw * 0.43f, top + ph * 0.85f, r * 0.42f, "+", Color.rgb(75, 220, 115), "Regen")
        drawButton(canvas, "FLICKER", left + pw * 0.52f, top + ph * 0.85f, r * 0.42f, "✦", Color.rgb(255, 180, 65), "Flicker")

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
                val touchRadius = dp(24).toFloat()
                targetHitRects[key] = RectF(mx - touchRadius, my - touchRadius, mx + touchRadius, my + touchRadius)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(225, 28, 102, 230)
                canvas.drawCircle(mx, my, markerRadius, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); paint.color = Color.WHITE
                canvas.drawCircle(mx, my, markerRadius, paint)
                paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER; paint.textSize = dp(8).toFloat()
                val tag = when (key) {
                    "STICK" -> "◎"; "SKILL_1" -> "1"; "SKILL_2" -> "2"; "ULTIMATE" -> "ULT"
                    "BATTLE_SPELL" -> "SP"; "BLINK" -> "BL"; "ATTACK" -> "ATK"; "ITEM_CUSTOM" -> "IT"
                    "RECALL" -> "R"; "REGEN" -> "G"; "FLICKER" -> "F"; else -> key.take(1)
                }
                canvas.drawText(tag, mx, my + dp(3).toFloat(), paint)
                val caption = labels[key] ?: key
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = dp(8).toFloat()
                val labelWidth = paint.measureText(caption) + dp(10)
                val labelRect = RectF(mx - labelWidth / 2f, my + markerRadius + dp(2), mx + labelWidth / 2f, my + markerRadius + dp(17))
                paint.color = Color.argb(220, 5, 15, 32)
                canvas.drawRoundRect(labelRect, dp(5).toFloat(), dp(5).toFloat(), paint)
                paint.color = Color.WHITE
                canvas.drawText(caption, mx, labelRect.bottom - dp(4).toFloat(), paint)
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

    private fun drawStick(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        hitRects["STICK"] = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(150, 8, 20, 38)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.color = Color.argb(225, 100, 175, 255)
        canvas.drawCircle(cx, cy, radius * 0.96f, paint)
        canvas.drawCircle(cx, cy, radius * 0.78f, paint)
        canvas.drawCircle(cx, cy, radius * 0.60f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(35, 115, 220)
        val knobX = cx + analogKnobDx * radius * 0.48f
        val knobY = cy + analogKnobDy * radius * 0.48f
        canvas.drawCircle(knobX, knobY, radius * 0.43f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.color = Color.rgb(135, 210, 255)
        canvas.drawCircle(knobX, knobY, radius * 0.27f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(7).toFloat()
        canvas.drawText("360°", cx, cy + radius + dp(10).toFloat(), paint)
        paint.textAlign = Paint.Align.LEFT
        paint.style = Paint.Style.FILL
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
        releaseAllTouchPoints()
        io.shutdown()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
