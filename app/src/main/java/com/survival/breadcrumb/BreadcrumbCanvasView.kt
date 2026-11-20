package com.survival.breadcrumb

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Dark 2D radar. No tiles. North is up. Tap = cycle zoom (FOLLOW mode). */
class BreadcrumbCanvasView(ctx: Context) : View(ctx) {

    var follow = true
    var headingDeg = 0f // true-north heading of phone

    private val zooms = floatArrayOf(60f, 120f, 250f, 500f)
    private var zoomIdx = 1
    private val dp = resources.displayMetrics.density
    private val mDeg = 111_320.0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val arrow = Path()

    private var refLat = 0.0
    private var refLon = 0.0
    private var cosLat = 1.0
    private var scale = 1f
    private var cx = 0f
    private var cy = 0f

    private companion object {
        const val BG = 0xFF0B0F14.toInt()
        const val RING = 0xFF1E2A36.toInt()
        const val RING_TXT = 0xFF55657A.toInt()
        const val TRAIL = 0xFF3B6FA0.toInt()
        const val AUTO = 0xFFB0BEC5.toInt()
        const val AMBER = 0xFFFFB300.toInt()
        const val GREEN = 0xFF2EE66B.toInt()
        const val BLUE = 0xFF2E9BFF.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val PANEL = 0xDD14202B.toInt()
    }

    fun cycleZoom() {
        zoomIdx = (zoomIdx + 1) % zooms.size
        invalidate()
    }

    private fun sx(lon: Double): Float = cx + ((lon - refLon) * mDeg * cosLat * scale).toFloat()
    private fun sy(lat: Double): Float = cy - ((lat - refLat) * mDeg * scale).toFloat()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(BG)

        val t = BreadcrumbTracker
        val cur = t.current
        val pins = t.pins

        if (cur == null && pins.isEmpty()) {
            paint.style = Paint.Style.FILL
            paint.color = RING_TXT
            paint.textSize = 18 * dp
            paint.textAlign = Paint.Align.CENTER
            c.drawText("WAITING FOR GPS...", w / 2, h / 2, paint)
            paint.textAlign = Paint.Align.LEFT
            return
        }

        cx = w / 2
        cy = h / 2

        // ---- projection setup
        if (follow && cur != null) {
            refLat = cur.latitude
            refLon = cur.longitude
            cosLat = cos(Math.toRadians(refLat))
            scale = (min(w, h) / 2f * 0.92f) / zooms[zoomIdx]
        } else {
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            for (p in pins) {
                minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
                minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
            }
            if (cur != null) {
                minLat = min(minLat, cur.latitude); maxLat = max(maxLat, cur.latitude)
                minLon = min(minLon, cur.longitude); maxLon = max(maxLon, cur.longitude)
            }
            refLat = (minLat + maxLat) / 2
            refLon = (minLon + maxLon) / 2
            cosLat = cos(Math.toRadians(refLat))
            val ext = max((maxLat - minLat) * mDeg, (maxLon - minLon) * mDeg * cosLat).toFloat()
            scale = min(w, h) * 0.8f / max(ext, 60f)
        }

        // ---- distance rings around live position (FOLLOW only)
        if (follow && cur != null) {
            val px = sx(cur.longitude)
            val py = sy(cur.latitude)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * dp
            for (r in intArrayOf(30, 60, 100)) {
                val rad = r * scale
                paint.color = RING
                c.drawCircle(px, py, rad, paint)
                if (rad > 20 * dp) {
                    paint.style = Paint.Style.FILL
                    paint.color = RING_TXT
                    paint.textSize = 11 * dp
                    c.drawText("${r}m", px + 4 * dp, py - rad + 13 * dp, paint)
                    paint.style = Paint.Style.STROKE
                }
            }
        }

        // ---- trail
        if (pins.isNotEmpty()) {
            path.reset()
            path.moveTo(sx(pins[0].lon), sy(pins[0].lat))
            for (i in 1 until pins.size) path.lineTo(sx(pins[i].lon), sy(pins[i].lat))
            if (cur != null) path.lineTo(sx(cur.longitude), sy(cur.latitude))
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3 * dp
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = TRAIL
            c.drawPath(path, paint)
        }

