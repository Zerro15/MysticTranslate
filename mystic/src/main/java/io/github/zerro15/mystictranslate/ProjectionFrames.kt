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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Адаптировано из MediaProjectionScreenshotter, Copyright 2026 ciddwd, Apache-2.0. */
internal class ProjectionFrames(
    private val context: Context,
    private val projection: MediaProjection,
    private val onStop: () -> Unit,
    private val onResize: () -> Unit,
) : AutoCloseable {
    private val density = context.resources.configuration.densityDpi
    private val handler = Handler(Looper.getMainLooper())
    private val available = Channel<Unit>(Channel.CONFLATED)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    var generation = 0
        private set
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() = this@ProjectionFrames.onStop()
        override fun onCapturedContentResize(width: Int, height: Int) = resize(width, height)
    }

    fun start(width: Int, height: Int) {
        projection.registerCallback(callback, handler)
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
        val input = try {
            newReader(width, height)
        } catch (error: RuntimeException) {
            Diagnostics.failure("Размер кадра", "Не удалось подготовить поверхность захвата", error)
            onStop()
            return
        }
        try {
            currentDisplay.setSurface(null)
            currentDisplay.resize(width, height, density)
            currentDisplay.setSurface(input.surface)
        } catch (error: RuntimeException) {
            input.close()
            Diagnostics.failure("Размер кадра", "Не удалось изменить размер захвата", error)
            onStop()
            return
        }
        reader = input
        runCatching { old.close() }.onFailure { Diagnostics.failure("Кадры", "Не удалось закрыть прежнюю поверхность", it) }
        generation++
        available.trySend(Unit)
        onResize()
    }

    private fun newReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ available.trySend(Unit) }, handler)
        }

    fun discardQueued() {
        reader?.acquireLatestImage()?.close()
        available.tryReceive()
    }

    suspend fun capture(hadVisibleBlocks: Boolean): Bitmap? {
        val input = reader ?: return null
        val hiddenAt = System.nanoTime()
        if (hadVisibleBlocks) delay(72)
        // Возвращаем Image только синхронно на main: resize и close работают на том же потоке.
        var pending: Bitmap? = null
        try {
            val received = withTimeoutOrNull(if (hadVisibleBlocks) 430L else 1_450L) {
                while (input === reader) {
                    pending = input.acquireLatestImage()?.use { frame ->
                        // На устройствах с монотонными отметками отбрасываем кадр до скрытия окна.
                        val age = System.nanoTime() - frame.timestamp
                        if (hadVisibleBlocks && age in 0..500_000_000L && frame.timestamp < hiddenAt) {
                            null
                        } else imageToBitmap(frame)
                    }
                    if (pending != null) return@withTimeoutOrNull true
                    available.receive()
                }
                false
            }
            if (received != true) return null
            return pending.also { pending = null }
        } finally {
            pending?.recycle()
        }
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
        available.cancel()
        runCatching { display?.release() }.onFailure { Diagnostics.failure("Захват", "Не удалось освободить экран", it) }
        runCatching { reader?.close() }.onFailure { Diagnostics.failure("Захват", "Не удалось закрыть поток кадров", it) }
        reader = null
        display = null
        runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
    }
}
