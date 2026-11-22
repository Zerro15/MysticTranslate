package io.github.zerro15.mystictranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PixelSignature(val hash: Long, val low: Int, val high: Int) {
    val uniform get() = low == high
}

internal class FrameDeduplication(private val retryMs: Long = 4200) {
    private var lastHash: Long? = null
    private var lastGeneration = -1
    private var lastRead = 0L
    private var foundText = false

    fun shouldRead(hash: Long, generation: Int, now: Long): Boolean =
        hash != lastHash || generation != lastGeneration || now - lastRead >= if (foundText) retryMs * 3 else retryMs

    fun record(hash: Long, generation: Int, now: Long, hasText: Boolean) {
        lastHash = hash
        lastGeneration = generation
        lastRead = now
        foundText = hasText
    }

    fun reset() {
        lastHash = null
    }
}

internal fun normalizeRecognition(text: String): String =
    normalized(text.filterNot { it == '\u200b' || it == '\ufeff' || it == '\ufffd' })

internal data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal fun sourceBounds(
    box: PixelBox, source: PixelBox, inputWidth: Int, inputHeight: Int,
): PixelBox? {
    val sx = (source.right - source.left).toDouble() / inputWidth
    val sy = (source.bottom - source.top).toDouble() / inputHeight
    val result = PixelBox(
        (source.left + floor(box.left * sx).toInt()).coerceIn(source.left, source.right),
        (source.top + floor(box.top * sy).toInt()).coerceIn(source.top, source.bottom),
        (source.left + ceil(box.right * sx).toInt()).coerceIn(source.left, source.right),
        (source.top + ceil(box.bottom * sy).toInt()).coerceIn(source.top, source.bottom),
    )
    return result.takeIf { it.left < it.right && it.top < it.bottom }
}

internal data class FrameResult(val blocks: List<Recognized>, val ocrMs: Long, val uniform: Boolean)

internal class FrameAnalyzer(private val pipeline: Engines, private val options: Options) {
    private val dedup = FrameDeduplication()
    private var lastEnhanced = -4000L
    private var enhancement = 0
    private var previousCount = 0

    fun reset() = dedup.reset()

    suspend fun read(frame: Bitmap, generation: Int): FrameResult? = withContext(Dispatchers.Default) {
        val start = SystemClock.elapsedRealtime()
        val crop = Rect(0, if (options.dialogue) (frame.height * 0.48f).toInt() else 0, frame.width, frame.height)
        // В сюжетном режиме также учитываем область, доступную резервному проходу.
        val signatureArea = Rect(crop).apply { if (options.dialogue) top = (frame.height * 0.31f).toInt() }
        val signature = signature(frame, signatureArea)
        if (!dedup.shouldRead(signature.hash, generation, start)) return@withContext null
        if (signature.uniform) {
            dedup.record(signature.hash, generation, start, false)
            return@withContext FrameResult(emptyList(), 0, true)
        }
        var blocks = recognize(frame, crop, 0, signature)
        val weak = blocks.isEmpty() || blocks.any { it.bounds.height() <= 13 } ||
            (previousCount >= 4 && blocks.size * 2 <= previousCount)
        if (!options.umi && weak && start - lastEnhanced >= 3700) {
            lastEnhanced = start
            enhancement = 1 + enhancement % 2
            val area = if (options.dialogue && blocks.isEmpty()) signatureArea else crop
            val enhanced = recognize(frame, area, enhancement, signature)
            // Объединяем неперекрывающиеся находки, сохраняя координаты исходного кадра.
            if (blocks.isEmpty()) blocks = enhanced else {
                blocks = blocks + enhanced.filter { candidate ->
                    blocks.none { original -> Rect.intersects(original.bounds, candidate.bounds) }
                }
            }
        }
        previousCount = blocks.size
        dedup.record(signature.hash, generation, start, blocks.isNotEmpty())
        val ordered = blocks.sortedWith(compareBy({ it.bounds.top / 12 }, { it.bounds.left })).take(80)
        val result = if (!options.dialogue || ordered.size <= 1) ordered else {
            val bounds = Rect(ordered.first().bounds)
            ordered.drop(1).forEach { bounds.union(it.bounds) }
            listOf(Recognized(ordered.joinToString(" ") { it.text }, bounds))
        }
        FrameResult(result, SystemClock.elapsedRealtime() - start, false)
    }

    private fun signature(frame: Bitmap, area: Rect): PixelSignature {
        val row = IntArray(area.width())
        var hash = -3750763034362895579L
        var first = 0
        var varied = false
        var low = 255
        var high = 0
        for (y in area.top until area.bottom step 3) {
            frame.getPixels(row, 0, row.size, area.left, y, row.size, 1)
            for (x in row.indices step 3) {
                val pixel = row[x] and 0xffffff
                if (y == area.top && x == 0) first = pixel
                varied = varied || pixel != first
                hash = (hash xor pixel.toLong()) * 1099511628211L
                val light = ((pixel shr 16) * 3 + ((pixel shr 8) and 255) * 6 + (pixel and 255)) / 10
                low = minOf(low, light)
                high = maxOf(high, light)
            }
        }
        if (!varied) {
            // Тонкие штрихи между точками сетки не должны превратить текст в «пустой кадр».
            for (y in area.top until area.bottom) {
                frame.getPixels(row, 0, row.size, area.left, y, row.size, 1)
                val different = row.indexOfFirst { (it and 0xffffff) != first }
                if (different != -1) {
                    val pixel = row[different] and 0xffffff
                    hash = (hash xor pixel.toLong() xor (y * row.size + different).toLong()) * 1099511628211L
                    varied = true
                    break
                }
            }
        }
        return PixelSignature(hash, low, if (varied && high == low) high + 1 else high)
    }

    private suspend fun recognize(frame: Bitmap, area: Rect, variant: Int, signature: PixelSignature): List<Recognized> {
        val longSide = maxOf(area.width(), area.height()).toFloat()
        val limit = if (options.detailed || variant != 0) 2560f else 2048f
        val scale = (limit / longSide).coerceAtMost(if (variant == 0) 1f else 1.6f)
        val width = (area.width() * scale).roundToInt().coerceAtLeast(1)
        val height = (area.height() * scale).roundToInt().coerceAtLeast(1)
        val input = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            if (variant != 0) {
                val span = (signature.high - signature.low).coerceAtLeast(60)
                val gain = (220f / span).coerceIn(1.15f, 2.7f)
                val shift = 128f - gain * (signature.low + signature.high) / 2f
                val colors = ColorMatrix().apply {
                    if (variant == 2) setSaturation(0f)
                    postConcat(ColorMatrix(floatArrayOf(
                        gain, 0f, 0f, 0f, shift, 0f, gain, 0f, 0f, shift,
                        0f, 0f, gain, 0f, shift, 0f, 0f, 0f, 1f, 0f,
                    )))
                }
                paint.colorFilter = ColorMatrixColorFilter(colors)
            }
            Canvas(input).drawBitmap(frame, area, Rect(0, 0, width, height), paint)
            val source = PixelBox(area.left, area.top, area.right, area.bottom)
            return pipeline.recognize(input, options).mapNotNull { block ->
                val text = normalizeRecognition(block.text)
                if (!han.containsMatchIn(text)) return@mapNotNull null
                val rect = block.bounds
                val bounds = sourceBounds(PixelBox(rect.left, rect.top, rect.right, rect.bottom), source, width, height)
                    ?: return@mapNotNull null
                Recognized(text, Rect(bounds.left, bounds.top, bounds.right, bounds.bottom))
            }
        } finally {
            input.recycle()
        }
    }
}
