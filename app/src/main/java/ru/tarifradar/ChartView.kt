package ru.tarifradar

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Простой график без сторонних библиотек: линии или столбики. */
class ChartView(ctx: Context) : View(ctx) {

    class Series(val name: String, val color: Int, val xs: FloatArray, val ys: FloatArray)

    var series: List<Series> = emptyList()
    var xMin = 0f
    var xMax = 24f
    var xTicks: List<Float> = emptyList()
    var xLabel: (Float) -> String = { it.toInt().toString() }
    var threshold: Float? = null
    var bars = false
    var fixedYMax: Float? = null
    var ySuffix = ""
    /** Если между точками больше этого расстояния по X — линию не соединяем (вас не было дома). */
    var gap = Float.MAX_VALUE
    var emptyText = "Пока нет данных"

    private val d = resources.displayMetrics.density
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9E9E9E.toInt(); strokeWidth = d }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE6E6E6.toInt(); strokeWidth = d }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF616161.toInt(); textSize = 10 * d }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (220 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val left = 40 * d
        val right = width - 10 * d
        val top = 10 * d
        val bottom = height - 22 * d

        val all = series.flatMap { it.ys.asList() }
        if (all.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            c.drawText(emptyText, width / 2f, height / 2f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            return
        }
        var yMax = fixedYMax ?: (maxOf(all.max(), threshold ?: 0f) * 1.15f)
        if (yMax <= 0f) yMax = 1f
        val yStep = niceStep(yMax / 4f)
        yMax = ceil(yMax / yStep) * yStep
        val span = (xMax - xMin).takeIf { it > 0f } ?: 1f

        fun px(x: Float) = left + (x - xMin) / span * (right - left)
        fun py(y: Float) = bottom - y / yMax * (bottom - top)

        // Сетка и подписи по вертикали
        textPaint.textAlign = Paint.Align.RIGHT
        var v = 0f
        while (v <= yMax + yStep * 0.01f) {
            val yy = py(v)
            c.drawLine(left, yy, right, yy, gridPaint)
            c.drawText(fmtY(v) + ySuffix, left - 4 * d, yy + 3.5f * d, textPaint)
            v += yStep
        }
        // Подписи по горизонтали
        textPaint.textAlign = Paint.Align.CENTER
        for (t in xTicks) {
            val xx = px(t)
            c.drawLine(xx, bottom, xx, bottom + 3 * d, axisPaint)
            c.drawText(xLabel(t), xx.coerceIn(left + 14 * d, right - 14 * d), bottom + 15 * d, textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
        c.drawLine(left, bottom, right, bottom, axisPaint)

        threshold?.let {
            val yy = py(it)
            c.drawPath(Path().apply { moveTo(left, yy); lineTo(right, yy) }, thrPaint)
        }

        if (bars) {
            val bw = (right - left) / span * 0.7f
            for (s in series) {
                fillPaint.color = s.color
                for (i in s.xs.indices) {
                    val xx = px(s.xs[i])
                    c.drawRect(xx - bw / 2, py(s.ys[i]), xx + bw / 2, bottom, fillPaint)
                }
            }
            return
        }

        for (s in series) {
            linePaint.color = s.color
            fillPaint.color = s.color
            val path = Path()
            for (i in s.xs.indices) {
                val xx = px(s.xs[i])
                val yy = py(s.ys[i])
                val breakHere = i == 0 || s.xs[i] - s.xs[i - 1] > gap
                if (breakHere) path.moveTo(xx, yy) else path.lineTo(xx, yy)
                // Одиночную точку (без соседей) рисуем кружком, иначе её не видно
                val prevFar = breakHere
                val nextFar = i == s.xs.size - 1 || s.xs[i + 1] - s.xs[i] > gap
                if (s.xs.size <= 60 || (prevFar && nextFar)) c.drawCircle(xx, yy, 2.5f * d, fillPaint)
            }
            c.drawPath(path, linePaint)
        }
    }

    private fun niceStep(raw: Float): Float {
        if (raw <= 0f) return 1f
        val e = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
        val f = raw / e
        return e * when {
            f <= 1f -> 1f
            f <= 2f -> 2f
            f <= 5f -> 5f
            else -> 10f
        }
    }

    private fun fmtY(v: Float) =
        if (v == floor(v)) v.toInt().toString() else String.format(Locale.US, "%.1f", v)
}
