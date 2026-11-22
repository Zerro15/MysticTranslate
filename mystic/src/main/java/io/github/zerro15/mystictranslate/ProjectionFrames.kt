package io.github.zerro15.mystictranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import kotlinx.coroutines.delay

/** Адаптировано из MediaProjectionScreenshotter, Copyright 2026 ciddwd, Apache-2.0. */
internal class ProjectionFrames(
    private val context: Context,
    private val projection: MediaProjection,
    private val onStop: () -> Unit,
    private val onResize: () -> Unit,
) : AutoCloseable {
    private val density = context.resources.configuration.densityDpi
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    var generation = 0
        private set
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() = this@ProjectionFrames.onStop()
        override fun onCapturedContentResize(width: Int, height: Int) = resize(width, height)
    }

    fun start(width: Int, height: Int) {
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        val input = newReader(width, height)
        reader = input
        display = checkNotNull(projection.createVirtualDisplay(
            "MysticTranslate", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            input.surface, null, null,
        ))
    }

    fun resize(width: Int, height: Int) {
        val old = reader ?: return
        val currentDisplay = display ?: return
        if (width < 1 || height < 1 || (old.width == width && old.height == height)) return
        val input = newReader(width, height)
        try {
            currentDisplay.setSurface(null)
            currentDisplay.resize(width, height, density)
            currentDisplay.setSurface(input.surface)
        } catch (_: RuntimeException) {
            input.close()
            onStop()
            return
        }
        reader = input
        old.close()
        generation++
        onResize()
    }

    private fun newReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

    suspend fun capture(hadVisibleBlocks: Boolean): Bitmap? {
        val input = reader ?: return null
        if (hadVisibleBlocks) {
            // Освобождаем буферы, накопившиеся до скрытия оверлея.
            input.acquireLatestImage()?.close()
            delay(55)
        }
        repeat(5) {
            if (input !== reader) return null
            input.acquireLatestImage()?.use { return imageToBitmap(it) }
            delay(30)
        }
        return null
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val paddedWidth = plane.rowStride / plane.pixelStride
        val raw = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        val pixels = if (buffer.remaining() >= raw.byteCount) buffer else {
            // Некоторые устройства не возвращают padding последней строки.
            ByteBuffer.allocate(raw.byteCount).apply {
                put(buffer)
                rewind()
            }
        }
        raw.copyPixelsFromBuffer(pixels)
        if (paddedWidth == width) return raw
        return Bitmap.createBitmap(raw, 0, 0, width, height).also { raw.recycle() }
    }

    override fun close() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        reader = null
        display = null
        runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
    }
}
