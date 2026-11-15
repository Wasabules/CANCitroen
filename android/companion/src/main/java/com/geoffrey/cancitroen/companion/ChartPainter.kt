package com.geoffrey.cancitroen.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dessine un graphique sur un Canvas Android : le même code pour l'écran du téléphone et pour
 * l'image envoyée à Android Auto, qui n'affiche que ses modèles et des images.
 * Les tailles sont en dp, multipliées par [scale] (pixels par dp).
 */
object ChartPainter {
    private const val MAX_BUCKETS = 240
    private const val BACKGROUND = 0xFF121418.toInt()

    fun color(metric: Metric): Int = when (metric) {
        Metric.SPEED -> 0xFF90CAF9.toInt()
        Metric.RPM -> 0xFFCE93D8.toInt()
        Metric.FUEL -> 0xFFFFD54F.toInt()
        Metric.COOLANT -> 0xFF4FC3F7.toInt()
        Metric.OIL -> 0xFFFFB74D.toInt()
        Metric.OUTSIDE -> 0xFFA5D6A7.toInt()
        Metric.CONSUMPTION -> 0xFFEF9A9A.toInt()
    }

    /** Les courbes de [kind] ramenées à la largeur du graphique : ce que l'on dessine, et ce que lit le curseur. */
    fun prepare(kind: ChartKind, series: Map<Metric, Series>, from: Long, to: Long, plotWidthPx: Float, scale: Float): Map<Metric, Series> {
        val buckets = (plotWidthPx / (2 * scale)).toInt().coerceIn(10, MAX_BUCKETS)
        return kind.metrics.associateWith { (series[it] ?: Series(LongArray(0), DoubleArray(0))).downsampled(from, to, buckets) }
    }

    /** La dernière valeur lue de chaque courbe, pour la légende : pas la moyenne de la dernière tranche. */
    fun latest(kind: ChartKind, series: Map<Metric, Series>): Map<Metric, Double?> =
        kind.metrics.associateWith { series[it]?.lastValue() }

    fun bitmap(kind: ChartKind, series: Map<Metric, Series>, from: Long, to: Long, widthPx: Int, heightPx: Int, scale: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        val plot = widthPx - (LEFT_DP + RIGHT_DP) * scale
        val prepared = prepare(kind, series, from, to, plot, scale)
        draw(canvas, widthPx.toFloat(), heightPx.toFloat(), kind, prepared, latest(kind, series), from, to, scale)
        return bitmap
    }

    /**
     * [prepared] vient de [prepare], [latest] de [latest]. [cursor] : instant pointé, dont les
     * valeurs remplacent les dernières dans la légende.
     */
    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        kind: ChartKind,
        prepared: Map<Metric, Series>,
        latest: Map<Metric, Double?>,
        from: Long,
        to: Long,
        scale: Float,
        cursor: Long? = null,
    ) {
        val left = LEFT_DP * scale
        val right = width - RIGHT_DP * scale
        val top = TOP_DP * scale
        val bottom = height - BOTTOM_DP * scale
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB3FFFFFF.toInt()
            textSize = 11 * scale
        }
        val grid = Paint().apply {
            color = 0x33FFFFFF
            strokeWidth = scale
        }
        val values = prepared.values.flatMap { s -> s.values.filter { !it.isNaN() } }
        if (values.isEmpty() || right <= left || bottom <= top) {
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("Pas de données sur cette période", width / 2, height / 2, text)
            return
        }
        var low = kind.fixedMin ?: values.min()
        var high = kind.fixedMax ?: values.max()
        if (high - low < 1e-6) {
            low -= 1
            high += 1
        }
        // Bornes rondes : une échelle de 0 à 60 se lit mieux que de 0 à 57,8.
        val step = niceStep((high - low) / 4)
        if (kind.fixedMin == null) low = kotlin.math.floor(low / step) * step
        if (kind.fixedMax == null) high = kotlin.math.ceil(high / step) * step
        fun y(value: Double) = (bottom - (value - low) / (high - low) * (bottom - top)).toFloat()
        fun x(at: Long) = left + (at - from).toFloat() / (to - from).toFloat() * (right - left)

        // Grille et échelle : bas, milieu, haut.
        val decimals = kind.metrics.first().decimals
        for (step in 0..2) {
            val value = low + (high - low) * step / 2
            val at = y(value)
            canvas.drawLine(left, at, right, at, grid)
            canvas.drawText(format(value, decimals), 2 * scale, at + 4 * scale, text)
        }
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(timeLabel(from, from, to), left, height - 4 * scale, text)
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(timeLabel((from + to) / 2, from, to), (left + right) / 2, height - 4 * scale, text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(timeLabel(to, from, to), right, height - 4 * scale, text)
        text.textAlign = Paint.Align.LEFT

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * scale
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((metric, series) in prepared) {
            line.color = color(metric)
            dot.color = color(metric)
            val path = Path()
            for (i in 0 until series.size) {
                val value = series.values[i]
                if (value.isNaN()) continue
                val previous = i > 0 && !series.values[i - 1].isNaN()
                val next = i < series.size - 1 && !series.values[i + 1].isNaN()
                val px = x(series.ts[i])
                val py = y(value)
                when {
                    previous -> path.lineTo(px, py)
                    next -> path.moveTo(px, py)
                    // Point isolé (un court trajet sur une longue période) : un point, pas un trait.
                    else -> canvas.drawCircle(px, py, 2.5f * scale, dot)
                }
            }
            canvas.drawPath(path, line)
        }

        if (cursor != null) {
            val at = x(cursor.coerceIn(from, to))
            canvas.drawLine(at, top, at, bottom, grid.apply { color = 0x99FFFFFF.toInt() })
        }
        // Légende : valeur au curseur, sinon la dernière connue.
        var legendX = left
        val tolerance = prepared.values.firstOrNull()?.let { if (it.size > 1) it.ts[1] - it.ts[0] else 0L } ?: 0L
        for ((metric, series) in prepared) {
            val value = if (cursor != null) series.valueNear(cursor, tolerance) else latest[metric]
            val label = "${metric.label} ${value?.let { format(it, metric.decimals) } ?: "–"} ${metric.unit}"
            text.color = color(metric)
            canvas.drawText(label, legendX, 12 * scale, text)
            legendX += text.measureText(label) + 12 * scale
        }
        if (cursor != null) {
            text.color = 0xB3FFFFFF.toInt()
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(cursor)), right, 12 * scale, text)
        }
    }

    fun format(value: Double, decimals: Int): String = number(value, decimals)

    /** Un pas de graduation en 1, 2 ou 5 fois une puissance de dix. */
    fun niceStep(raw: Double): Double {
        if (raw <= 0) return 1.0
        val magnitude = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw)))
        val fraction = raw / magnitude
        return magnitude * when {
            fraction <= 1 -> 1.0
            fraction <= 2 -> 2.0
            fraction <= 5 -> 5.0
            else -> 10.0
        }
    }

    /** Les bornes d'une période de 24 h ont la même heure : le jour les distingue. */
    private fun timeLabel(at: Long, from: Long, to: Long): String {
        val span = to - from
        val pattern = when {
            span <= 12L * 3_600_000L -> "HH:mm"
            span <= 3L * 86_400_000L -> "EEE HH:mm"
            span <= 400L * 86_400_000L -> "dd/MM"
            else -> "MM/yyyy"
        }
        return SimpleDateFormat(pattern, Locale.FRANCE).format(Date(at))
    }

    const val LEFT_DP = 34f
    private const val RIGHT_DP = 8f
    private const val TOP_DP = 20f
    private const val BOTTOM_DP = 18f
}
