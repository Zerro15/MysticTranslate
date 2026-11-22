package io.github.zerro15.mystictranslate

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PanelAnchor(val x: Float = 1f, val y: Float = 0f)
internal data class PanelRange(val start: Int, val end: Int) {
    fun at(fraction: Float): Int = start + ((end - start) * fraction.coerceIn(0f, 1f)).roundToInt()
    fun fraction(value: Int): Float =
        if (end == start) 0f else ((value - start).toFloat() / (end - start)).coerceIn(0f, 1f)
}

/** Доработка кнопок и жеста удержания из MysticTranslate PR #1. */
internal class FloatingControls(
    private val context: Context,
    private val wm: WindowManager,
    private val edit: () -> Unit,
    private val stop: () -> Unit,
) : AutoCloseable {
    private val prefs = context.getSharedPreferences("panel", Context.MODE_PRIVATE)
    private var anchor = PanelAnchor(prefs.getFloat("x", 1f), prefs.getFloat("y", 0f))
    private var collapsed = prefs.getBoolean("collapsed", false)
    private val density get() = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val params = WindowManager.LayoutParams(
        -2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 30) {
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        title = "MysticTranslate · панель"
    }
    private var editing = false
    private var pressing: View? = null
    private var held: Runnable? = null
    private var blocked = false
    private var longPress = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    private var status: String? = null
    private val caption = TextView(context).apply {
        maxWidth = dp(154)
        maxLines = 2
        textSize = 11f
        setTextColor(Color.WHITE)
        setPadding(dp(6), dp(2), dp(6), dp(4))
        visibility = View.GONE
    }
    private val editButton = button("Блоки", "Редактировать перевод", edit)
    private val stopButton = button("Стоп", "Остановить захват", stop)
    private val toggle = button("−", "Свернуть панель") { toggleCollapsed() }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(11).toFloat()
            setColor(0xde201833.toInt())
        }
        addView(LinearLayout(context).apply {
            addView(editButton)
            addView(stopButton)
            addView(toggle)
        })
        addView(caption)
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) reposition()
        }
    }

    private fun button(label: String, description: String, tap: () -> Unit) = Button(context).apply {
        text = label
        contentDescription = description
        textSize = 12f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = dp(30)
        setTextColor(Color.WHITE)
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x44ffffff), null, null,
        )
        setPadding(dp(8), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(-2, dp(40))
        setOnClickListener { tap() }
        setOnTouchListener { view, event -> handleTouch(view, event) }
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearGesture()
                pressing = view
                downX = event.rawX
                downY = event.rawY
                blocked = false
                longPress = false
                moved = false
                view.isPressed = true
                held = Runnable {
                    longPress = true
                    startX = params.x
                    startY = params.y
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    view.isPressed = false
                }.also { view.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (blocked) return true
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!moved && abs(dx) <= slop && abs(dy) <= slop) return true
                if (!longPress) {
                    blocked = true
                    clearGesture()
                } else {
                    moved = true
                    val (x, y) = ranges()
                    val targetX = (startX + dx.roundToInt()).coerceIn(x.start, x.end)
                    val targetY = (startY + dy.roundToInt()).coerceIn(y.start, y.end)
                    anchor = PanelAnchor(x.fraction(targetX), y.fraction(targetY))
                    move(targetX, targetY)
                }
            }
            MotionEvent.ACTION_UP -> {
                val click = !blocked && !longPress && pressing === view
                if (moved) save()
                clearGesture()
                if (click) view.performClick()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                blocked = true
                if (moved) save()
                clearGesture()
            }
        }
        return true
    }

    private fun clearGesture() {
        pressing?.let { view ->
            held?.let(view::removeCallbacks)
            view.isPressed = false
        }
        pressing = null
        held = null
    }

    @Suppress("DEPRECATION")
    private fun safeBounds(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return Rect(metrics.bounds.left + insets.left, metrics.bounds.top + insets.top,
                metrics.bounds.right - insets.right, metrics.bounds.bottom - insets.bottom)
        }
        val metrics = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        val inset = panel.rootWindowInsets
        return Rect(inset?.stableInsetLeft ?: 0, inset?.stableInsetTop ?: dp(24),
            metrics.widthPixels - (inset?.stableInsetRight ?: 0),
            metrics.heightPixels - (inset?.stableInsetBottom ?: dp(24)))
    }

    private fun ranges(): Pair<PanelRange, PanelRange> {
        val bounds = safeBounds()
        val margin = dp(5)
        val left = bounds.left + margin
        val top = bounds.top + margin
        return PanelRange(left, (bounds.right - margin - panel.width).coerceAtLeast(left)) to
            PanelRange(top, (bounds.bottom - margin - panel.height).coerceAtLeast(top))
    }

    fun reposition() {
        if (!panel.isAttachedToWindow || panel.width == 0) return
        val (x, y) = ranges()
        move(x.at(anchor.x), y.at(anchor.y))
    }

    private fun move(x: Int, y: Int) {
        if (params.x == x && params.y == y) return
        params.x = x
        params.y = y
        if (panel.isAttachedToWindow) wm.updateViewLayout(panel, params)
    }

    private fun toggleCollapsed() {
        if (editing) edit()
        collapsed = !collapsed
        applyAppearance()
        save()
    }

    private fun applyAppearance() {
        editButton.visibility = if (collapsed) View.GONE else View.VISIBLE
        stopButton.visibility = if (collapsed) View.GONE else View.VISIBLE
        toggle.text = if (collapsed) "⇄" else "−"
        toggle.contentDescription = if (collapsed) "Развернуть панель" else "Свернуть панель"
        panel.alpha = if (collapsed) 0.76f else 1f
        showStatus(status)
    }

    fun setEditing(value: Boolean) {
        editing = value
        editButton.text = if (value) "Готово" else "Блоки"
        editButton.contentDescription = if (value) "Завершить редактирование" else "Редактировать перевод"
    }

    fun showStatus(message: String?) {
        status = message
        if (caption.text.toString() != message.orEmpty()) caption.text = message.orEmpty()
        val visibility = if (collapsed || message == null) View.GONE else View.VISIBLE
        if (caption.visibility != visibility) caption.visibility = visibility
    }

    fun attach() {
        applyAppearance()
        val bounds = safeBounds()
        params.x = bounds.left + dp(5)
        params.y = bounds.top + dp(5)
        wm.addView(panel, params)
    }

    fun setVisible(visible: Boolean) {
        if (!visible) clearGesture()
        panel.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun save() {
        prefs.edit().putFloat("x", anchor.x).putFloat("y", anchor.y).putBoolean("collapsed", collapsed).apply()
    }

    override fun close() {
        clearGesture()
        if (panel.isAttachedToWindow) wm.removeViewImmediate(panel)
    }
}
