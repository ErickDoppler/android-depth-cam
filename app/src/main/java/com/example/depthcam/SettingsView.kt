package com.example.depthcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * SETTINGS page of the depth camera, opened by a long display press or by
 * holding Volume Down. Scroll by dragging; tap a button to toggle a setting
 * (applied and persisted immediately by the host). Back, any volume button,
 * or a long display press exits back to the depth view.
 */
class SettingsView(context: Context, private val state: StreamState) : View(context) {

    interface Host {
        fun setStreamMode(mode: Int)
        fun editStreamUrl()
        fun editStreamKey()
        fun exitRequested()
    }

    var host: Host? = null

    private val dp get() = resources.displayMetrics.density
    private val grey = Color.rgb(150, 150, 150)

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = grey
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        color = grey
    }

    private var scrollY = 0f
    private var contentH = 0f

    // Tap targets recorded during the last draw (view coords, scroll baked in).
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()

    // --------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        hits.clear()
        text.color = grey
        stroke.color = grey
        val w = width.toFloat()
        if (w == 0f) return
        var y = 20f * dp - scrollY

        // Header.
        text.textAlign = Paint.Align.CENTER
        text.textSize = 18f * dp
        text.isFakeBoldText = true
        canvas.drawText("SETTINGS", w / 2f, y + 14f * dp, text)
        text.isFakeBoldText = false
        y += 26f * dp
        text.textSize = 10f * dp
        canvas.drawText("VOLUME / BACK / LONG PRESS - EXIT", w / 2f, y + 10f * dp, text)
        y += 26f * dp

        // ------------------------------------------------------ STREAM VIDEO
        y = drawSetting(
            canvas, y, "STREAM VIDEO",
            listOf("OFF", "STREAM"),
            state.streamMode.coerceIn(0, 1),
            emptyList()
        ) { i -> host?.setStreamMode(i) }
        if (state.streamMode == 1) {
            y = drawTapField(
                canvas, y,
                state.streamUrl.ifEmpty { "TAP TO ENTER SERVER URL" },
                if (state.streamUrlOk) "OK" else ""
            ) { host?.editStreamUrl() }
            y = drawTapField(
                canvas, y,
                state.streamKey.ifEmpty { "TAP TO ENTER KEY" },
                when {
                    state.streamKeyOk -> "OK"
                    state.streamKeyBusy -> "BUSY"
                    else -> ""
                }
            ) { host?.editStreamKey() }
        }

        contentH = y + scrollY + 24f * dp
    }

    /** Tappable value box (URL / key entry) with a status marker right of it. */
    private fun drawTapField(
        canvas: Canvas, startY: Float, label: String, marker: String,
        onTap: () -> Unit
    ): Float {
        text.textAlign = Paint.Align.LEFT
        text.textSize = 12f * dp
        val left = 18f * dp
        val boxW = max(text.measureText(label) + 24f * dp, 240f * dp)
        val box = RectF(left, startY, left + boxW, startY + 30f * dp)
        stroke.strokeWidth = 1.5f * dp
        canvas.drawRoundRect(box, 6f * dp, 6f * dp, stroke)
        canvas.drawText(label, left + 12f * dp, box.centerY() + 4f * dp, text)
        hits.add(RectF(box).apply { inset(-5f * dp, -10f * dp) } to onTap)
        if (marker.isNotEmpty()) {
            text.isFakeBoldText = true
            canvas.drawText(marker, box.right + 12f * dp, box.centerY() + 4f * dp, text)
            text.isFakeBoldText = false
        }
        return box.bottom + 14f * dp
    }

    /**
     * One setting block: name, option buttons (wrapping when needed),
     * description lines under them. Returns the y below the block.
     */
    private fun drawSetting(
        canvas: Canvas, startY: Float, title: String, options: List<String>,
        selected: Int, notes: List<String>, onSelect: (Int) -> Unit
    ): Float {
        var y = startY
        val left = 18f * dp

        text.textAlign = Paint.Align.LEFT
        text.textSize = 14f * dp
        text.isFakeBoldText = true
        text.color = grey
        canvas.drawText(title, left, y + 12f * dp, text)
        text.isFakeBoldText = false
        y += 22f * dp

        // Buttons row(s): wraps when a button would leave the screen.
        text.textSize = 12f * dp
        val btnH = 30f * dp
        var x = left
        for ((i, label) in options.withIndex()) {
            val btnW = text.measureText(label) + 24f * dp
            if (x > left && x + btnW > width - 18f * dp) {
                x = left
                y += btnH + 8f * dp
            }
            val box = RectF(x, y, x + btnW, y + btnH)
            if (i == selected) {
                fill.color = grey
                canvas.drawRoundRect(box, 6f * dp, 6f * dp, fill)
                text.color = Color.BLACK
            } else {
                stroke.strokeWidth = 1.5f * dp
                canvas.drawRoundRect(box, 6f * dp, 6f * dp, stroke)
                text.color = grey
            }
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(label, box.centerX(), box.centerY() + 4f * dp, text)
            val idx = i
            hits.add(RectF(box).apply { inset(-5f * dp, -10f * dp) } to {
                if (idx != selected) onSelect(idx)
            })
            x += btnW + 10f * dp
        }
        text.color = grey
        y += btnH + 8f * dp

        // Description lines.
        text.textAlign = Paint.Align.LEFT
        text.textSize = 11f * dp
        for (line in notes) {
            canvas.drawText(line, left, y + 9f * dp, text)
            y += 15f * dp
        }
        return y + 14f * dp
    }

    // ----------------------------------------------------------------- touch

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var dragged = false
    private val longExit = Runnable { host?.exitRequested() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val slop = 20f * dp
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastY = event.y
                downTime = event.eventTime
                dragged = false
                handler?.postDelayed(longExit, 700L)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.y - downY) > slop || abs(event.x - downX) > slop) {
                    dragged = true
                    handler?.removeCallbacks(longExit)
                }
                if (dragged) {
                    scrollY = (scrollY - (event.y - lastY))
                        .coerceIn(0f, max(0f, contentH - height))
                    invalidate()
                }
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                handler?.removeCallbacks(longExit)
                if (!dragged && event.eventTime - downTime < 400L) {
                    for ((box, action) in hits) {
                        if (box.contains(event.x, event.y)) {
                            action()
                            invalidate()
                            break
                        }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> handler?.removeCallbacks(longExit)
        }
        return true
    }
}
