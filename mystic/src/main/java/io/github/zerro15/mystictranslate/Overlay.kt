package io.github.zerro15.mystictranslate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow

internal data class OverlayBlock(val text: String, val bounds: Rect)

/** Окна поверх игры, с проходом касаний во время перевода. */
internal class Overlay(context: Context, stop: () -> Unit) : AutoCloseable {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val board = Board(context)
    private val boardLayout = WindowManager.LayoutParams(
        -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        alpha = 0.72f
    }
    private var editing = false
    val editMode = MutableStateFlow(false)
    private val controls = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(Button(context).apply {
            text = "Блоки"
            setOnClickListener {
                editing = !editing
                text = if (editing) "Готово" else "Блоки"
                boardLayout.flags = if (editing) boardLayout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    else boardLayout.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                wm.updateViewLayout(board, boardLayout)
                board.editing = editing
                editMode.value = editing
                board.invalidate()
            }
            setOnLongClickListener {
                board.reset()
                true
            }
        })
        addView(Button(context).apply {
            text = "Стоп"
            contentDescription = "Остановить перевод и захват экрана"
            setOnClickListener { stop() }
        })
    }

    fun attach() {
        wm.addView(board, boardLayout)
        wm.addView(controls, WindowManager.LayoutParams(
            -2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END })
    }

    fun setBlocks(blocks: List<OverlayBlock>, font: Float, opacity: Float) {
        board.blocks = blocks
        board.font = font
        if (boardLayout.alpha != opacity) {
            boardLayout.alpha = opacity.coerceIn(0.2f, 0.8f)
            wm.updateViewLayout(board, boardLayout)
        }
        board.invalidate()
    }

    fun clear() {
        board.blocks = emptyList()
        board.invalidate()
    }

    val hasBlocks get() = board.blocks.isNotEmpty()

    fun hideForCapture(hide: Boolean) {
        // INVISIBLE сохраняет размер окна и положение блоков.
        board.visibility = if (hide) View.INVISIBLE else View.VISIBLE
    }

    fun setReadingScreen(reading: Boolean) {
        controls.visibility = if (reading) View.VISIBLE else View.INVISIBLE
        hideForCapture(!reading)
    }

    override fun close() {
        if (controls.isAttachedToWindow) wm.removeViewImmediate(controls)
        if (board.isAttachedToWindow) wm.removeViewImmediate(board)
    }

    private class Board(context: Context) : View(context) {
        private data class Placement(var x: Float = 0f, var y: Float = 0f, var scale: Float = 1f)
        private val placements = mutableMapOf<Int, Placement>()
        private val hitAreas = mutableMapOf<Int, RectF>()
        var blocks = emptyList<OverlayBlock>()
        var font = 17f
        var editing = false
        private var selected: Int? = null
        private var lastX = 0f
        private var lastY = 0f
        private val density = resources.displayMetrics.density
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val index = selected ?: return false
                val place = placements.getOrPut(index) { Placement() }
                place.scale = (place.scale * detector.scaleFactor).coerceIn(0.65f, 2f)
                invalidate()
                return true
            }
        })

        fun reset() {
            placements.clear()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val position = IntArray(2)
            getLocationOnScreen(position)
            hitAreas.clear()
            blocks.forEachIndexed { index, block ->
                val place = placements[index] ?: Placement()
                val pad = 7 * density
                textPaint.textSize = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP, font * place.scale, resources.displayMetrics,
                )
                val available = (width - 2 * pad).toInt()
                if (available < 1) return@forEachIndexed
                val minimum = min(available, (120 * density * place.scale).toInt())
                val target = (max(block.bounds.width().toFloat(), textPaint.measureText(block.text)) * place.scale)
                    .toInt().coerceIn(max(1, minimum), available)
                val rendered = StaticLayout.Builder.obtain(block.text, 0, block.text.length, textPaint, target)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(if (blocks.size == 1) 12 else 5)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
                val w = target + 2 * pad
                val h = rendered.height + 2 * pad
                val x = (block.bounds.left - position[0] + place.x).coerceIn(0f, max(0f, width - w))
                val y = (block.bounds.top - position[1] + place.y).coerceIn(0f, max(0f, height - h))
                val rect = RectF(x, y, x + w, y + h)
                hitAreas[index] = rect
                boxPaint.color = if (editing) 0xff3c2c60.toInt() else 0xff140e22.toInt()
                canvas.drawRoundRect(rect, 10 * density, 10 * density, boxPaint)
                canvas.save()
                canvas.translate(x + pad, y + pad)
                rendered.draw(canvas)
                canvas.restore()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!editing) return false
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                selected = hitAreas.entries.lastOrNull { it.value.contains(event.x, event.y) }?.key
                lastX = event.x
                lastY = event.y
            }
            pinch.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 1) {
                selected?.let {
                    val place = placements.getOrPut(it) { Placement() }
                    place.x += event.x - lastX
                    place.y += event.y - lastY
                    invalidate()
                }
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                val i = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex == 0) 1 else 0
                lastX = event.getX(i)
                lastY = event.getY(i)
            }
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            return true
        }

        override fun performClick(): Boolean = super.performClick()
    }
}
