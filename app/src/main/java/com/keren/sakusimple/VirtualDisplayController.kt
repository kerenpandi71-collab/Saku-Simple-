package com.keren.sakusimple

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import android.widget.Presentation
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns an app-created virtual display and a frame stream for preview.
 *
 * Important: this creates a display for content owned by this app. It does not grant
 * permission to launch arbitrary third-party activities onto the display, and it does
 * not request privileged TRUSTED / SUPPORTS_TOUCH flags. Those require system/OEM
 * support and cannot be made available merely by reflecting hidden constants.
 */
class VirtualDisplayController(
    context: Context,
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (String) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val displayManager =
        appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private val closed = AtomicBoolean(false)

    val displayId: Int
        get() = virtualDisplay?.display?.displayId ?: Display.INVALID_DISPLAY

    fun start(width: Int, height: Int, densityDpi: Int): Boolean {
        closeResources()
        closed.set(false)
        val safeWidth = width.coerceIn(320, 2560)
        val safeHeight = height.coerceIn(320, 2560)
        val safeDpi = densityDpi.coerceIn(120, 640)

        return try {
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

            // PUBLIC + PRESENTATION are public API flags. Deliberately do not claim
            // that this app has privileged TRUSTED or SUPPORTS_TOUCH capabilities.
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            virtualDisplay = displayManager.createVirtualDisplay(
                "SakuSimpleVirtualDisplay",
                safeWidth,
                safeHeight,
                safeDpi,
                reader.surface,
                flags
            ) ?: run {
                onStatus("Android menolak pembuatan virtual display.")
                closeResources()
                return false
            }

            val display = virtualDisplay?.display
            if (display == null) {
                onStatus("Virtual display dibuat tetapi objek Display tidak tersedia.")
                closeResources()
                return false
            }
            presentation = Presentation(appContext, display).apply {
                setContentView(TextView(context).apply {
                    text = "SAKU VIRTUAL DISPLAY\\n\\nLayar virtual aktif. Konten aplikasi pihak ketiga belum dipindahkan ke layar ini."
                    textSize = 18f
                    setPadding(24, 24, 24, 24)
                })
                show()
            }
            onStatus("Virtual display aktif • ID ${display.displayId} • ${safeWidth}×${safeHeight}")
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Virtual display creation failed", error)
            onStatus("Gagal membuat virtual display: ${error.javaClass.simpleName}")
            closeResources()
            false
        }
    }

    override fun close() {
        closed.set(true)
        closeResources()
    }

    private fun closeResources() {
        try { presentation?.dismiss() } catch (_: Throwable) {}
        presentation = null
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
    }

    companion object {
        private const val TAG = "SakuVirtualDisplay"
    }
}
