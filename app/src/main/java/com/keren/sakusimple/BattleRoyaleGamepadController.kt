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

/** Standalone Battle Royale controller. It does not share MOBA mappings or preferences. */
class BattleRoyaleGamepadController(
    context: Context,
    root: FrameLayout,
    private val displaySize: () -> Pair<Int, Int>,
    private val inject: (Int, IntArray, FloatArray, FloatArray) -> Boolean
) {
    private val prefs = context.getSharedPreferences("saku_battle_royale_gamepad", Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sensitivity = prefs.getFloat("camera_sensitivity", 1.25f).coerceIn(0.35f, 3f)
    private var cameraSensitivity = sensitivity
    private val points = linkedMapOf<Int, Pair<Float, Float>>()
    private val physical = mutableMapOf<Int, Int>()
    private var visible = false
    private var cameraFinger = -1
    private var cameraLastX = 0f
    private var cameraLastY = 0f
    private var cameraX = 0.72f
    private var cameraY = 0.56f
    private var stickFinger = -1
    private val buttonIds = mapOf("FIRE" to 2, "ADS" to 3, "JUMP" to 4, "CROUCH" to 5,
        "RELOAD" to 6, "WEAPON" to 7, "INTERACT" to 8, "SPRINT" to 9)
    private val labels = listOf("FIRE", "ADS", "JUMP", "CROUCH", "RELOAD", "WEAPON", "INTERACT", "SPRINT")
    private val view = object : View(context) {
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (!visible) return
            val w = width.toFloat(); val h = height.toFloat()
            paint.color = Color.argb(105, 12, 22, 32)
            c.drawRoundRect(RectF(w * .53f, h * .17f, w * .98f, h * .82f), 24f, 24f, paint)
            paint.color = Color.argb(95, 20, 30, 42)
            c.drawCircle(w * .15f, h * .76f, min(w,h)*.105f, paint)
            paint.color = Color.argb(180, 220, 240, 255)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
            c.drawCircle(w * .15f, h * .76f, min(w,h)*.105f, paint)
            paint.style = Paint.Style.FILL
            drawLabel(c, "MOVE", w*.15f, h*.76f, 22f)
            paint.color = Color.argb(85, 80, 180, 255)
            c.drawRoundRect(RectF(w*.53f,h*.17f,w*.98f,h*.82f),24f,24f,paint)
            paint.color = Color.argb(210, 230, 245, 255)
            c.drawText("LOOK • GESER KAMERA", w*.57f, h*.22f, paint.apply { textSize=dp(11).toFloat() })
            val centers = buttonCenters(w,h)
            centers.forEach { (name, p) ->
                paint.color = if (name=="FIRE") Color.argb(190,190,55,45) else Color.argb(165,25,45,65)
                c.drawCircle(p.first,p.second,dp(if(name=="FIRE")  thirty else 25).toFloat(),paint)
                paint.color = Color.WHITE
                paint.textSize = dp(11).toFloat()
                c.drawText(name,p.first-paint.measureText(name)/2f,p.second+dp(4),paint)
            }
            paint.color = Color.argb(210,255,255,255)
            paint.textSize = dp(10).toFloat()
            c.drawText("BR", w*.03f, h*.08f, paint)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean = handle(e)
    }
    private val thirty = 30
    init {
        view.isClickable = true
        view.contentDescription = "Controller Battle Royale terpisah"
        root.addView(view, FrameLayout.LayoutParams(-1,-1))
        view.visibility = View.GONE
    }
    fun toggle() {
        visible = !visible
        view.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) releaseAll()
        Toast.makeText(view.context, if(visible) "Gamepad Battle Royale aktif" else "Gamepad Battle Royale disembunyikan", Toast.LENGTH_SHORT).show()
    }
    fun showSettings() {
        val seek = android.widget.SeekBar(view.context).apply {
            max=265; progress=((cameraSensitivity-.35f)*100).toInt().coerceIn(0,265)
        }
        android.app.AlertDialog.Builder(view.context)
            .setTitle("Gamepad Battle Royale")
            .setMessage("Controller ini terpisah dari profil MOBA. Area LOOK memakai geser kamera relatif; joystick kiri dan tombol aksi dapat ditekan bersamaan. Atur sensitivitas kamera:")
            .setView(seek)
            .setPositiveButton("Simpan") { _,_ ->
                cameraSensitivity=(.35f+seek.progress/100f).coerceIn(.35f,3f)
                prefs.edit().putFloat("camera_sensitivity",cameraSensitivity).apply()
            }.setNegativeButton("Tutup",null).show()
    }
    private fun buttonCenters(w:Float,h:Float): Map<String,Pair<Float,Float>> = mapOf(
        "FIRE" to (w*.91f to h*.40f), "ADS" to (w*.78f to h*.31f),
        "JUMP" to (w*.91f to h*.60f), "CROUCH" to (w*.78f to h*.68f),
        "RELOAD" to (w*.65f to h*.48f), "WEAPON" to (w*.65f to h*.68f),
        "INTERACT" to (w*.55f to h*.57f), "SPRINT" to (w*.34f to h*.76f)
    )
    private fun drawLabel(c:Canvas,s:String,x:Float,y:Float,size:Float) {
        paint.color=Color.WHITE; paint.textSize=dp(12).toFloat(); c.drawText(s,x-paint.measureText(s)/2f,y+dp(4),paint)
    }
    private fun handle(e:MotionEvent):Boolean {
        val idx=e.actionIndex.coerceIn(0,e.pointerCount-1); val id=e.getPointerId(idx)
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x=e.getX(idx); val y=e.getY(idx); val w=view.width.toFloat(); val h=view.height.toFloat()
                if(x<w*.34f && y>h*.57f) {
                    if(stickFinger<0) { stickFinger=id; physical[id]=1; updateStick(x,y) }
                } else if(x>w*.51f && y>h*.25f && y<h*.84f) {
                    if(cameraFinger<0) { cameraFinger=id; cameraLastX=x; cameraLastY=y; physical[id]=10; down(10,cameraX,cameraY) }
                } else {
                    val nearest=buttonCenters(w,h).minByOrNull { (_,p)->(p.first-x)*(p.first-x)+(p.second-y)*(p.second-y) }
                    if(nearest!=null && kotlin.math.hypot((nearest.value.first-x).toDouble(),(nearest.value.second-y).toDouble())<dp(48)) {
                        val key=buttonIds[nearest.key] ?: return true; physical[id]=key
                        val target=buttonTarget(nearest.key); down(key,target.first,target.second)
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for(i in 0 until e.pointerCount) {
                    val pid=e.getPointerId(i); val x=e.getX(i); val y=e.getY(i)
                    if(pid==stickFinger) updateStick(x,y)
                    if(pid==cameraFinger) {
                        val dx=x-cameraLastX; val dy=y-cameraLastY; cameraLastX=x; cameraLastY=y
                        cameraX=(cameraX+dx*cameraSensitivity/max(1f,view.width)*.9f).coerceIn(.05f,.95f)
                        cameraY=(cameraY+dy*cameraSensitivity/max(1f,view.height)*.9f).coerceIn(.05f,.95f)
                        move(10,cameraX,cameraY)
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                physical.remove(id)?.let { key ->
                    if(key==1) { up(1); stickFinger=-1 }
                    else if(key==10) { up(10); cameraFinger=-1 }
                    else up(key)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { releaseAll(); return true }
        }
        return true
    }
    private fun buttonTarget(name:String):Pair<Float,Float> = when(name) {
        "FIRE" -> .94f to .48f; "ADS" -> .82f to .39f; "JUMP" -> .94f to .68f
        "CROUCH" -> .82f to .76f; "RELOAD" -> .68f to .55f; "WEAPON" -> .68f to .75f
        "INTERACT" -> .58f to .64f; else -> .38f to .82f
    }
    private fun updateStick(x:Float,y:Float) {
        val cx=view.width*.15f; val cy=view.height*.76f; val radius=max(1f,min(view.width,view.height)*.105f)
        var dx=((x-cx)/radius).coerceIn(-1f,1f); var dy=((y-cy)/radius).coerceIn(-1f,1f)
        val mag=sqrt(dx*dx+dy*dy); if(mag>1f){dx/=mag;dy/=mag}
        val px=(.15f+dx*.12f).coerceIn(0f,1f); val py=(.76f+dy*.12f).coerceIn(0f,1f)
        setPoint(1,px,py)
    }
    private fun down(key:Int,x:Float,y:Float) {
        val action=if(points.isEmpty()) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN or (points.size shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        points[key]=x to y; send(action)
    }
    private fun move(key:Int,x:Float,y:Float) { if(key !in points)return; points[key]=x to y; send(MotionEvent.ACTION_MOVE) }
    private fun setPoint(key:Int,x:Float,y:Float) { if(key in points) move(key,x,y) else down(key,x,y) }
    private fun up(key:Int) {
        if(key !in points)return
        val index=points.keys.indexOf(key)
        val action=if(points.size==1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        send(action); points.remove(key)
    }
    private fun send(action:Int) {
        val (w,h)=displaySize(); if(w<=0||h<=0)return
        val ids=points.keys.toIntArray(); val xs=points.values.map{it.first*w}.toFloatArray(); val ys=points.values.map{it.second*h}.toFloatArray()
        inject(action,ids,xs,ys)
    }
    private fun releaseAll() { points.keys.toList().asReversed().forEach { up(it) }; physical.clear(); stickFinger=-1; cameraFinger=-1 }
    private fun dp(v:Int)= (v*view.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    fun close() { releaseAll(); (view.parent as? FrameLayout)?.removeView(view) }
}
