package com.textlens.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object SelectMode {
    const val EXTRA = "mode"
    const val PEN = "pen"
    const val BOX = "box"
}

/**
 * Shows the screenshot and lets the user pick an area, Circle-to-Search style:
 *  - PEN: draw a thin stroke around/over the text; when the finger lifts the stroke
 *    automatically becomes a box that fits it.
 *  - BOX: drag a rectangle.
 * Either way the resulting box can be MOVED (drag inside) and RESIZED (drag any corner or edge handle).
 */
class SelectionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }

    /** Changing the mode keeps the current box. */
    var mode: String = SelectMode.BOX

    var onSelectionChanged: ((Boolean) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.CYAN)

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val penGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; alpha = 90; style = Paint.Style.STROKE
        strokeWidth = 5f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val penCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; alpha = 36 }
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = 2f * d
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = 2f * d
    }

    private val handleR = 6f * d
    private val hitR = 26f * d
    private val minSize = 36f * d
    private val autoPad = 8f * d

    private val full = RectF()
    private val path = Path()
    private val pathBounds = RectF()
    private var rect: RectF? = null
    private var committed = false

    private enum class Drag { NONE, NEW_PEN, NEW_BOX, MOVE, RESIZE }
    private var drag = Drag.NONE
    private var handle = -1
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f

    fun clearSelection() {
        path.reset(); rect = null; committed = false; drag = Drag.NONE
        onSelectionChanged?.invoke(false)
        invalidate()
    }

    /** Selected rectangle in VIEW coordinates, or null. */
    fun selectionRect(): RectF? = if (committed) rect?.let { RectF(it) } else null

    /** The selected part of the screenshot as its own bitmap. */
    fun cropSelection(): Bitmap? {
        val bmp = bitmap ?: return null
        val r = selectionRect() ?: return null
        if (width == 0 || height == 0) return null
        val sx = bmp.width.toFloat() / width
        val sy = bmp.height.toFloat() / height
        val l = max(0, (r.left * sx).toInt())
        val t = max(0, (r.top * sy).toInt())
        val rr = min(bmp.width, (r.right * sx).toInt())
        val b = min(bmp.height, (r.bottom * sy).toInt())
        if (rr - l < 8 || b - t < 8) return null
        return Bitmap.createBitmap(bmp, l, t, rr - l, b - t)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        full.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bmp, null, full, bmpPaint)
        canvas.drawRect(full, dimPaint)

        val r = rect
        if (r != null && (committed || drag == Drag.NEW_BOX)) {
            canvas.save()
            canvas.clipRect(r)
            canvas.drawBitmap(bmp, null, full, bmpPaint) // un-dim the chosen area
            canvas.restore()
            canvas.drawRect(r, boxFill)
            canvas.drawRect(r, boxStroke)
            if (committed && drag != Drag.NEW_BOX) {
                for (p in handlePoints(r)) {
                    canvas.drawCircle(p[0], p[1], handleR, handleFill)
                    canvas.drawCircle(p[0], p[1], handleR, handleStroke)
                }
            }
        }
        if (drag == Drag.NEW_PEN) {
            canvas.drawPath(path, penGlow)
            canvas.drawPath(path, penCore)
        }
    }

    /** TL, T, TR, R, BR, B, BL, L */
    private fun handlePoints(r: RectF): List<FloatArray> {
        val cx = r.centerX(); val cy = r.centerY()
        return listOf(
            floatArrayOf(r.left, r.top), floatArrayOf(cx, r.top), floatArrayOf(r.right, r.top),
            floatArrayOf(r.right, cy), floatArrayOf(r.right, r.bottom), floatArrayOf(cx, r.bottom),
            floatArrayOf(r.left, r.bottom), floatArrayOf(r.left, cy)
        )
    }

    private fun hitHandle(r: RectF, x: Float, y: Float): Int {
        var best = -1
        var bestD = hitR
        handlePoints(r).forEachIndexed { i, p ->
            val dist = hypot(p[0] - x, p[1] - y)
            if (dist < bestD) { bestD = dist; best = i }
        }
        return best
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x.coerceIn(0f, width.toFloat())
        val y = e.y.coerceIn(0f, height.toFloat())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = rect
                if (committed && r != null) {
                    val h = hitHandle(r, x, y)
                    if (h >= 0) { drag = Drag.RESIZE; handle = h; lastX = x; lastY = y; return true }
                    if (r.contains(x, y)) { drag = Drag.MOVE; lastX = x; lastY = y; return true }
                }
                startNew(x, y)
            }
            MotionEvent.ACTION_MOVE -> when (drag) {
                Drag.NEW_PEN -> {
                    for (i in 0 until e.historySize) addPoint(e.getHistoricalX(i), e.getHistoricalY(i))
                    addPoint(x, y)
                }
                Drag.NEW_BOX -> rect = RectF(min(startX, x), min(startY, y), max(startX, x), max(startY, y))
                Drag.MOVE -> { moveBy(x - lastX, y - lastY); lastX = x; lastY = y }
                Drag.RESIZE -> { resizeTo(x, y) }
                Drag.NONE -> {}
            }
            MotionEvent.ACTION_UP -> finish()
            MotionEvent.ACTION_CANCEL -> { if (drag == Drag.NEW_PEN || drag == Drag.NEW_BOX) clearSelection() else drag = Drag.NONE }
        }
        invalidate()
        return true
    }

    private fun startNew(x: Float, y: Float) {
        val wasCommitted = committed
        rect = null; committed = false; path.reset()
        if (wasCommitted) onSelectionChanged?.invoke(false)
        startX = x; startY = y
        if (mode == SelectMode.PEN) {
            drag = Drag.NEW_PEN
            path.moveTo(x, y)
            pathBounds.set(x, y, x, y)
        } else {
            drag = Drag.NEW_BOX
            rect = RectF(x, y, x, y)
        }
    }

    private fun addPoint(x: Float, y: Float) {
        path.lineTo(x, y)
        pathBounds.union(x, y)
    }

    private fun finish() {
        when (drag) {
            Drag.NEW_PEN -> {
                // The stroke turns into a box that fits it.
                if (pathBounds.width() + pathBounds.height() > 30f * d) {
                    rect = RectF(
                        (pathBounds.left - autoPad).coerceAtLeast(0f),
                        (pathBounds.top - autoPad).coerceAtLeast(0f),
                        (pathBounds.right + autoPad).coerceAtMost(width.toFloat()),
                        (pathBounds.bottom + autoPad).coerceAtMost(height.toFloat())
                    )
                    committed = true
                } else rect = null
                path.reset()
            }
            Drag.NEW_BOX -> {
                val r = rect
                committed = r != null && r.width() > 24f * d && r.height() > 24f * d
                if (!committed) rect = null
            }
            else -> {}
        }
        drag = Drag.NONE
        onSelectionChanged?.invoke(committed)
    }

    private fun moveBy(dx: Float, dy: Float) {
        val r = rect ?: return
        var ddx = dx; var ddy = dy
        if (r.left + ddx < 0) ddx = -r.left
        if (r.right + ddx > width) ddx = width - r.right
        if (r.top + ddy < 0) ddy = -r.top
        if (r.bottom + ddy > height) ddy = height - r.bottom
        r.offset(ddx, ddy)
    }

    /** handle: 0 TL, 1 T, 2 TR, 3 R, 4 BR, 5 B, 6 BL, 7 L — drag moves the matching edges. */
    private fun resizeTo(x: Float, y: Float) {
        val r = rect ?: return
        if (handle in intArrayOf(0, 6, 7)) r.left = min(x, r.right - minSize)
        if (handle in intArrayOf(2, 3, 4)) r.right = max(x, r.left + minSize)
        if (handle in intArrayOf(0, 1, 2)) r.top = min(y, r.bottom - minSize)
        if (handle in intArrayOf(4, 5, 6)) r.bottom = max(y, r.top + minSize)
    }
}
