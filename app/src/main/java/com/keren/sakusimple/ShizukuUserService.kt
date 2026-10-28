package com.keren.sakusimple

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Binder
import android.os.IBinder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.Display
import java.lang.reflect.Method
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.math.max
import kotlin.math.min

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
    private val inputThread = HandlerThread("SakuShizukuInput").apply { start() }
    private val inputHandler = Handler(inputThread.looper)

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
        val identity = Binder.clearCallingIdentity()
        return try {
            enableHiddenApiAccess()
            val context = serviceContext
                ?: throw IllegalStateException("Shizuku UserService tidak memberikan Context.")
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: throw IllegalStateException("DisplayManager tidak tersedia di UserService.")

            // Flags from AOSP DisplayManager:
            // PUBLIC=1, PRESENTATION=2, SUPPORTS_TOUCH=(1 shl 6), TRUSTED=(1 shl 10).
            // The last two are hidden and may still be rejected by the device's system policy.
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                SUPPORTS_TOUCH_FLAG or TRUSTED_FLAG

            val created = manager.createVirtualDisplay(
                "SakuShizukuVirtualDisplay",
                width.coerceIn(320, 2560),
                height.coerceIn(320, 2560),
                densityDpi.coerceIn(120, 640),
                surface,
                flags
            ) ?: throw IllegalStateException("createVirtualDisplay mengembalikan null.")

            created.setSurface(surface)
            virtualDisplay = created
            val display = created.display
            val stateName = when (display.state) {
                Display.STATE_ON -> "ON"
                Display.STATE_OFF -> "OFF"
                Display.STATE_DOZE -> "DOZE"
                Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
                Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
                else -> "UNKNOWN(${display.state})"
            }
            lastError = "Virtual display created • id=${display.displayId} • ${width.coerceIn(320, 2560)}×${height.coerceIn(320, 2560)} • state=$stateName • surfaceValid=${surface.isValid}"
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

    private fun enableHiddenApiAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }

    override fun injectTap(displayId: Int, x: Float, y: Float): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = injectMotion(displayId, MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
        if (!down) return false
        val upTime = SystemClock.uptimeMillis() + 8L
        return injectMotion(displayId, MotionEvent.ACTION_UP, x, y, downTime, upTime)
    }

    override fun injectHover(displayId: Int, x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        return injectMotion(displayId, MotionEvent.ACTION_HOVER_MOVE, x, y, now, now, InputDevice.SOURCE_MOUSE)
    }

    override fun injectSwipe(
        displayId: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): Boolean {
        val duration = durationMs.coerceIn(50L, 2000L)
        val downTime = SystemClock.uptimeMillis()
        if (!injectMotion(displayId, MotionEvent.ACTION_DOWN, x1, y1, downTime, downTime)) {
            return false
        }

        val steps = (duration / 8L).toInt().coerceIn(2, 60)
        for (step in 1 until steps) {
            val fraction = step.toFloat() / steps
            val x = x1 + (x2 - x1) * fraction
            val y = y1 + (y2 - y1) * fraction
            val delay = duration * step / steps
            inputHandler.postDelayed({
                injectMotion(
                    displayId,
                    MotionEvent.ACTION_MOVE,
                    x,
                    y,
                    downTime,
                    downTime + delay
                )
            }, delay)
        }
        inputHandler.postDelayed({
            injectMotion(
                displayId,
                MotionEvent.ACTION_UP,
                x2,
                y2,
                downTime,
                downTime + duration
            )
        }, duration)
        lastError = "OK"
        return true
    }

    private fun injectMotion(
        displayId: Int,
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
        source: Int = InputDevice.SOURCE_TOUCHSCREEN
    ): Boolean {
        var event: MotionEvent? = null
        val identity = Binder.clearCallingIdentity()
        return try {
            enableHiddenApiAccess()
            event = MotionEvent.obtain(
                downTime,
                max(downTime, eventTime),
                action,
                x,
                y,
                0
            ).apply { this.source = source }

            // setDisplayId is hidden on some Android releases. A Shizuku UserService is not
            // subject to the normal app hidden-API restrictions; OEM policy may still differ.
            val setter = motionDisplaySetter
                ?: throw NoSuchMethodException("MotionEvent.setDisplayId(int) tidak ditemukan.")
            setter.invoke(event, displayId)

            val accepted = inputManagerInject.invoke(
                inputManagerInstance,
                event as InputEvent,
                INJECT_INPUT_EVENT_MODE_ASYNC
            ) as? Boolean ?: false

            if (!accepted) {
                lastError = "InputManager menolak event untuk display ID $displayId."
            }
            accepted
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            lastError = "${cause.javaClass.simpleName}: ${cause.message ?: "injeksi input gagal"}"
            Log.e(TAG, "Input injection failed: $lastError", cause)
            false
        } finally {
            event?.recycle()
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun releaseVirtualDisplay() {
        val identity = Binder.clearCallingIdentity()
        try {
            inputHandler.removeCallbacksAndMessages(null)
            virtualDisplay?.release()
            virtualDisplay = null
            lastError = "Virtual display ditutup."
        } catch (error: Throwable) {
            lastError = "${error.javaClass.simpleName}: ${error.message ?: "gagal menutup display"}"
            Log.e(TAG, "Release virtual display failed", error)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun launchTestAppOnDisplay(displayId: Int): Boolean {
        if (displayId < 0 || displayId > 10000) {
            lastError = "Display ID tidak valid: $displayId"
            return false
        }
        val candidates = listOf(
            "Kalkulator" to "am start --display $displayId -W -a android.intent.action.MAIN -c android.intent.category.APP_CALCULATOR -f 0x1c000000",
            "Browser" to "am start --display $displayId -W -a android.intent.action.MAIN -c android.intent.category.APP_BROWSER -f 0x1c000000",
            "Browser contoh" to "am start --display $displayId -W -a android.intent.action.VIEW -d https://example.com -f 0x1c000000"
        )
        val attempts = mutableListOf<String>()
        for ((label, command) in candidates) {
            val result = runShizukuShell(command)
            val detail = "$label • exit=${result.first} • ${result.second}"
            attempts += detail
            val output = result.second
            val appearsSuccessful = result.first == 0 &&
                output.contains("Status: ok", ignoreCase = true) &&
                !output.contains("Warning: Activity not started", ignoreCase = true) &&
                !output.contains("Error:", ignoreCase = true)
            Log.i(TAG, "Launch candidate: $detail")
            if (appearsSuccessful) {
                lastError = "Shell launch diterima • target display=$displayId • flags=0x1c000000 • $detail"
                return true
            }
        }
        lastError = "Semua percobaan shell launch gagal atau ditolak • target display=$displayId • " +
            attempts.joinToString(" || ").take(2400)
        Log.w(TAG, lastError)
        return false
    }

    /**
     * Run a bounded, literal Android shell command in the Shizuku UserService process.
     * Commands are assembled only from the validated display ID and hard-coded intents.
     */
    private fun runShizukuShell(command: String): Pair<Int, String> {
        var process: Process? = null
        return try {
            val identity = Binder.clearCallingIdentity()
            try {
                process = ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val exitCode = process.waitFor()
                val compact = output.ifBlank { "(output kosong)" }.take(900)
                exitCode to compact
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            val detail = "${cause.javaClass.simpleName}: ${cause.message ?: "tanpa detail"}\n${Log.getStackTraceString(cause)}"
            Log.e(TAG, "Shell command failed: $command", cause)
            -1 to detail.take(1800)
        } finally {
            runCatching { process?.destroy() }
        }
    }

    override fun getDisplayDiagnostics(displayId: Int): String {
        return try {
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
            "display=${display.displayId} state=$stateName mode=${mode.physicalWidth}x${mode.physicalHeight} surfaceValid=$surfaceValid"
        } catch (error: Throwable) {
            "Diagnostics gagal: ${error.javaClass.simpleName}: ${error.message ?: "tanpa detail"}"
        }
    }

    override fun getLastError(): String = lastError

    override fun destroy() {
        releaseVirtualDisplay()
        inputThread.quitSafely()
        System.exit(0)
    }

    companion object {
        private const val TAG = "SakuShizukuService"
        private const val SUPPORTS_TOUCH_FLAG = 1 shl 6
        private const val TRUSTED_FLAG = 1 shl 10
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

        private val inputManagerInstance: Any by lazy {
            val type = Class.forName("android.hardware.input.InputManager")
            type.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
                ?: throw IllegalStateException("InputManager.getInstance() menghasilkan null.")
        }

        private val inputManagerInject: Method by lazy {
            val type = Class.forName("android.hardware.input.InputManager")
            type.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        }

        private val motionDisplaySetter: Method? by lazy {
            try {
                MotionEvent::class.java.getDeclaredMethod(
                    "setDisplayId",
                    Int::class.javaPrimitiveType
                ).apply { isAccessible = true }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
