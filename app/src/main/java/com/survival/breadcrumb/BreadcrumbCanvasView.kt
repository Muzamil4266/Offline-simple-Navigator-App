package com.survival.breadcrumb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Dark 2D view. North is up. One plain straight line: START -> YOU.
 * Line grows / turns as user moves. No rings, no winding trail.
 */
class BreadcrumbCanvasView(ctx: Context) : View(ctx) {

    var headingDeg = 0f // true-north heading of phone

    private val dp = resources.displayMetrics.density
    private val mDeg = 111_320.0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Path()

    private var refLat = 0.0
    private var refLon = 0.0
    private var cosLat = 1.0
    private var scale = 1f
    private var cx = 0f
    private var cy = 0f

    private companion object {
        const val BG = 0xFF0B0F14.toInt()
        const val RING_TXT = 0xFF55657A.toInt()
        const val LINE = 0xFF5AA9E6.toInt()
        const val GREEN = 0xFF2EE66B.toInt()
        const val BLUE = 0xFF2E9BFF.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val PANEL = 0xDD14202B.toInt()
    }

    private fun sx(lon: Double): Float = cx + ((lon - refLon) * mDeg * cosLat * scale).toFloat()
    private fun sy(lat: Double): Float = cy - ((lat - refLat) * mDeg * scale).toFloat()

    /** Smallest 1-2-5 step (min 30 m) that holds the distance. Keeps line length meaningful. */
    private fun niceRange(d: Float): Float {
        val need = max(d * 1.1f, 30f)
        var base = 10f
        while (true) {
            for (m in floatArrayOf(1f, 2f, 5f)) {
                if (base * m >= need) return base * m
            }
            base *= 10f
        }
    }

    private fun fmt(m: Float): String =
        if (m < 1000f) "${m.toInt()} m" else String.format(Locale.US, "%.1f km", m / 1000f)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(BG)

        val t = BreadcrumbTracker
        val cur = t.current
        val start = t.pins.firstOrNull()

        if (cur == null) {
            paint.style = Paint.Style.FILL
            paint.color = RING_TXT
            paint.textSize = 18 * dp
            paint.textAlign = Paint.Align.CENTER
            c.drawText("WAITING FOR GPS...", w / 2, h / 2, paint)
            paint.textAlign = Paint.Align.LEFT
            return
        }

        val sLat = start?.lat ?: cur.latitude
        val sLon = start?.lon ?: cur.longitude

        // ---- projection: centre on midpoint of START and YOU
        cx = w / 2
        cy = h / 2
        refLat = (sLat + cur.latitude) / 2
        refLon = (sLon + cur.longitude) / 2
        cosLat = cos(Math.toRadians(refLat))
        val distM = t.distFromStart() ?: 0f
        val range = niceRange(distM)
        scale = min(w, h) * 0.8f / range

        val x0 = sx(sLon)
        val y0 = sy(sLat)
        val x1 = sx(cur.longitude)
        val y1 = sy(cur.latitude)

        // ---- the line
        val homing = t.returnMode && !t.arrived
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4 * dp
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = if (homing) GREEN else LINE
        c.drawLine(x0, y0, x1, y1, paint)

        // ---- distance label on the middle of the line
        val label = fmt(distM)
        paint.style = Paint.Style.FILL
        paint.textSize = 14 * dp
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        val tw = paint.measureText(label)
        val mx = (x0 + x1) / 2
        val my = (y0 + y1) / 2
        paint.color = BG
        c.drawRoundRect(
            mx - tw / 2 - 7 * dp, my - 12 * dp, mx + tw / 2 + 7 * dp, my + 12 * dp,
            8 * dp, 8 * dp, paint
        )
        paint.color = WHITE
        c.drawText(label, mx, my + 5 * dp, paint)
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.DEFAULT

        // ---- START marker
        paint.style = Paint.Style.FILL
        paint.color = GREEN
        c.drawCircle(x0, y0, 11 * dp, paint)
        paint.color = BG
        paint.textSize = 13 * dp
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        c.drawText("S", x0, y0 + 4.5f * dp, paint)
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.DEFAULT

        // ---- YOU marker + heading tick
        val rad = Math.toRadians(headingDeg.toDouble())
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * dp
        paint.color = BLUE
        c.drawLine(
            x1, y1,
            x1 + (sin(rad) * 28 * dp).toFloat(),
            y1 - (cos(rad) * 28 * dp).toFloat(),
            paint
        )
        paint.style = Paint.Style.FILL
        paint.color = BLUE
        c.drawCircle(x1, y1, 9 * dp, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * dp
        paint.color = WHITE
        c.drawCircle(x1, y1, 9 * dp, paint)

        // ---- north marker (top-left)
        paint.style = Paint.Style.FILL
        paint.color = WHITE
        arrow.reset()
        arrow.moveTo(18 * dp, 8 * dp)
        arrow.lineTo(26 * dp, 24 * dp)
        arrow.lineTo(10 * dp, 24 * dp)
        arrow.close()
        c.drawPath(arrow, paint)
        paint.textSize = 14 * dp
        paint.textAlign = Paint.Align.CENTER
        c.drawText("N", 18 * dp, 42 * dp, paint)
        paint.textAlign = Paint.Align.LEFT

        // ---- hint (bottom-left)
        paint.color = RING_TXT
        paint.textSize = 12 * dp
        c.drawText("START TO YOU - NORTH UP", 8 * dp, h - 8 * dp, paint)

        // ---- return arrow (top-right), rotates with phone heading
        if (t.returnMode) {
            val ax = w - 52 * dp
            val ay = 52 * dp
            paint.style = Paint.Style.FILL
            paint.color = PANEL
            c.drawCircle(ax, ay, 46 * dp, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * dp
            paint.color = GREEN
            c.drawCircle(ax, ay, 46 * dp, paint)

            if (t.arrived) {
                paint.style = Paint.Style.FILL
                paint.textSize = 18 * dp
                paint.textAlign = Paint.Align.CENTER
                c.drawText("HOME", ax, ay + 6 * dp, paint)
                paint.textAlign = Paint.Align.LEFT
            } else {
                val b = t.targetBearing()
                if (b != null) {
                    c.save()
                    c.rotate(b - headingDeg, ax, ay)
                    arrow.reset()
                    arrow.moveTo(ax, ay - 36 * dp)
                    arrow.lineTo(ax + 22 * dp, ay + 26 * dp)
                    arrow.lineTo(ax, ay + 12 * dp)
                    arrow.lineTo(ax - 22 * dp, ay + 26 * dp)
                    arrow.close()
                    paint.style = Paint.Style.FILL
                    paint.color = GREEN
                    c.drawPath(arrow, paint)
                    c.restore()
                }
            }
        }
    }
}
