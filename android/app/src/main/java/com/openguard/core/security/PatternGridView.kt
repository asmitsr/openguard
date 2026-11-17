package com.openguard.core.security

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Classic 3x3 pattern grid (plain Android View, no Compose dependency,
 * so it works both in the Views-based decoy overlay and in Compose
 * Settings via AndroidView).
 *
 * Drag across dots to draw a pattern. Cells are numbered row-major:
 * 0 1 2 / 3 4 5 / 6 7 8. On ACTION_UP, [onPatternComplete] fires with
 * the drawn sequence — even for 1-3 cells; callers decide validity —
 * and the drawing clears after 300 ms so the user briefly sees it.
 * [reset] clears the selection immediately.
 *
 * Android-like intermediate-dot rule: when the line from cell A to
 * cell B passes exactly through cell C's center, C is auto-selected.
 * A cell is never selected twice. Hit-testing uses ~1.5x the dot
 * radius for forgiving touch. Touch is ignored while disabled.
 */
class PatternGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Fired on ACTION_UP with the drawn cell sequence (0-8, row-major). */
    var onPatternComplete: ((List<Int>) -> Unit)? = null

    private val selected = mutableListOf<Int>()
    private val centers = FloatArray(18) // x,y per cell, set in onSizeChanged
    private var dotRadius = 0f
    private var touchRadius = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private var drawing = false

    private val clearRunnable = Runnable {
        selected.clear()
        drawing = false
        invalidate()
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = COLOR_OUTLINE
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_GOLD
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = COLOR_GOLD
    }

    /** Clears the current selection and drawing immediately. */
    fun reset() {
        removeCallbacks(clearRunnable)
        selected.clear()
        drawing = false
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Square: take the smaller dimension.
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val size = when {
            width > 0 && height > 0 -> minOf(width, height)
            width > 0 -> width
            height > 0 -> height
            else -> suggestedMinimumWidth.coerceAtLeast(0)
        }
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = minOf(w, h).toFloat()
        if (size <= 0f) return
        val step = size / 3f
        for (cell in 0..8) {
            centers[cell * 2] = step * (cell % 3 + 0.5f)
            centers[cell * 2 + 1] = step * (cell / 3 + 0.5f)
        }
        dotRadius = size * DOT_RADIUS_RATIO
        touchRadius = dotRadius * TOUCH_RADIUS_MULTIPLIER
        linePaint.strokeWidth = size * LINE_WIDTH_RATIO
        outlinePaint.strokeWidth = (size * OUTLINE_WIDTH_RATIO).coerceAtLeast(2f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(clearRunnable)
                selected.clear()
                drawing = true
                fingerX = event.x
                fingerY = event.y
                cellAt(event.x, event.y)?.let { select(it) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!drawing) return false
                fingerX = event.x
                fingerY = event.y
                cellAt(event.x, event.y)?.let { select(it) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!drawing) return false
                fingerX = event.x
                fingerY = event.y
                val pattern = selected.toList()
                // Keep the pattern visible briefly, then clear it.
                postDelayed(clearRunnable, CLEAR_DELAY_MS)
                onPatternComplete?.invoke(pattern)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                reset()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dotRadius <= 0f) return
        // Connecting lines first, dots on top.
        if (selected.size > 1) {
            for (i in 0 until selected.size - 1) {
                canvas.drawLine(
                    centers[selected[i] * 2], centers[selected[i] * 2 + 1],
                    centers[selected[i + 1] * 2], centers[selected[i + 1] * 2 + 1],
                    linePaint,
                )
            }
        }
        if (drawing && selected.isNotEmpty()) {
            val last = selected.last()
            canvas.drawLine(
                centers[last * 2], centers[last * 2 + 1],
                fingerX, fingerY, linePaint,
            )
        }
        for (cell in 0..8) {
            val x = centers[cell * 2]
            val y = centers[cell * 2 + 1]
            if (cell in selected) {
                canvas.drawCircle(x, y, dotRadius, selectedPaint)
            } else {
                canvas.drawCircle(x, y, dotRadius, outlinePaint)
            }
        }
    }

    private fun select(cell: Int) {
        if (cell in selected) return
        // Intermediate-dot rule: a line through a third cell's center
        // auto-selects that cell first.
        val last = selected.lastOrNull()
        if (last != null) {
            intermediate(last, cell)?.let { middle ->
                if (middle !in selected) selected.add(middle)
            }
        }
        selected.add(cell)
    }

    private fun intermediate(a: Int, b: Int): Int? {
        val key = if (a < b) a * 10 + b else b * 10 + a
        return INTERMEDIATES[key]
    }

    private fun cellAt(x: Float, y: Float): Int? {
        if (dotRadius <= 0f) return null
        for (cell in 0..8) {
            val dx = x - centers[cell * 2]
            val dy = y - centers[cell * 2 + 1]
            if (dx * dx + dy * dy <= touchRadius * touchRadius) return cell
        }
        return null
    }

    companion object {
        private const val COLOR_OUTLINE = 0xFF344358.toInt()
        private const val COLOR_GOLD = 0xFFC6A46A.toInt()
        private const val DOT_RADIUS_RATIO = 0.055f
        private const val TOUCH_RADIUS_MULTIPLIER = 1.5f
        private const val LINE_WIDTH_RATIO = 0.02f
        private const val OUTLINE_WIDTH_RATIO = 0.008f
        private const val CLEAR_DELAY_MS = 300L

        // (0,2)->1, (6,8)->7, (0,6)->3, (2,8)->5, (0,8)->4, (2,6)->4.
        // Keys are the unordered pair as min*10+max, so both directions match.
        private val INTERMEDIATES = mapOf(
            2 to 1, 68 to 7, 6 to 3, 28 to 5, 8 to 4, 26 to 4,
        )
    }
}
