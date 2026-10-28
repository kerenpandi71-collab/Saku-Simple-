package com.keren.sakusimple

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates the app-side ImageReader/preview with a virtual display created by Shizuku.
 * The Surface passed to the Shizuku UserService is the target for frames from this display.
 */
class VirtualDisplayController(
    context: Context,
    private val shizukuBridge: ShizukuBridge,
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (String) -> Unit
) : AutoCloseable {
    private val appContext = context
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var imageReader: ImageReader? = null
    private var presentation: Presentation? = null
    private val closed = AtomicBoolean(false)

    @Volatile
    var displayId: Int = Display.INVALID_DISPLAY
        private set

    fun start(width: Int, height: Int, densityDpi: Int) {
        releaseDisplayAndReader()
        closed.set(false)
        val safeWidth = width.coerceIn(320, 2560)
        val safeHeight = height.coerceIn(320, 2560)
        val safeDpi = densityDpi.coerceIn(120, 640)

        try {
            val reader = ImageReader.newInstance(
                safeWidth, safeHeight, PixelFormat.RGBA_8888, 3
            )
            imageReader = reader
            reader.setOnImageAvailableListener({ source ->
                val image = try {
                    source.acquireLatestImage()
                } catch (error: Throwable) {
                    Log.w(TAG, "Unable to acquire virtual display frame", error)
                    null
                } ?: return@setOnImageAvailableListener

                try {
                    val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = (rowStride - pixelStride * image.width) / pixelStride
                    val padded = Bitmap.createBitmap(
                        image.width + rowPadding, image.height, Bitmap.Config.ARGB_8888
                    )
                    buffer.rewind()
                    padded.copyPixelsFromBuffer(buffer)
                    val frame = if (rowPadding == 0) padded else Bitmap.createBitmap(
                        padded, 0, 0, image.width, image.height
                    ).also { if (it !== padded) padded.recycle() }
                    mainHandler.post {
                        if (!closed.get()) onFrame(frame) else frame.recycle()
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "Could not convert virtual display frame", error)
                } finally {
                    image.close()
                }
            }, mainHandler)

            onStatus("Meminta Shizuku membuat display virtual…")
            shizukuBridge.createVirtualDisplay(
                safeWidth,
                safeHeight,
                safeDpi,
                reader.surface
            ) { createdId, error ->
                if (createdId < 0) {
                    displayId = Display.INVALID_DISPLAY
                    onStatus("Pembuatan virtual display gagal: $error")
                    releaseReaderOnly()
                    return@createVirtualDisplay
                }

                displayId = createdId
                mainHandler.post {
                    if (closed.get()) {
                        shizukuBridge.releaseVirtualDisplay()
                        return@post
                    }
                    try {
                        val display = displayManager.getDisplay(createdId)
                            ?: throw IllegalStateException("Display ID $createdId belum terlihat oleh aplikasi.")
                        presentation = Presentation(appContext, display).apply {
                            setContentView(TextView(appContext).apply {
                                text = "SAKU VIRTUAL DISPLAY\n\nDisplay ID: $createdId\nShizuku berhasil membuat layar. Tekan 'Buka Pengaturan' untuk menguji peluncuran aplikasi."
                                textSize = 18f
                                setPadding(24, 24, 24, 24)
                            })
                            show()
                        }
                        onStatus("Virtual display Shizuku aktif • ID $createdId • ${safeWidth}×${safeHeight}.")
                    } catch (displayError: Throwable) {
                        onStatus("Display dibuat (ID $createdId), tetapi preview window gagal: ${displayError.message ?: displayError.javaClass.simpleName}")
                        Log.e(TAG, "Unable to show Presentation on Shizuku display", displayError)
                    }
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Virtual display setup failed", error)
            onStatus("Gagal menyiapkan virtual display: ${error.message ?: error.javaClass.simpleName}")
            releaseDisplayAndReader()
        }
    }

    fun injectTap(x: Float, y: Float): Boolean {
        val id = displayId
        if (id < 0) {
            onStatus("Buat virtual display terlebih dahulu.")
            return false
        }
        return shizukuBridge.injectTap(id, x, y)
    }

    fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 220L): Boolean {
        val id = displayId
        if (id < 0) {
            onStatus("Buat virtual display terlebih dahulu.")
            return false
        }
        return shizukuBridge.injectSwipe(id, x1, y1, x2, y2, durationMs)
    }

    fun launchSettings(activity: Activity): Boolean =
        shizukuBridge.launchSettingsOnDisplay(activity, displayId)

    override fun close() {
        closed.set(true)
        releaseDisplayAndReader()
    }

    private fun releaseDisplayAndReader() {
        try { presentation?.dismiss() } catch (_: Throwable) {}
        presentation = null
        if (displayId >= 0) shizukuBridge.releaseVirtualDisplay()
        displayId = Display.INVALID_DISPLAY
        releaseReaderOnly()
    }

    private fun releaseReaderOnly() {
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
    }

    companion object {
        private const val TAG = "SakuVirtualDisplay"
    }
}
