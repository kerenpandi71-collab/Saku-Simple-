package com.keren.sakusimple

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Display
import java.util.concurrent.TimeUnit
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Runs in Shizuku's shell/root UserService process.
 *
 * The UserService runs with Shizuku's granted identity, not the normal app UID. The platform
 * can still reject individual flags or input injection depending on Android/OEM permissions.
 * All failures are returned through getLastError() rather than silently reported as success.
 */
class ShizukuUserService() : IVirtualDisplayService.Stub() {
    private var serviceContext: Context? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var lastError: String = "Belum ada operasi."
    private var mouseButtonDownTime: Long = 0L
    private var touchDownTime: Long = 0L
    @Volatile private var virtualWidth: Int = 1280
    @Volatile private var virtualHeight: Int = 720
    private val injectedKeyDownTimes = mutableMapOf<Int, Long>()

    constructor(context: Context) : this() {
        serviceContext = context
    }

    @SuppressLint("WrongConstant")
    override fun createVirtualDisplay(
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface
    ): Int {
        releaseVirtualDisplay()
        virtualWidth = width.coerceIn(320, 2560)
        virtualHeight = height.coerceIn(320, 2560)
        val identity = Binder.clearCallingIdentity()
        return try {
            enableHiddenApiAccess()
            val context = serviceContext
                ?: throw IllegalStateException("Shizuku UserService tidak memberikan Context.")
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: throw IllegalStateException("DisplayManager tidak tersedia di UserService.")

            // TRUSTED is required on this device path for InputManager to accept injected
            // touchscreen events on the virtual display. Do not remove it to solve IME focus:
            // doing so causes Direct Touch and multi-pointer gestures to be rejected.
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                TRUSTED_FLAG or
                SUPPORTS_TOUCH_FLAG

            val created = manager.createVirtualDisplay(
                "SakuShizukuVirtualDisplay",
                width.coerceIn(320, 2560),
                height.coerceIn(320, 2560),
                densityDpi.coerceIn(120, 640),
                surface,
                flags
            ) ?: throw IllegalStateException("createVirtualDisplay mengembalikan null.")

            created.setSurface(surface)

            // Route the IME to the default display while keeping the focused editor on this
            // virtual display. This lets Gboard stay visible on the phone while typing directly
            // into the app window that owns focus on the virtual display.
            virtualDisplay = created
            val imeFallbackApplied = applyVirtualDisplayImePolicy(created.display.displayId)
            val imeFallbackDetail = lastError
            val display = created.display
            val stateName = when (display.state) {
                Display.STATE_ON -> "ON"
                Display.STATE_OFF -> "OFF"
                Display.STATE_DOZE -> "DOZE"
                Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
                Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
                else -> "UNKNOWN(${display.state})"
            }
            lastError = "Virtual display created • id=${display.displayId} • ${width.coerceIn(320, 2560)}×${height.coerceIn(320, 2560)} • state=$stateName • surfaceValid=${surface.isValid} • imeFallback=$imeFallbackApplied • $imeFallbackDetail"
            Log.i(TAG, "$lastError flags=0x${flags.toString(16)}")
            display.displayId
        } catch (error: Throwable) {
            lastError = "${error.javaClass.simpleName}: ${error.message ?: "tidak ada detail"}"
            Log.e(TAG, "Virtual display creation failed: $lastError", error)
            -1
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun applyVirtualDisplayImePolicy(displayId: Int): Boolean {
        // FALLBACK_DISPLAY=1: editor tetap berada di virtual display, sedangkan Gboard
        // ditampilkan pada display utama. Jangan gunakan HIDE karena itu memblokir IME.
        return runCatching {
            enableHiddenApiAccess()
            val windowManagerGlobal = Class.forName("android.view.WindowManagerGlobal")
            val windowManager = windowManagerGlobal.getMethod("getWindowManagerService").invoke(null)
                ?: throw IllegalStateException("WindowManagerService tidak tersedia")
            // Android's documented policy values are LOCAL=0, FALLBACK_DISPLAY=1,
            // HIDE=2. Prefer the framework constant, but keep FALLBACK_DISPLAY as the
            // compatibility value when an OEM hides the field from reflection. Falling
            // back to HIDE would suppress the keyboard instead of routing it to display 0.
            val policy = DISPLAY_IME_POLICY_FALLBACK_DISPLAY
            val iWindowManager = Class.forName("android.view.IWindowManager")
            val setPolicy = iWindowManager.getMethod(
                "setDisplayImePolicy",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )

            // Try the framework Binder API and the public shell command. Some OEM builds
            // accept the Binder call but silently keep LOCAL; the shell command is a
            // second supported route on Android builds that expose WindowManagerShellCommand.
            val binderAttempt = runCatching { setPolicy.invoke(windowManager, displayId, policy) }
            val shellAttempt = runShizukuShell(
                "cmd window set-display-ime-policy $displayId fallback",
                timeoutMs = 3500L,
                maxCharacters = 500
            )
            val readBack = runCatching {
                iWindowManager.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType)
                    .invoke(windowManager, displayId) as? Int
            }.getOrNull()
            val shellAccepted = shellAttempt.first == 0 &&
                !shellAttempt.second.contains("Error", ignoreCase = true) &&
                !shellAttempt.second.contains("Exception", ignoreCase = true)
            val applied = readBack == policy || (readBack == null && (shellAccepted || binderAttempt.isSuccess))
            lastError = if (applied) {
                "Gboard fallback aktif • virtual display=$displayId • target=display utama • policy=$policy • readBack=${readBack ?: "tidak tersedia"}"
            } else {
                "ROM belum menerapkan IME fallback • display=$displayId • diminta=$policy • terbaca=${readBack ?: "tidak tersedia"} • shell=${shellAttempt.second.trim().take(180)}"
            }
            Log.i(TAG, "$lastError • binder=${binderAttempt.isSuccess} • shellExit=${shellAttempt.first}")
            applied
        }.onFailure { error ->
            lastError = "Gagal mengaktifkan fallback keyboard Android • ${error.javaClass.simpleName}: ${error.message ?: "tanpa detail"}"
            Log.w(TAG, "Gagal mengatur IME fallback pada virtual display $displayId: ${error.message}", error)
        }.getOrDefault(false)
    }

    override fun applyImeFallback(displayId: Int): Boolean {
        val activeDisplayId = virtualDisplay?.display?.displayId ?: -1
        if (displayId < 0 || displayId != activeDisplayId) {
            lastError = "Fallback keyboard ditolak: display $displayId bukan display aktif ($activeDisplayId)."
            return false
        }
        val identity = Binder.clearCallingIdentity()
        return try {
            applyVirtualDisplayImePolicy(displayId)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun enableHiddenApiAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }

    private val inputManagerService: Any by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        enableHiddenApiAccess()
        val manager = Class.forName("android.os.ServiceManager")
        val binder = manager.getDeclaredMethod("getService", String::class.java)
            .apply { isAccessible = true }
            .invoke(null, "input") as? IBinder
            ?: throw IllegalStateException("InputManager Binder tidak tersedia")
        val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
        stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: throw IllegalStateException("IInputManager.asInterface mengembalikan null")
    }

    private val injectInputEventMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Class.forName("android.hardware.input.IInputManager").getMethod(
            "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
        )
    }

