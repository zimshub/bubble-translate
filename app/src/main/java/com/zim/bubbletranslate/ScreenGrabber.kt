package com.zim.bubbletranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Keeps a live copy of what is on the screen and hands back a picture of it
 * on request.
 *
 * How it works: Android mirrors the screen into a "virtual display" that
 * draws into an ImageReader (a small queue of frames). We always hold on to
 * the newest frame, so a picture is ready the moment the bubble is tapped.
 *
 * Use from the main thread only.
 */
class ScreenGrabber(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val handler: Handler
) {
    var width = 0; private set
    var height = 0; private set
    private var dpi = 0
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var latest: Image? = null

    private fun measure() {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            width = b.width(); height = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            width = m.widthPixels; height = m.heightPixels
        }
        dpi = ctx.resources.displayMetrics.densityDpi
    }

    private fun newReader(): ImageReader {
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
        r.setOnImageAvailableListener({ source ->
            val img = try { source.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) {
                latest?.close()
                latest = img
            }
        }, handler)
        return r
    }

    fun start() {
        measure()
        val r = newReader()
        reader = r
        display = projection.createVirtualDisplay(
            "bubble-translate", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
    }

    /** Call when the phone is rotated: the screen is now a different shape. */
    fun resizeIfNeeded() {
        val oldW = width
        val oldH = height
        measure()
        if (width == oldW && height == oldH) return
        val d = display ?: return
        val old = reader
        latest?.close()
        latest = null
        val r = newReader()
        reader = r
        d.resize(width, height, dpi)
        d.surface = r.surface
        old?.close()
    }

    /** A picture of the screen as it is right now, or null if none has arrived yet. */
    fun grab(): Bitmap? {
        val img = latest ?: return null
        val plane = img.planes[0]
        // A row in memory can be wider than the screen (padding), so copy the
        // padded picture first and cut the padding off afterwards.
        val paddedWidth = plane.rowStride / plane.pixelStride
        val buffer = plane.buffer
        buffer.rewind()
        val full = Bitmap.createBitmap(paddedWidth, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(buffer)
        if (paddedWidth == img.width) return full
        val cut = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        full.recycle()
        return cut
    }

    fun stop() {
        try { display?.release() } catch (e: Exception) { }
        display = null
        try { latest?.close() } catch (e: Exception) { }
        latest = null
        try { reader?.close() } catch (e: Exception) { }
        reader = null
    }
}
