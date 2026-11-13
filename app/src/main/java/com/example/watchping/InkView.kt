package com.example.watchping

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.mlkit.vision.digitalink.Ink

/** Поле для письма пальцем или стилусом. Собирает штрихи для распознавания ML Kit. */
class InkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val guide = Paint().apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 1.5f * density
    }

    private val paths = mutableListOf<Path>()
    private var currentPath: Path? = null
    private var inkBuilder: Ink.Builder = Ink.builder()
    private var strokeBuilder: Ink.Stroke.Builder? = null

    var hasInk = false
        private set

    fun buildInk(): Ink = inkBuilder.build()

    fun clear() {
        paths.clear()
        currentPath = null
        inkBuilder = Ink.builder()
        strokeBuilder = null
        hasInk = false
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val p = Path().apply { moveTo(x, y) }
                currentPath = p
                paths.add(p)
                strokeBuilder = Ink.Stroke.builder().apply {
                    addPoint(Ink.Point.create(x, y, e.eventTime))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) {
                    val hx = e.getHistoricalX(i)
                    val hy = e.getHistoricalY(i)
                    currentPath?.lineTo(hx, hy)
                    strokeBuilder?.addPoint(Ink.Point.create(hx, hy, e.getHistoricalEventTime(i)))
                }
                currentPath?.lineTo(x, y)
                strokeBuilder?.addPoint(Ink.Point.create(x, y, e.eventTime))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                strokeBuilder?.let {
                    it.addPoint(Ink.Point.create(x, y, e.eventTime))
                    inkBuilder.addStroke(it.build())
                    hasInk = true
                }
                strokeBuilder = null
                currentPath = null
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val gy = height * 0.68f
        canvas.drawLine(20f * density, gy, width - 20f * density, gy, guide)
        paths.forEach { canvas.drawPath(it, paint) }
    }
}
