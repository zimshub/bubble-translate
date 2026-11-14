package com.zim.bubbletranslate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View

/** The round floating button. Blue when ready, dim while working. */
class BubbleView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = Color.argb(90, 0, 0, 0)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textSize = 17f * d
    }

    var busy = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (52 * d).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f - 2 * d
        fill.color = if (busy) Color.rgb(15, 78, 120) else Color.rgb(29, 155, 240)
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
        val baseline = cy - (label.descent() + label.ascent()) / 2f
        canvas.drawText(if (busy) "..." else "Aa", cx, baseline, label)
    }
}

/**
 * Covers the whole screen and draws each translated block over the place the
 * original text was. Tap anywhere to close it.
 */
@SuppressLint("ViewConstructor")
class OverlayView(
    ctx: Context,
    private val result: PageResult,
    private val onClose: () -> Unit
) : View(ctx) {

    private class Laid(val box: RectF, val layout: StaticLayout, val bg: Int)

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(231, 233, 234)
        textSize = 13f * d
        textAlign = Paint.Align.CENTER
    }
    private val origin = IntArray(2)
    private var laid: List<Laid>? = null
    private val caption = "${Prefs.name(result.from)} to ${Prefs.name(result.to)}. Tap anywhere to close."

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        laid = null
    }

    private fun layoutFor(text: String, paint: TextPaint, width: Int, centred: Boolean): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(if (centred) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.05f)
            .build()

    /**
     * Decides where each translation goes and how big its letters are.
     * A translation is rarely the same length as the original, so the font
     * shrinks until the text fits the original box. If it still doesn't fit
     * at the smallest readable size, the box grows downwards instead.
     */
    private fun prepare(screenW: Int, screenH: Int): List<Laid> {
        val padX = 5 * d
        val padY = 3 * d
        val minSize = 10f * d
        val out = ArrayList<Laid>()
        for (p in result.pieces) {
            val box = RectF(p.box)
            box.inset(-padX, -padY)

            // Japanese speech bubbles are often tall and thin (text runs top
            // to bottom). Sideways text needs more width than that.
            val minW = 120 * d
            if (box.width() < minW) {
                val cx = box.centerX()
                box.left = cx - minW / 2
                box.right = cx + minW / 2
            }
            if (box.left < 2 * d) box.offset(2 * d - box.left, 0f)
            if (box.right > screenW - 2 * d) box.offset(screenW - 2 * d - box.right, 0f)
            if (box.left < 0f) box.left = 0f

            val centred = box.width() < screenW * 0.6f
            val inner = (box.width() - 2 * padX).toInt()

            val originalLine = p.box.height().toFloat() / p.lines
            var size = (originalLine * 0.8f).coerceIn(minSize, 22f * d)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
            paint.color = p.fg
            paint.textSize = size
            var layout = layoutFor(p.text, paint, inner, centred)
            while (layout.height > box.height() - 2 * padY && size > minSize) {
                size = (size - d).coerceAtLeast(minSize)
                paint.textSize = size
                layout = layoutFor(p.text, paint, inner, centred)
            }
            val needed = layout.height + 2 * padY
            if (needed > box.height()) box.bottom = box.top + needed
            if (box.bottom > screenH) box.offset(0f, screenH - box.bottom)

            out.add(Laid(box, layout, p.bg))
        }
        return out
    }

    override fun onDraw(canvas: Canvas) {
        // The picture was of the whole screen, so positions are measured from
        // the screen's top-left corner. Shift the drawing in case this view
        // doesn't start exactly there.
        getLocationOnScreen(origin)
        val screenW = width + origin[0]
        val screenH = height + origin[1]
        val items = laid ?: prepare(screenW, screenH).also { laid = it }

        canvas.save()
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        val padX = 5 * d
        for (item in items) {
            fill.color = item.bg
            canvas.drawRoundRect(item.box, 6 * d, 6 * d, fill)
            canvas.save()
            canvas.translate(item.box.left + padX, item.box.top + (item.box.height() - item.layout.height) / 2f)
            item.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()

        // A small label at the bottom saying which languages were used.
        val textW = pillText.measureText(caption)
        val pillH = 30 * d
        val cx = width / 2f
        val bottom = height - 56 * d
        val pill = RectF(cx - textW / 2 - 14 * d, bottom - pillH, cx + textW / 2 + 14 * d, bottom)
        fill.color = Color.argb(235, 22, 24, 28)
        canvas.drawRoundRect(pill, pillH / 2, pillH / 2, fill)
        val baseline = pill.centerY() - (pillText.descent() + pillText.ascent()) / 2f
        canvas.drawText(caption, cx, baseline, pillText)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) onClose()
        return true
    }
}