    private val setDisplayIdMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    /**
     * Some OEM InputManager implementations reject WAIT_FOR_RESULT for secondary-display
     * touch/mouse events even though they accept the same event in asynchronous mode.
     * Try queueing first, then use the synchronous mode as a compatibility fallback.
     */
    private fun injectEventCompat(event: InputEvent, label: String): Boolean {
        return try {
            val motion = event as? MotionEvent
            val continuousMotion = motion?.actionMasked == MotionEvent.ACTION_MOVE ||
                motion?.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                motion?.actionMasked == MotionEvent.ACTION_SCROLL
            val asyncAccepted = injectInputEventMethod.invoke(
                inputManagerService, event, INJECT_ASYNC
            ) as? Boolean ?: false
            if (asyncAccepted) {
                lastError = "$label diterima (async)"
                true
            } else {
                // Movement is continuous and may arrive every display frame. Never block
                // the gamepad worker waiting for an OEM to reject the same MOVE twice.
                if (continuousMotion) {
                    lastError = "$label ditolak InputManager • async=false • gerakan dilewati tanpa fallback sinkron"
                    return false
                }
                val waited = injectInputEventMethod.invoke(
                    inputManagerService, event, INJECT_WAIT_FOR_RESULT
                ) as? Boolean ?: false
                if (waited) {
                    lastError = "$label diterima (wait-for-result fallback)"
                    true
                } else {
                    // A few OEM InputManager builds reject queued/WAIT_FOR_RESULT events
                    // during pointer transitions on secondary displays. Retry only discrete
                    // transitions synchronously; doing this for every MOVE would add lag.
                    val transition = motion?.actionMasked?.let { action ->
                        action == MotionEvent.ACTION_DOWN ||
                            action == MotionEvent.ACTION_UP ||
                            action == MotionEvent.ACTION_CANCEL ||
                            action == MotionEvent.ACTION_POINTER_DOWN ||
                            action == MotionEvent.ACTION_POINTER_UP
                    } ?: false
                    val finishAccepted = if (transition) {
                        injectInputEventMethod.invoke(
                            inputManagerService, event, INJECT_WAIT_FOR_FINISH
                        ) as? Boolean ?: false
                    } else false
                    if (finishAccepted) {
                        lastError = "$label diterima (wait-for-finish fallback)"
                        true
                    } else {
                        lastError = "$label ditolak InputManager • async=false • wait-result=false" +
                            if (transition) " • wait-finish=false" else ""
                        Log.w(TAG, lastError)
                        false
                    }
                }
            }
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            lastError = "$label gagal • ${cause.javaClass.simpleName}: ${cause.message ?: "tanpa detail"}"
            Log.w(TAG, lastError, cause)
            false
        }
    }

    override fun injectHover(displayId: Int, x: Float, y: Float): Boolean {
        val point = parseDisplayPoint(displayId, x, y) ?: return false
        return injectMouseEvent(
            displayId, MotionEvent.ACTION_HOVER_MOVE, point.first.toFloat(), point.second.toFloat(),
            buttonState = 0, actionName = "MOUSE_MOVE"
        )
    }