        // ---- return route highlight (dashed green): you -> next pin -> ... -> START
        if (t.returnMode && !t.arrived && t.returnIdx in pins.indices) {
            path.reset()
            if (cur != null) {
                path.moveTo(sx(cur.longitude), sy(cur.latitude))
                path.lineTo(sx(pins[t.returnIdx].lon), sy(pins[t.returnIdx].lat))
            } else {
                path.moveTo(sx(pins[t.returnIdx].lon), sy(pins[t.returnIdx].lat))
            }
            for (i in t.returnIdx - 1 downTo 0) path.lineTo(sx(pins[i].lon), sy(pins[i].lat))
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4 * dp
            paint.color = GREEN
            paint.pathEffect = DashPathEffect(floatArrayOf(14 * dp, 8 * dp), 0f)
            c.drawPath(path, paint)
            paint.pathEffect = null
        }

        // ---- pins
        paint.style = Paint.Style.FILL
        for ((i, p) in pins.withIndex()) {
            val x = sx(p.lon)
            val y = sy(p.lat)
            when {
                i == 0 -> {
                    paint.color = GREEN
                    c.drawCircle(x, y, 10 * dp, paint)
                    paint.color = BG
                    paint.textSize = 12 * dp
                    paint.textAlign = Paint.Align.CENTER
                    c.drawText("S", x, y + 4.5f * dp, paint)
                    paint.textAlign = Paint.Align.LEFT
                }
                p.manual -> {
                    paint.color = AMBER
                    c.drawCircle(x, y, 8 * dp, paint)
                }
                else -> {
                    paint.color = AUTO
                    c.drawCircle(x, y, 3.5f * dp, paint)
                }
            }
            if (t.returnMode && !t.arrived && i == t.returnIdx) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3 * dp
                paint.color = WHITE
                c.drawCircle(x, y, 14 * dp, paint)
                paint.style = Paint.Style.FILL
            }
        }

        // ---- you
        if (cur != null) {
            val x = sx(cur.longitude)
            val y = sy(cur.latitude)
            val rad = Math.toRadians(headingDeg.toDouble())
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3 * dp
            paint.color = BLUE
            c.drawLine(
                x, y,
                x + (sin(rad) * 28 * dp).toFloat(),
                y - (cos(rad) * 28 * dp).toFloat(),
                paint
            )
            paint.style = Paint.Style.FILL
            paint.color = BLUE
            c.drawCircle(x, y, 8 * dp, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * dp
            paint.color = WHITE
            c.drawCircle(x, y, 8 * dp, paint)
        }

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

        // ---- zoom hint (bottom-left)
        paint.color = RING_TXT
        paint.textSize = 12 * dp
        val hint = if (follow) "ZOOM ${zooms[zoomIdx].toInt()} m  (TAP)" else "FULL TRAIL"
        c.drawText(hint, 8 * dp, h - 8 * dp, paint)

        // ---- return arrow (top-right), rotates with phone heading
        if (t.returnMode) {
            val ax = w - 62 * dp
            val ay = 62 * dp
            paint.style = Paint.Style.FILL
            paint.color = PANEL
            c.drawCircle(ax, ay, 54 * dp, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * dp
            paint.color = GREEN
            c.drawCircle(ax, ay, 54 * dp, paint)

            if (t.arrived) {
                paint.style = Paint.Style.FILL
                paint.textSize = 20 * dp
                paint.textAlign = Paint.Align.CENTER
                c.drawText("HOME", ax, ay + 7 * dp, paint)
                paint.textAlign = Paint.Align.LEFT
            } else {
                val b = t.targetBearing()
                if (b != null) {
                    c.save()
                    c.rotate(b - headingDeg, ax, ay)
                    arrow.reset()
                    arrow.moveTo(ax, ay - 42 * dp)
                    arrow.lineTo(ax + 26 * dp, ay + 30 * dp)
                    arrow.lineTo(ax, ay + 14 * dp)
                    arrow.lineTo(ax - 26 * dp, ay + 30 * dp)
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
