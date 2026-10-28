package com.saku.gamepad

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceView
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/** Port of SakuDextop's display lifecycle and mirror-backend selection order. */
internal class DextopMirrorDisplayBackend(
    private val context: Context,
    private val log: (String) -> Unit
) {
    companion object {
        const val DISPLAY_SPECIFICATION = "overlay_display_devices"
        private const val DISPLAY_SPEC = "800x800/240"
        private const val WIDTH = 800
        private const val HEIGHT = 800
        private const val DENSITY = 240
        private const val TYPE_OVERLAY = 4
        private const val FLAG_PUBLIC = 1
        private const val FLAG_AUTO_MIRROR = 1 shl 4
        private const val FLAG_TRUSTED = 1 shl 10
    }

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var previousSpecs: List<String> = emptyList()
    private var requestAdded = false
    private var sourceDisplayId = -1
    private var mirror: MirrorAttachment? = null
    private var activeStrategy = "none"
    private var active = false

    @Synchronized
    fun start(host: SurfaceView): Int {
        check(!active && !requestAdded) { "SakuDextop-style mirror is active or cleanup is pending; release/clean the previous session first" }
        check(host.width > 0 && host.height > 0) { "Host SurfaceView has no size yet" }
        check(runCatching { host.holder.surface.isValid }.getOrDefault(false)) {
            "Host SurfaceView surface is not valid"
        }
        HiddenApiBypass.addHiddenApiExemptions("")

        val originalIds = displayIds()
        previousSpecs = readSpecs()
        requestAdded = true
        try {
            writeSpecs(previousSpecs + DISPLAY_SPEC)
            log("Dextop requestDisplay: spec=$DISPLAY_SPEC; previous=$previousSpecs")
            val source = waitForOverlay(originalIds, 8_000L)
            sourceDisplayId = source.displayId
            log("Dextop overlay display ready id=$sourceDisplayId size=${displaySize(source)}")

            val failures = mutableListOf<String>()
            val strategies = listOf(
                WindowManagerMirrorBackend(),
                SurfaceControlMirrorBackend(),
                VirtualDisplayMirrorBackend()
            )
            for (backend in strategies) {
                if (!backend.isSupported()) {
                    failures += "${backend.id}=API unavailable"
                    log("Dextop mirror strategy=${backend.id} unsupported")
                    continue
                }
                val result = runCatching {
                    backend.attach(sourceDisplayId, host, host.width, host.height, WIDTH, HEIGHT, DENSITY)
                }
                if (result.isSuccess) {
                    mirror = result.getOrThrow()
                    activeStrategy = backend.id
                    active = true
                    log("Dextop mirror strategy=$activeStrategy attached display=$sourceDisplayId")
                    return sourceDisplayId
                }
                val error = result.exceptionOrNull()!!
                failures += "${backend.id}=${describe(error)}"
                log("Dextop mirror strategy=${backend.id} failed: ${describe(error)}")
            }
            error("No compatible Dextop mirror backend: ${failures.joinToString("; ")}")
        } catch (error: Throwable) {
            val cleanup = runCatching { clearOwnRequestAndWait(sourceDisplayId, 5_000L) }
            cleanup.onFailure { log("Rollback display request failed: ${describe(it)}") }
            requestAdded = cleanup.isFailure
            sourceDisplayId = -1
            activeStrategy = "none"
            throw error
        }
    }

    @Synchronized
    fun release() {
        log("Dextop-style release requested display=$sourceDisplayId strategy=$activeStrategy")
        runCatching { mirror?.release() }
            .onSuccess { log("Dextop mirror layer released strategy=$activeStrategy") }
            .onFailure { log("Dextop mirror release failed: ${describe(it)}") }
        mirror = null
        active = false
        activeStrategy = "none"
        val releasingId = sourceDisplayId
        sourceDisplayId = -1
        if (requestAdded) {
            val cleanup = runCatching { clearOwnRequestAndWait(releasingId, 6_000L) }
            cleanup.onFailure { log("Display request cleanup failed: ${describe(it)}") }
            requestAdded = cleanup.isFailure
        }
    }

    @Synchronized
    fun status(): String =
        "createdDisplayId=$sourceDisplayId; strategy=$activeStrategy; active=$active; requestAdded=$requestAdded"

    private fun waitForOverlay(previousIds: Set<Int>, timeoutMs: Long): Display {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last = "no new overlay display reported"
        while (SystemClock.uptimeMillis() < deadline) {
            val found = displayManager.displays.firstOrNull { display ->
                display.displayId !in previousIds && display.displayId != Display.DEFAULT_DISPLAY &&
                    displayType(display) == TYPE_OVERLAY
            }
            if (found != null && found.isValid) return found
            last = displayManager.displays.joinToString { d ->
                "${d.displayId}:${d.name}/type${displayType(d)}"
            }
            Thread.sleep(100L)
        }
        error("OverlayDisplayAdapter did not publish a new display in ${timeoutMs}ms; displays=$last")
    }

    private fun clearOwnRequestAndWait(displayId: Int, timeoutMs: Long) {
        val current = readSpecs()
        var removed = false
        val previousQuota = previousSpecs.groupingBy { it }.eachCount().toMutableMap()
        val next = buildList {
            for (entry in current) {
                val originalQuota = previousQuota[entry] ?: 0
                if (originalQuota > 0) {
                    previousQuota[entry] = originalQuota - 1
                    add(entry)
                } else if (entry == DISPLAY_SPEC && !removed) {
                    removed = true
                } else {
                    add(entry)
                }
            }
        }
        writeSpecs(next)
        val verified = readSpecs()
        check(verified == next) { "overlay_display_devices cleanup verification mismatch: $verified" }
        log("Dextop clearRequest: removedOwnSpec=$removed setting=${verified.ifEmpty { listOf("(kosong)") }}")

        if (displayId >= 0) {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < deadline) {
                if (displayManager.getDisplay(displayId)?.isValid != true) {
                    log("Dextop display id=$displayId disappeared after clearRequest")
                    return
                }
                Thread.sleep(100L)
            }
            if (displayManager.getDisplay(displayId)?.isValid == true) {
                log("Dextop cleanup: display id=$displayId still reported after ${timeoutMs}ms; setting was cleared")
            }
        }
    }

    private fun displayIds(): Set<Int> = displayManager.displays.mapTo(mutableSetOf()) { it.displayId }

    private fun displayType(display: Display): Int = runCatching {
        Display::class.java.getMethod("getType").invoke(display) as Int
    }.getOrDefault(-1)

    private fun displaySize(display: Display): String {
        val point = android.graphics.Point()
        runCatching { display.getRealSize(point) }
        if (point.x <= 0 || point.y <= 0) {
            point.x = display.mode.physicalWidth
            point.y = display.mode.physicalHeight
        }
        return "${point.x}x${point.y}"
    }

    private fun readSpecs(): List<String> {
        val result = shell("settings get global $DISPLAY_SPECIFICATION")
        check(result.first == 0) { "Cannot read $DISPLAY_SPECIFICATION: ${result.third}" }
        return parseSpecs(result.second)
    }

    private fun writeSpecs(specs: List<String>) {
        val value = specs.joinToString(";")
        val result = shell("settings put global $DISPLAY_SPECIFICATION ${quote(value)}")
        check(result.first == 0) { "Cannot update $DISPLAY_SPECIFICATION: ${result.third}" }
        val verify = readSpecs()
        check(verify == specs) { "Settings rejected display request: expected=$specs actual=$verify" }
    }

    private fun parseSpecs(value: String): List<String> =
        value.trim().takeUnless { it.isBlank() || it == "null" }
            ?.split(';')?.filter { it.isNotBlank() } ?: emptyList()

    private fun shell(command: String): Triple<Int, String, String> {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        log("shell[su] exit=$exitCode cmd=$command out='${stdout.trim()}' err='${stderr.trim()}'")
        return Triple(exitCode, stdout.trim(), stderr.trim())
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun describe(error: Throwable): String {
        var cause = error
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return "${cause.javaClass.simpleName}: ${cause.message ?: error.message ?: "unknown error"}"
    }

    private data class MirrorRequest(
        val displayId: Int, val host: SurfaceView, val hostWidth: Int, val hostHeight: Int,
        val contentWidth: Int, val contentHeight: Int, val contentDensity: Int
    )

    private interface MirrorAttachment { fun release() }

    private interface MirrorBackend {
        val id: String
        fun isSupported(): Boolean
        fun attach(
            displayId: Int, host: SurfaceView, hostWidth: Int, hostHeight: Int,
            contentWidth: Int, contentHeight: Int, contentDensity: Int
        ): MirrorAttachment
    }

    private fun request(
        displayId: Int, host: SurfaceView, hostWidth: Int, hostHeight: Int,
        contentWidth: Int, contentHeight: Int, contentDensity: Int
    ) = MirrorRequest(displayId, host, hostWidth, hostHeight, contentWidth, contentHeight, contentDensity)

    private inner class WindowManagerMirrorBackend : MirrorBackend {
        override val id = "window_manager"
        private val interfaceClass by lazy { Class.forName("android.view.IWindowManager") }

        override fun isSupported(): Boolean = runCatching {
            interfaceClass.getMethod("mirrorDisplay", Int::class.javaPrimitiveType, SurfaceControl::class.java)
        }.isSuccess

        override fun attach(
            displayId: Int, host: SurfaceView, hostWidth: Int, hostHeight: Int,
            contentWidth: Int, contentHeight: Int, contentDensity: Int
        ): MirrorAttachment {
            val layer = SurfaceControl::class.java.getConstructor().newInstance()
            val service = windowManagerService()
            val result = interfaceClass.getMethod(
                "mirrorDisplay", Int::class.javaPrimitiveType, SurfaceControl::class.java
            ).invoke(service, displayId, layer) as Boolean
            check(result) { "IWindowManager rejected mirror for display $displayId" }
            return attachLayer(layer, request(displayId, host, hostWidth, hostHeight, contentWidth, contentHeight, contentDensity))
        }
    }

    private inner class SurfaceControlMirrorBackend : MirrorBackend {
        override val id = "surface_control"

        override fun isSupported(): Boolean = runCatching {
            SurfaceControl::class.java.getDeclaredMethod("mirrorDisplay", Int::class.javaPrimitiveType)
        }.isSuccess

        override fun attach(
            displayId: Int, host: SurfaceView, hostWidth: Int, hostHeight: Int,
            contentWidth: Int, contentHeight: Int, contentDensity: Int
        ): MirrorAttachment {
            val method = SurfaceControl::class.java.getDeclaredMethod(
                "mirrorDisplay", Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val layer = method.invoke(null, displayId) as SurfaceControl
            return attachLayer(layer, request(displayId, host, hostWidth, hostHeight, contentWidth, contentHeight, contentDensity))
        }
    }

    private fun attachLayer(layer: SurfaceControl, request: MirrorRequest): MirrorAttachment {
        try {
            val parent = SurfaceView::class.java.getMethod("getSurfaceControl").invoke(request.host) as SurfaceControl
            val transaction = SurfaceControl.Transaction().reparent(layer, parent).setLayer(layer, 1)

            // Match SakuDextop exactly: crop/scale uses the requested desktop
            // content size, not Display.getRealSize(). On this Xiaomi Android
            // 15 build getRealSize() reports 1150x934 for an 800x800 overlay;
            // that value is not the logical content geometry requested here.
            val scale = minOf(
                request.hostWidth.toFloat() / request.contentWidth,
                request.hostHeight.toFloat() / request.contentHeight
            )
            val offsetX = (request.hostWidth - request.contentWidth * scale) / 2f
            val offsetY = (request.hostHeight - request.contentHeight * scale) / 2f
            SurfaceControl.Transaction::class.java.getMethod(
                "setMatrix", SurfaceControl::class.java, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
            ).invoke(transaction, layer, scale, 0f, 0f, scale)
            transaction.setPosition(layer, offsetX, offsetY)
            SurfaceControl.Transaction::class.java.getMethod(
                "setWindowCrop", SurfaceControl::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(transaction, layer, request.contentWidth, request.contentHeight)
            SurfaceControl.Transaction::class.java.getMethod("show", SurfaceControl::class.java)
                .invoke(transaction, layer)
            transaction.apply()

            val actual = readSourceSize(request.displayId)
            log("Mirror geometry: logical=${request.contentWidth}x${request.contentHeight}, reportedDisplay=${actual.x}x${actual.y}, host=${request.hostWidth}x${request.hostHeight}")
        } catch (error: Throwable) {
            runCatching { layer.release() }
            throw error
        }
        return object : MirrorAttachment {
            override fun release() {
                runCatching {
                    val transaction = SurfaceControl.Transaction()
                    SurfaceControl.Transaction::class.java.getMethod("remove", SurfaceControl::class.java)
                        .invoke(transaction, layer)
                    transaction.apply()
                }
                layer.release()
            }
        }
    }

    private fun readSourceSize(displayId: Int): android.graphics.Point {
        val point = android.graphics.Point()
        val display = displayManager.getDisplay(displayId)
        if (display != null) {
            runCatching { display.getRealSize(point) }
            if (point.x <= 0 || point.y <= 0) {
                runCatching {
                    point.x = display.mode.physicalWidth
                    point.y = display.mode.physicalHeight
                }
            }
        }
        return point
    }

    private inner class VirtualDisplayMirrorBackend : MirrorBackend {
        override val id = "virtual_display"
        private val managerInterface by lazy { Class.forName("android.hardware.display.IDisplayManager") }
        private val configClass by lazy { Class.forName("android.hardware.display.VirtualDisplayConfig") }
        private val builderClass by lazy { Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder") }
        private val callbackClass by lazy { Class.forName("android.hardware.display.IVirtualDisplayCallback") }

        override fun isSupported(): Boolean = runCatching {
            managerInterface.methods.singleOrNull { method ->
                method.name == "createVirtualDisplay" &&
                    method.returnType == Int::class.javaPrimitiveType &&
                    method.parameterTypes.any { configClass.isAssignableFrom(it) } &&
                    method.parameterTypes.any { callbackClass.isAssignableFrom(it) }
            } ?: error("createVirtualDisplay not found")
            builderClass.getMethod("setDisplayIdToMirror", Int::class.javaPrimitiveType)
        }.isSuccess

        override fun attach(
            displayId: Int, host: SurfaceView, hostWidth: Int, hostHeight: Int,
            contentWidth: Int, contentHeight: Int, contentDensity: Int
        ): MirrorAttachment {
            val surface = host.holder.surface
            check(surface.isValid) { "Destination SurfaceView surface unavailable" }
            val builderConstructor = builderClass.constructors.single { constructor ->
                constructor.parameterTypes.contentEquals(arrayOf(
                    String::class.java, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                ))
            }
            val createOperation = managerInterface.methods.single { method ->
                method.name == "createVirtualDisplay" &&
                    method.returnType == Int::class.javaPrimitiveType &&
                    method.parameterTypes.any { configClass.isAssignableFrom(it) } &&
                    method.parameterTypes.any { callbackClass.isAssignableFrom(it) }
            }
            val releaseOperation = managerInterface.methods.single { method ->
                method.name == "releaseVirtualDisplay" && method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(callbackClass)
            }
            val service = displayManagerService()
            var lastFailure: Throwable? = null
            for (flags in intArrayOf(FLAG_PUBLIC or FLAG_AUTO_MIRROR or FLAG_TRUSTED, FLAG_PUBLIC or FLAG_AUTO_MIRROR)) {
                val callback = displayCallback(callbackClass)
                try {
                    val builder = builderConstructor.newInstance(
                        "Saku Game Pad Mirror $displayId",
                        hostWidth.coerceAtLeast(1), hostHeight.coerceAtLeast(1), contentDensity
                    )
                    builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
                    builderClass.getMethod("setDisplayIdToMirror", Int::class.javaPrimitiveType).invoke(builder, displayId)
                    builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
                    val config = builderClass.getMethod("build").invoke(builder)
                    val args = createOperationArgs(createOperation, config, callback)
                    val outputDisplayId = (createOperation.invoke(service, *args) as? Number)?.toInt() ?: -1
                    check(outputDisplayId >= 0) { "VirtualDisplay mirror request rejected for flags=0x${flags.toString(16)}" }
                    return object : MirrorAttachment {
                        private var released = false
                        override fun release() {
                            if (released) return
                            released = true
                            releaseOperation.invoke(service, callback)
                            log("Dextop virtual_display mirror released outputDisplayId=$outputDisplayId")
                        }
                    }
                } catch (error: Throwable) {
                    lastFailure = error
                    log("Dextop virtual_display mirror flags=0x${flags.toString(16)} failed: ${describe(error)}")
                }
            }
            throw IllegalStateException("VirtualDisplay mirror backend failed", lastFailure)
        }
    }

    private fun createOperationArgs(operation: java.lang.reflect.Method, config: Any, callback: Any): Array<Any?> =
        operation.parameterTypes.map { parameter ->
            when {
                parameter.isInstance(config) -> config
                parameter.isInstance(callback) -> callback
                parameter == String::class.java -> "com.android.shell"
                IBinder::class.java.isAssignableFrom(parameter) -> null
                !parameter.isPrimitive -> null
                parameter == Boolean::class.javaPrimitiveType -> false
                parameter == Byte::class.javaPrimitiveType -> 0.toByte()
                parameter == Char::class.javaPrimitiveType -> '\u0000'
                parameter == Short::class.javaPrimitiveType -> 0.toShort()
                parameter == Int::class.javaPrimitiveType -> 0
                parameter == Long::class.javaPrimitiveType -> 0L
                parameter == Float::class.javaPrimitiveType -> 0f
                parameter == Double::class.javaPrimitiveType -> 0.0
                else -> null
            }
        }.toTypedArray()

    private fun windowManagerService(): Any {
        val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("window"))
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: error("IWindowManager service is null")
    }

    private fun displayManagerService(): Any {
        val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("display"))
        val stub = Class.forName("android.hardware.display.IDisplayManager\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: error("IDisplayManager service is null")
    }

    private fun displayCallback(callbackType: Class<*>): Any {
        val binder = Binder()
        val handler = InvocationHandler { proxy, method, args ->
            when {
                method.name == "asBinder" -> binder
                method.name == "toString" -> "SakuGamePadDisplayLifecycle"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.returnType == Boolean::class.javaPrimitiveType -> false
                method.returnType == Int::class.javaPrimitiveType -> 0
                method.returnType == Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        }
        return Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType), handler)
    }
}