    override fun injectTouchEvent(displayId: Int, action: Int, x: Float, y: Float): Boolean {
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE &&
            action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
        ) {
            lastError = "Aksi touchscreen tidak didukung: $action"
            return false
        }
        val now = SystemClock.uptimeMillis()
        val downTime = when (action) {
            MotionEvent.ACTION_DOWN -> now.also { touchDownTime = it }
            MotionEvent.ACTION_MOVE -> touchDownTime.takeIf { it > 0L } ?: now
            else -> touchDownTime.takeIf { it > 0L } ?: now
        }
        val accepted = injectTouchMotionEvent(displayId, action, x, y, downTime)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touchDownTime = 0L
        return accepted
    }

    override fun injectTouchPoints(displayId: Int, action: Int, pointerIds: IntArray, xs: FloatArray, ys: FloatArray): Boolean {
        val actionMask = action and MotionEvent.ACTION_MASK
        if (pointerIds.size == 1 && xs.size == 1 && ys.size == 1 &&
            (actionMask == MotionEvent.ACTION_POINTER_DOWN || actionMask == MotionEvent.ACTION_POINTER_UP)
        ) {
            val normalizedAction = if (actionMask == MotionEvent.ACTION_POINTER_DOWN) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
            return injectTouchEvent(displayId, normalizedAction, xs[0], ys[0])
        }
        if (pointerIds.isEmpty() || pointerIds.size > 5 || pointerIds.size != xs.size || pointerIds.size != ys.size) {
            lastError = "Multi-touch perlu 1-5 pointer dengan array sama panjang."
            return false
        }
        val masked = action and MotionEvent.ACTION_MASK
        if (masked !in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP)) {
            lastError = "Aksi multi-touch tidak valid: $action"
            return false
        }
        val originalActionIndex = (action and MotionEvent.ACTION_POINTER_INDEX_MASK) shr MotionEvent.ACTION_POINTER_INDEX_SHIFT
        val actionPointerId = if (masked == MotionEvent.ACTION_POINTER_DOWN || masked == MotionEvent.ACTION_POINTER_UP) {
            pointerIds.getOrNull(originalActionIndex)
        } else null
        if ((masked == MotionEvent.ACTION_POINTER_DOWN || masked == MotionEvent.ACTION_POINTER_UP) && actionPointerId == null) {
            lastError = "Multi-touch action index tidak cocok dengan pointer."
            return false
        }
        // Android expects pointer properties ordered by pointer ID; host pointer indices can change.
        val order = pointerIds.indices.sortedBy { pointerIds[it] }
        val sortedIds = order.map { pointerIds[it] }
        if (sortedIds.distinct().size != sortedIds.size) {
            lastError = "Multi-touch berisi pointer ID ganda."
            return false
        }
        val sortedAction = if (actionPointerId != null) {
            masked or (sortedIds.indexOf(actionPointerId) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else masked
        val now = SystemClock.uptimeMillis()
        val downTime = if (masked == MotionEvent.ACTION_DOWN) now.also { touchDownTime = it } else touchDownTime.takeIf { it > 0L } ?: now
        val points = ArrayList<Pair<Int, Int>>(pointerIds.size)
        for (index in order) points.add(parseDisplayPoint(displayId, xs[index], ys[index]) ?: return false)
        var event: MotionEvent? = null
        val identity = Binder.clearCallingIdentity()
        try {
            val props = Array(sortedIds.size) { i -> MotionEvent.PointerProperties().apply {
                id = sortedIds[i].coerceIn(0, 31)
                toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coords = Array(sortedIds.size) { i -> MotionEvent.PointerCoords().apply {
                x = points[i].first.toFloat()
                y = points[i].second.toFloat()
                pressure = if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) 0f else 1f
                size = 1f
            } }
            event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), sortedAction, sortedIds.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            setDisplayIdMethod.invoke(event, displayId)
            val accepted = injectEventCompat(event, "MULTITOUCH")
            if (accepted) lastError = "MULTITOUCH accepted • display=$displayId • pointers=${sortedIds.size} • action=$sortedAction"
            return accepted
        } catch (error: Throwable) {
            lastError = "Multi-touch gagal • ${error.javaClass.simpleName}: ${error.message}"
            Log.w(TAG, lastError, error)
            return false
        } finally {
            runCatching { event?.recycle() }
            Binder.restoreCallingIdentity(identity)
            if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) touchDownTime = 0L
        }
    }

    override fun injectTouchClick(displayId: Int, x: Float, y: Float): Boolean {
        val down = injectTouchEvent(displayId, MotionEvent.ACTION_DOWN, x, y)
        if (!down) return false
        SystemClock.sleep(35L)
        return injectTouchEvent(displayId, MotionEvent.ACTION_UP, x, y)
    }

    private fun injectTouchMotionEvent(
        displayId: Int, action: Int, x: Float, y: Float, downTime: Long
    ): Boolean {
        val point = parseDisplayPoint(displayId, x, y) ?: return false
        var event: MotionEvent? = null
        val identity = Binder.clearCallingIdentity()
        try {
            val properties = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val coordinates = MotionEvent.PointerCoords().apply {
                this.x = point.first.toFloat()
                this.y = point.second.toFloat()
                pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else 1f
                size = 1f
            }
            event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, 1,
                arrayOf(properties), arrayOf(coordinates), 0, 0,
                1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            setDisplayIdMethod.invoke(event, displayId)
            val accepted = injectEventCompat(event, "TOUCH")
            if (accepted) lastError = "TOUCH accepted • display=$displayId • action=$action • x=${point.first} y=${point.second}"
            return accepted
        } catch (error: Throwable) {
            lastError = "TOUCH gagal • ${error.javaClass.simpleName}: ${error.message}"
            Log.w(TAG, lastError, error)
            return false
        } finally {
            runCatching { event?.recycle() }
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun injectMouseClick(displayId: Int, x: Float, y: Float): Boolean {
        val point = parseDisplayPoint(displayId, x, y) ?: return false
        val identity = Binder.clearCallingIdentity()
        return try {
            val downTime = SystemClock.uptimeMillis()
            val down = injectMouseEvent(
                displayId, MotionEvent.ACTION_DOWN, point.first.toFloat(), point.second.toFloat(),
                buttonState = MotionEvent.BUTTON_PRIMARY, actionName = "MOUSE_DOWN", downTime = downTime
            )
            if (!down) {
                val mouseFailure = lastError
                val command = "input -d $displayId tap ${point.first} ${point.second}"
                if (runInputCommand(displayId, command, "MOUSE_CLICK_FALLBACK")) {
                    lastError = "MOUSE_CLICK accepted by shell tap fallback • display=$displayId • x=${point.first} y=${point.second}"
                    return true
                }
                val tapFailure = lastError
                lastError = "MOUSE_CLICK denied by InputManager and shell fallback • mouse=[$mouseFailure] • tap=[$tapFailure]"
                Log.w(TAG, lastError)
                return false
            }
            // Keep DOWN and UP in separate dispatch turns; some OEMs drop an immediate pair.
            SystemClock.sleep(MOUSE_CLICK_HOLD_MS)
            val up = injectMouseEvent(
                displayId, MotionEvent.ACTION_UP, point.first.toFloat(), point.second.toFloat(),
                buttonState = 0, actionName = "MOUSE_UP", downTime = downTime
            )
            lastError = if (up) {
                "MOUSE_CLICK accepted • display=$displayId • x=${point.first} y=${point.second}"
            } else {
                "MOUSE_CLICK release rejected • display=$displayId • x=${point.first} y=${point.second} • ${lastError}"
            }
            if (!up) Log.w(TAG, lastError)
            up
        } catch (error: Throwable) {
            lastError = "MOUSE_CLICK error • ${error.javaClass.simpleName}: ${error.message}"
            Log.w(TAG, lastError, error)
            false
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun injectMouseButton(displayId: Int, action: Int, x: Float, y: Float): Boolean {
        val point = parseDisplayPoint(displayId, x, y) ?: return false
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE &&
            action != MotionEvent.ACTION_UP
        ) {
            lastError = "Aksi mouse tidak didukung: $action"
            return false
        }
        val now = SystemClock.uptimeMillis()
        val downTime = when (action) {
            MotionEvent.ACTION_DOWN -> now.also { mouseButtonDownTime = it }
            MotionEvent.ACTION_MOVE -> mouseButtonDownTime.takeIf { it > 0L } ?: now
            else -> mouseButtonDownTime.takeIf { it > 0L } ?: now
        }
        val pressed = action == MotionEvent.ACTION_DOWN ||
            (action == MotionEvent.ACTION_MOVE && mouseButtonDownTime > 0L)
        val accepted = injectMouseEvent(
            displayId, action, point.first.toFloat(), point.second.toFloat(),
            buttonState = if (pressed) MotionEvent.BUTTON_PRIMARY else 0,
            actionName = when (action) {
                MotionEvent.ACTION_DOWN -> "MOUSE_BUTTON_DOWN"
                MotionEvent.ACTION_MOVE -> "MOUSE_BUTTON_MOVE"
                else -> "MOUSE_BUTTON_UP"
            },
            downTime = downTime
        )
        if (action == MotionEvent.ACTION_UP) mouseButtonDownTime = 0L
        if (accepted) lastError = "Mouse button event diterima • display=$displayId • action=$action • x=${point.first} y=${point.second}"
        return accepted
    }

    override fun injectKeyEvents(
        displayId: Int, actions: IntArray, keyCodes: IntArray, metaStates: IntArray
    ): Boolean {
        if (displayId < 0 || displayId > 10000) {
            lastError = "Keyboard input ditolak: display ID tidak valid ($displayId)."
            return false
        }
        val activeDisplayId = virtualDisplay?.display?.displayId ?: -1
        if (displayId != activeDisplayId) {
            lastError = "Keyboard input ditolak: target display=$displayId, virtual display aktif=$activeDisplayId. Buat ulang display lalu coba lagi."
            return false
        }
        if (actions.size != keyCodes.size || actions.size != metaStates.size) {
            lastError = "Keyboard input ditolak: ukuran array event tidak sama."
            return false
        }
        if (actions.isEmpty()) return true
        val identity = Binder.clearCallingIdentity()
        try {
            enableHiddenApiAccess()
            var allAccepted = true
            for (index in actions.indices) {
                val action = actions[index]
                val code = keyCodes[index]
                if (action != android.view.KeyEvent.ACTION_DOWN && action != android.view.KeyEvent.ACTION_UP) {
                    allAccepted = false
                    lastError = "KeyEvent batch ditolak • index=$index • action tidak valid=$action"
                    break
                }
                val now = SystemClock.uptimeMillis()
                val downTime = if (action == android.view.KeyEvent.ACTION_DOWN) {
                    now.also { injectedKeyDownTimes[code] = it }
                } else {
                    injectedKeyDownTimes.remove(code) ?: now
                }
                val event = android.view.KeyEvent(downTime, now, action, code, 0, metaStates[index])
                setDisplayIdMethod.invoke(event, displayId)
                // Async injection avoids waiting for the target app to process every key event.
                // Prefer asynchronous injection, but retry rejected events in wait mode for
                // ROMs whose InputManager refuses ASYNC key injection on secondary displays.
                var accepted = injectInputEventMethod.invoke(inputManagerService, event, INJECT_ASYNC) as? Boolean ?: false
                if (!accepted) {
                    accepted = injectInputEventMethod.invoke(inputManagerService, event, INJECT_WAIT_FOR_RESULT) as? Boolean ?: false
                }
                if (!accepted) {
                    allAccepted = false
                        lastError = "KeyEvent ditolak InputManager • display=$displayId • index=$index/${actions.lastIndex} • action=$action keyCode=$code • batch mungkin hanya terkirim sebagian"
                    Log.w(TAG, lastError)
                }
                if (!allAccepted) break
            }
            if (allAccepted) lastError = "Keyboard KeyEvent diterima • display=$displayId • count=${actions.size}"
            return allAccepted
        } catch (error: Throwable) {
            lastError = "Keyboard injection gagal • ${error.javaClass.simpleName}: ${error.message}"
            Log.w(TAG, lastError, error)
            return false
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun injectMouseScroll(
        displayId: Int, x: Float, y: Float, horizontal: Float, vertical: Float
    ): Boolean {
        val point = parseDisplayPoint(displayId, x, y) ?: return false
        if (!horizontal.isFinite() || !vertical.isFinite()) return false
        if (kotlin.math.abs(horizontal) < 0.001f && kotlin.math.abs(vertical) < 0.001f) return true
        val identity = Binder.clearCallingIdentity()
        return try {
            val accepted = injectMouseEvent(
                displayId, MotionEvent.ACTION_SCROLL, point.first.toFloat(), point.second.toFloat(),
                buttonState = 0, actionName = "MOUSE_SCROLL",
                horizontalScroll = horizontal.coerceIn(-8f, 8f),
                verticalScroll = vertical.coerceIn(-8f, 8f)
            )
            if (accepted) lastError = "MOUSE_SCROLL accepted • display=$displayId • h=$horizontal v=$vertical"
            accepted
        } catch (error: Throwable) {
            lastError = "MOUSE_SCROLL error • ${error.javaClass.simpleName}: ${error.message}"
            Log.w(TAG, lastError, error)
            false
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun resizeVirtualDisplay(width: Int, height: Int, densityDpi: Int, surface: Surface): Boolean {
        val identity = Binder.clearCallingIdentity()
        return try {
            val display = virtualDisplay ?: throw IllegalStateException("Virtual display belum dibuat.")
            if (!surface.isValid) throw IllegalArgumentException("Surface tujuan tidak valid.")
            val safeWidth = width.coerceIn(320, 2560)
            val safeHeight = height.coerceIn(320, 2560)
            val safeDpi = densityDpi.coerceIn(120, 640)
            display.resize(safeWidth, safeHeight, safeDpi)
            display.setSurface(surface)
            virtualWidth = safeWidth
            virtualHeight = safeHeight
            lastError = "Virtual display diubah tanpa release • id=${display.display.displayId} • ${safeWidth}×${safeHeight} • ${safeDpi} DPI"
            Log.i(TAG, lastError)
            true
        } catch (error: Throwable) {
            lastError = "Resize virtual display gagal: ${error.javaClass.simpleName}: ${error.message ?: "tanpa detail"}"
            Log.e(TAG, lastError, error)
            false
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun setVirtualDisplaySurface(surface: Surface): Boolean {
        val identity = Binder.clearCallingIdentity()
        return try {
            if (!surface.isValid) throw IllegalArgumentException("Surface tujuan tidak valid")
            val display = virtualDisplay ?: throw IllegalStateException("Virtual display belum dibuat")
            display.setSurface(surface)
            lastError = "Virtual display output surface diperbarui • surfaceValid=${surface.isValid}"
            Log.i(TAG, lastError)
            true
        } catch (error: Throwable) {
            lastError = "Gagal memperbarui surface: ${error.javaClass.simpleName}: ${error.message}"
            Log.w(TAG, lastError, error)
            false
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun injectMouseEvent(
        displayId: Int, action: Int, x: Float, y: Float, buttonState: Int,
        actionName: String, horizontalScroll: Float = 0f, verticalScroll: Float = 0f,
        downTime: Long = SystemClock.uptimeMillis()
    ): Boolean {
        var event: MotionEvent? = null
        val identity = Binder.clearCallingIdentity()
        try {
            val properties = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            }
            val coordinates = MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = if (action == MotionEvent.ACTION_DOWN) 1f else 0f
                size = 1f
                setAxisValue(MotionEvent.AXIS_HSCROLL, horizontalScroll)
                setAxisValue(MotionEvent.AXIS_VSCROLL, verticalScroll)
            }
            event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, 1,
                arrayOf(properties), arrayOf(coordinates), 0, buttonState,
                1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
            )
            setDisplayIdMethod.invoke(event, displayId)
            val accepted = injectEventCompat(event, actionName)
            return accepted
        } catch (error: Throwable) {
            lastError = "$actionName gagal • ${error.javaClass.simpleName}: ${error.message ?: "tanpa detail"}"
            if (actionName != "MOUSE_MOVE") Log.w(TAG, lastError, error)
            return false
        } finally {
            runCatching { event?.recycle() }
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun parseDisplayPoint(displayId: Int, x: Float, y: Float): Pair<Int, Int>? {
        if (displayId < 0 || displayId > 10000) {
            lastError = "Input ditolak: display ID tidak valid ($displayId)."
            return null
        }
        val activeDisplayId = virtualDisplay?.display?.displayId ?: -1
        if (displayId != activeDisplayId) {
            lastError = "Input ditolak: target display=$displayId, virtual display aktif=$activeDisplayId. Buat ulang display lalu coba lagi."
            return null
        }
        if (!x.isFinite() || !y.isFinite()) {
            lastError = "Input ditolak: koordinat bukan angka valid."
            return null
        }
        return x.toInt().coerceIn(0, (virtualWidth - 1).coerceAtLeast(0)) to y.toInt().coerceIn(0, (virtualHeight - 1).coerceAtLeast(0))
    }

    private fun runInputCommand(displayId: Int, command: String, action: String): Boolean {
        val result = runShizukuShell(command, timeoutMs = 4000L, maxCharacters = 900)
        val loggedCommand = redactInputText(command)
        val accepted = result.first == 0 &&
            !result.second.contains("Error:", ignoreCase = true) &&
            !result.second.contains("Exception", ignoreCase = true) &&
            !result.second.contains("Unknown command", ignoreCase = true)
        lastError = if (accepted) {
            "$action shell OK • display=$displayId • cmd=$loggedCommand • exit=${result.first} • ${result.second}"
        } else {
            "$action shell GAGAL • display=$displayId • cmd=$loggedCommand • exit=${result.first} • ${result.second}"
        }
        if (accepted) Log.i(TAG, lastError) else Log.w(TAG, lastError)
        return accepted
    }

    private fun redactInputText(command: String): String = Regex("(?i)^(input\\s+-d\\s+\\d+\\s+text\\s+).*$")
        .replace(command) { it.groupValues[1] + "'<teks-disamarkan>'" }

    override fun runShellCommand(command: String): Boolean {
        // Never accept arbitrary shell code; only allow input commands and the two existing Pixelboard actions.
        val safeCommand = command.trim()
        val pixelboardCommands = setOf(
            "pm disable-user --user 0 com.akshaykadam.pixelboard",
            "pm enable --user 0 com.akshaykadam.pixelboard"
        )
        if (safeCommand in pixelboardCommands) {
            val result = runShizukuShell(safeCommand, timeoutMs = 4000L, maxCharacters = 900)
            val accepted = result.first == 0 &&
                !result.second.contains("Error", ignoreCase = true) &&
                !result.second.contains("Exception", ignoreCase = true)
            lastError = if (accepted) {
                "PIXELBOARD shell OK • cmd=$safeCommand • exit=${result.first} • ${result.second}"
            } else {
                "PIXELBOARD shell GAGAL • cmd=$safeCommand • exit=${result.first} • ${result.second}"
            }
            if (accepted) Log.i(TAG, lastError) else Log.w(TAG, lastError)
            return accepted
        }
        val displayPrefix = Regex("""^input -d ([0-9]{1,5}) """).find(safeCommand)
        val tail = displayPrefix?.let { safeCommand.substring(it.range.last + 1) }.orEmpty()
        val isKeyEvent = tail == "keyevent 67" || tail == "keyevent 66" || tail == "keyevent 112" || tail == "keyevent 279"
        val tapMatch = Regex("""^tap ([0-9]{1,4}) ([0-9]{1,4})$""").matchEntire(tail)
        val isText = tail.startsWith("text '") && tail.endsWith("'") &&
            !tail.contains('\n') && !tail.contains(';') && !tail.contains('`') &&
            tail.removePrefix("text '").removeSuffix("'").replace("'\\''", "").none { it == '\'' }
        val allowed = displayPrefix != null && (isKeyEvent || isText || tapMatch != null)
        if (!allowed) {
            lastError = "Perintah shell ditolak: hanya tap numerik terbatas, input teks/keyboard dan dua aksi Pixelboard yang diizinkan."
            return false
        }
        val displayMatch = Regex("""^input -d ([0-9]{1,5}) """).find(safeCommand)
        val displayId = displayMatch?.groupValues?.get(1)?.toIntOrNull() ?: -1
        if (displayId !in 0..10000) {
            lastError = "Display ID tidak valid untuk input teks."
            return false
        }
        if (tapMatch != null) {
            val activeDisplayId = virtualDisplay?.display?.displayId ?: -1
            val tapX = tapMatch.groupValues[1].toIntOrNull() ?: -1
            val tapY = tapMatch.groupValues[2].toIntOrNull() ?: -1
            if (displayId != activeDisplayId || tapX !in 0 until virtualWidth || tapY !in 0 until virtualHeight) {
                lastError = "Fallback tap ditolak: display/koordinat di luar virtual display aktif."
                return false
            }
        }
        return runInputCommand(displayId, safeCommand, if (tapMatch != null) "MOUSE_CLICK_FALLBACK" else "IME_TEXT")
    }

    override fun launchAppOnDisplay(displayId: Int, packageName: String?, activityName: String?): String {
        val safePackage = packageName.orEmpty()
        val safeActivity = activityName.orEmpty()
        if (!safePackage.matches(Regex("""^[A-Za-z0-9_.]+$""")) ||
            !safeActivity.matches(Regex("""^[A-Za-z0-9_.$]+$"""))
        ) {
            lastError = "Peluncuran ditolak: nama paket atau activity tidak valid."
            return lastError
        }
        val activeDisplayId = virtualDisplay?.display?.displayId ?: -1
        if (displayId != activeDisplayId) {
            lastError = "Peluncuran ditolak: display $displayId bukan virtual display Saku yang aktif ($activeDisplayId)."
            return lastError
        }
        val componentClass = when {
            safeActivity.startsWith(".") -> safePackage + safeActivity
            !safeActivity.contains(".") -> "$safePackage.$safeActivity"
            else -> safeActivity
        }
        val componentArgument = "'$safePackage/$componentClass'"
        val result = runShizukuShell(
            "/system/bin/am start --display $displayId -n $componentArgument",
            timeoutMs = 8000L,
            maxCharacters = 1200
        )
        val accepted = result.first == 0 &&
            !result.second.contains("Error", ignoreCase = true) &&
            !result.second.contains("Exception", ignoreCase = true)
        val launchDetail = if (accepted) {
            "Game diluncurkan ke virtual display $displayId • ${result.second.trim()}"
        } else {
            "Game gagal diluncurkan ke virtual display $displayId • exit=${result.first} • ${result.second.trim()}"
        }
        // Re-apply after the target activity has attached its window. Several Android 14/15
        // vendor builds reset the IME policy during activity launch/display focus changes.
        val imeApplied = if (accepted) {
            SystemClock.sleep(280L)
            applyVirtualDisplayImePolicy(displayId)
        } else false
        val imeDetail = lastError
        lastError = if (accepted) {
            "$launchDetail • imeFallback=$imeApplied • $imeDetail"
        } else launchDetail
        if (accepted) Log.i(TAG, lastError) else Log.w(TAG, lastError)
        return lastError
    }

    override fun releaseVirtualDisplay() {
        val identity = Binder.clearCallingIdentity()
        try {
            val oldDisplayId = virtualDisplay?.display?.displayId
            virtualDisplay?.release()
            virtualDisplay = null
            oldDisplayId?.let { Log.i(TAG, "Virtual display ditutup • display=$it • kebijakan IME khusus display berakhir bersama display.") }
            lastError = "Virtual display ditutup."
        } catch (error: Throwable) {
            lastError = "${error.javaClass.simpleName}: ${error.message ?: "gagal menutup display"}"
            Log.e(TAG, "Release virtual display failed", error)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun runShizukuShell(
        command: String,
        timeoutMs: Long = 4500L,
        maxCharacters: Int = 1400
    ): Pair<Int, String> {
        var process: Process? = null
        val output = StringBuilder()
        return try {
            process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val proc = process
            val reader = Thread({
                try {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(output) {
                                if (output.length < maxCharacters) output.append(line).append('\n')
                            }
                        }
                    }
                } catch (error: Throwable) {
                    synchronized(output) {
                        if (output.length < maxCharacters) output.append("reader error: ").append(error.message).append('\n')
                    }
                }
            }, "SakuShellOutput").apply { isDaemon = true; start() }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join(350L)
                val partial = synchronized(output) { output.toString().trim() }
                return -1 to "TIMEOUT after ${timeoutMs}ms • ${partial.ifBlank { "no output" }}"
            }
            reader.join(350L)
            val captured = synchronized(output) { output.toString().trim() }
            process.exitValue() to captured.ifBlank { "(output kosong)" }
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            val detail = "${cause.javaClass.simpleName}: ${cause.message ?: "tanpa detail"}\n${Log.getStackTraceString(cause)}"
            Log.e(TAG, "Shell command failed: ${redactInputText(command)}", cause)
            -1 to detail.take(maxCharacters)
        } finally {
            runCatching { process?.destroy() }
        }
    }

    override fun getDisplayFps(displayId: Int, packageName: String?): Float {
        if (displayId < 0 || displayId > 10000) return -1f
        val safePackage = packageName.orEmpty()
        if (!safePackage.matches(Regex("""^[A-Za-z0-9_.]+$"""))) return -1f
        return try {
            val layersResult = runShizukuShell(
                "/system/bin/dumpsys SurfaceFlinger --list",
                timeoutMs = 4000L,
                maxCharacters = 24000
            )
            if (layersResult.first != 0) return -1f
            val layers = layersResult.second.lineSequence()
                .map { it.trim() }
                .filter { it.contains(safePackage) }
                .sortedByDescending { if (it.contains("SurfaceView", true) || it.contains("BLAST", true)) 1 else 0 }
                .take(6)
                .toList()
            val nowNs = System.nanoTime()
            for (layer in layers) {
                val quotedLayer = layer.replace("'", "'\\''")
                val latency = runShizukuShell(
                    "/system/bin/dumpsys SurfaceFlinger --latency '$quotedLayer'",
                    timeoutMs = 3000L,
                    maxCharacters = 12000
                )
                if (latency.first != 0) continue
                val frameTimes = latency.second.lineSequence().drop(1).mapNotNull { line ->
                    val values = line.trim().split(Regex("""\s+""")).mapNotNull { it.toLongOrNull() }
                    values.getOrNull(1)?.takeIf { it > 0L }
                }.filter { it <= nowNs && nowNs - it <= 1_500_000_000L }.toList()
                if (frameTimes.size >= 2) {
                    val span = ((frameTimes.maxOrNull() ?: nowNs) - (frameTimes.minOrNull() ?: nowNs)).coerceAtLeast(1L)
                    return (frameTimes.size * 1_000_000_000f / span).coerceIn(1f, 240f)
                }
            }
            -1f
        } catch (error: Throwable) {
            Log.w(TAG, "getDisplayFps failed", error)
            -1f
        }
    }

    override fun getDisplayDiagnostics(displayId: Int): String {
        return try {
            if (displayId < 0 || displayId > 10000) return "Display ID tidak valid: $displayId"
            val context = serviceContext ?: return "UserService Context tidak tersedia."
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = manager.getDisplay(displayId) ?: virtualDisplay?.display
                ?: return "display=$displayId tidak ditemukan."
            val stateName = when (display.state) {
                Display.STATE_ON -> "ON"
                Display.STATE_OFF -> "OFF"
                Display.STATE_DOZE -> "DOZE"
                Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
                Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
                else -> "UNKNOWN(${display.state})"
            }
            val mode = display.mode
            val surfaceValid = virtualDisplay?.surface?.isValid ?: false
            val windowCommand =
                "dumpsys window displays | grep -E -A 8 -B 2 'displayId=$displayId|Display $displayId|mDisplayId=$displayId' | head -n 42"
            val activityCommand =
                "dumpsys activity activities | grep -E -A 3 -B 2 'displayId=$displayId|display #$displayId|mDisplayId=$displayId|com.android.chrome|org.mozilla.firefox' | head -n 52"
            val windowResult = runShizukuShell(windowCommand, timeoutMs = 4500L, maxCharacters = 1900)
            val activityResult = runShizukuShell(activityCommand, timeoutMs = 4500L, maxCharacters = 1900)
            val result = buildString {
                append("Display diagnostics • id=${display.displayId} state=$stateName ")
                append("mode=${mode.physicalWidth}x${mode.physicalHeight} surfaceValid=$surfaceValid\n")
                append("--- dumpsys window displays (exit=${windowResult.first}) ---\n")
                append(windowResult.second.ifBlank { "(no matching display lines)" }.take(1900))
                append("\n--- dumpsys activity activities (exit=${activityResult.first}) ---\n")
                append(activityResult.second.ifBlank { "(no matching activity lines)" }.take(1900))
            }
            Log.i(TAG, result)
            result.take(4600)
        } catch (error: Throwable) {
            val detail = "${error.javaClass.simpleName}: ${error.message ?: "tanpa detail"}\n${Log.getStackTraceString(error)}"
            Log.e(TAG, "Display diagnostics failed", error)
            "Diagnostics gagal: $detail".take(2400)
        }
    }

    override fun getLastError(): String = lastError

    override fun destroy() {
        releaseVirtualDisplay()
        System.exit(0)
    }

    companion object {
        private const val TAG = "SakuShizukuService"
        private const val SUPPORTS_TOUCH_FLAG = 1 shl 6
        private const val OWN_CONTENT_ONLY_FLAG = 1 shl 8
        // Hidden Android flag; not exposed by the compile-time SDK.
        private const val TRUSTED_FLAG = 1 shl 10
        private const val INJECT_ASYNC = 0
        private const val INJECT_WAIT_FOR_RESULT = 1
        private const val INJECT_WAIT_FOR_FINISH = 2
        private const val MOUSE_CLICK_HOLD_MS = 45L
        private const val DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1
        private const val DISPLAY_IME_POLICY_HIDE = 2
    }
}
