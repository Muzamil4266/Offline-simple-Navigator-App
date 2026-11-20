package com.survival.breadcrumb

import android.content.Context
import android.location.Location
import android.os.SystemClock
import java.io.File
import java.io.IOException

data class Pin(val lat: Double, val lon: Double, val manual: Boolean, val time: Long)

/**
 * Pure logic + shared state. Lives in the process, used by TrackerService (writes)
 * and MainActivity (reads). Everything runs on the main thread.
 */
object BreadcrumbTracker {
    const val AUTO_PIN_M = 30f
    private const val GOOD_ACC = 18f          // normal accuracy gate (m)
    private const val WEAK_ACC = 35f          // relaxed gate after 30 s without good fix
    private const val WEAK_AFTER_MS = 30_000L
    private const val REACH_M = 10f           // pin counts as reached inside this radius
    private const val MAX_PINS = 4000

    // ---- stationary detection
    private const val MOVE_HOLD_MS = 8_000L   // steps this recent = user walking
    private const val FAST_SPEED = 3f         // m/s: vehicle / running, trust GPS speed
    private const val FAST_ACC = 12f
    private const val MIN_MOVE_M = 8f         // no step sensor: must leave this circle
    private const val JUMP_M = 40f            // step sensor on, but GPS far away (bike, car)
    private const val JUMP_ACC = 10f
    private const val STILL_STEP_MS = 20_000L // with step sensor: slow GPS sooner
    private const val STILL_GPS_MS = 60_000L  // without step sensor

    val pins = ArrayList<Pin>()

    /** Held position. Only moves when user really moves. */
    var current: Location? = null
        private set
    /** Latest raw GPS accuracy (m), -1 = none. */
    var accuracy = -1f
        private set
    var returnMode = false
        private set
    var returnIdx = -1
        private set
    var arrived = false
        private set
    var lastMoveTime = SystemClock.elapsedRealtime()
        private set

    /** Set by TrackerService when step sensor is registered and permitted. */
    @Volatile
    var stepActive = false

    /** UI hook. Set in onResume, cleared in onPause. */
    var onChange: (() -> Unit)? = null

    private var file: File? = null
    private var startedAt = SystemClock.elapsedRealtime()
    private var lastAcceptedAt = 0L
    private var lastStepTime = 0L
    private val out = FloatArray(1)
    private val tmp = Location("target")

    val gpsLost: Boolean
        get() = SystemClock.elapsedRealtime() - maxOf(lastAcceptedAt, startedAt) > WEAK_AFTER_MS

    // ---------------------------------------------------------------- setup

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.applicationContext.filesDir, "trail.csv")
        startedAt = SystemClock.elapsedRealtime()
        load()
    }

    // ----------------------------------------------------------- step input

    /** Called by service on every step event. */
    fun onSteps() {
        val now = SystemClock.elapsedRealtime()
        lastStepTime = now
        lastMoveTime = now
    }

    /** True when no movement for a while. Service uses it to slow GPS. */
    fun isStill(): Boolean {
        val limit = if (stepActive) STILL_STEP_MS else STILL_GPS_MS
        return SystemClock.elapsedRealtime() - lastMoveTime > limit
    }

    private fun isMoving(loc: Location, cur: Location, now: Long): Boolean {
        val d = loc.distanceTo(cur)
        // fast: GPS speed trusted (vehicle, bike)
        if (loc.hasSpeed() && loc.speed > FAST_SPEED && loc.accuracy <= FAST_ACC && d > 3f) return true
        return if (stepActive) {
            val walking = lastStepTime != 0L && now - lastStepTime < MOVE_HOLD_MS
            // walking AND GPS agrees we left the spot (pacing in a room does not count)
            (walking && d > maxOf(3f, loc.accuracy * 0.6f)) ||
                (d > JUMP_M && loc.accuracy <= JUMP_ACC)
        } else {
            d > maxOf(MIN_MOVE_M, loc.accuracy)
        }
    }

    // ------------------------------------------------------------ GPS input

    fun onLocation(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        val sinceGood = now - maxOf(lastAcceptedAt, startedAt)
        val limit = if (sinceGood < WEAK_AFTER_MS) GOOD_ACC else WEAK_ACC
        if (!loc.hasAccuracy() || loc.accuracy > limit) return

        lastAcceptedAt = now
        accuracy = loc.accuracy

        val cur = current
        val moving = cur == null || pins.isEmpty() || isMoving(loc, cur, now)
        if (!moving) {
            // At rest: hold position. GPS drift ignored. No distance change, no pins.
            onChange?.invoke()
            return
        }

        lastMoveTime = now
        current = loc

        if (pins.isEmpty()) {
            addPin(loc, false) // START
        } else if (!returnMode) {
            val last = pins.last()
            val d = dist(loc.latitude, loc.longitude, last.lat, last.lon)
            if (d >= AUTO_PIN_M && d > loc.accuracy) addPin(loc, false)
        } else {
            updateReturn(loc)
        }
        onChange?.invoke()
    }

    // ------------------------------------------------------------- actions

    fun dropManual(): Boolean {
        val c = current ?: return false
        if (returnMode) return false
        addPin(c, true)
        onChange?.invoke()
        return true
    }

    fun startReturn() {
        if (pins.isEmpty()) return
        returnMode = true
        arrived = false
        returnIdx = pins.lastIndex
        current?.let { updateReturn(it) }
        onChange?.invoke()
    }

    fun stopReturn() {
        returnMode = false
        arrived = false
        onChange?.invoke()
    }

    fun reset() {
        pins.clear()
        returnMode = false
        arrived = false
        returnIdx = -1
        try {
            file?.delete()
        } catch (e: SecurityException) {
            // ignore
        }
        onChange?.invoke()
    }

    // ------------------------------------------------------------- queries

    fun distFromLastPin(): Float? {
        val c = current ?: return null
        val p = pins.lastOrNull() ?: return null
        return dist(c.latitude, c.longitude, p.lat, p.lon)
    }

    fun distFromStart(): Float? {
        val c = current ?: return null
        val p = pins.firstOrNull() ?: return null
        return dist(c.latitude, c.longitude, p.lat, p.lon)
    }

    /** Total walked length along pins (+ live leg). */
    fun pathDistance(): Float {
        var sum = 0f
        for (i in 1 until pins.size) {
            sum += dist(pins[i - 1].lat, pins[i - 1].lon, pins[i].lat, pins[i].lon)
        }
        val c = current
        val last = pins.lastOrNull()
        if (c != null && last != null) sum += dist(c.latitude, c.longitude, last.lat, last.lon)
        return sum
    }

    fun target(): Pin? =
        if (returnMode && !arrived && returnIdx in pins.indices) pins[returnIdx] else null

    fun pinsLeft(): Int = if (returnMode && !arrived) returnIdx + 1 else 0

    fun targetDistance(): Float? {
        val c = current ?: return null
        val t = target() ?: return null
        return dist(c.latitude, c.longitude, t.lat, t.lon)
    }

    /** Bearing to next return pin, degrees clockwise from true north. */
    fun targetBearing(): Float? {
        val c = current ?: return null
        val t = target() ?: return null
        tmp.latitude = t.lat
        tmp.longitude = t.lon
        return c.bearingTo(tmp)
    }

    // ------------------------------------------------------------ internals

    private fun updateReturn(loc: Location) {
        if (arrived) return
        val reach = maxOf(REACH_M, loc.accuracy)
        while (returnIdx >= 0) {
            val p = pins[returnIdx]
            val d = dist(loc.latitude, loc.longitude, p.lat, p.lon)
            if (d <= reach) {
                returnIdx--
                continue
            }
            // Cut corner: already closer to the pin before this one -> skip this one.
            if (returnIdx > 0) {
                val prev = pins[returnIdx - 1]
                if (dist(loc.latitude, loc.longitude, prev.lat, prev.lon) < d) {
                    returnIdx--
                    continue
                }
            }
            break
        }
        if (returnIdx < 0) {
            arrived = true
            returnIdx = 0
        }
    }

    private fun addPin(loc: Location, manual: Boolean) {
        val p = Pin(loc.latitude, loc.longitude, manual, System.currentTimeMillis())
        pins.add(p)
        try {
            file?.appendText(line(p))
        } catch (e: IOException) {
            // storage trouble: keep tracking in memory
        }
        if (pins.size > MAX_PINS) thin()
    }

    /** Memory cap: drop every second auto pin. Keeps START, last pin, manual pins. */
    private fun thin() {
        val keep = ArrayList<Pin>()
        var skip = false
        for ((i, p) in pins.withIndex()) {
            if (i == 0 || i == pins.lastIndex || p.manual) {
                keep.add(p)
            } else {
                if (!skip) keep.add(p)
                skip = !skip
            }
        }
        pins.clear()
        pins.addAll(keep)
        if (returnIdx > pins.lastIndex) returnIdx = pins.lastIndex
        try {
            file?.writeText(pins.joinToString("") { line(it) })
        } catch (e: IOException) {
            // ignore
        }
    }

    private fun line(p: Pin) = "${p.lat},${p.lon},${if (p.manual) 1 else 0},${p.time}\n"

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            f.forEachLine { l ->
                val s = l.split(',')
                if (s.size >= 4) {
                    try {
                        pins.add(Pin(s[0].toDouble(), s[1].toDouble(), s[2] == "1", s[3].toLong()))
                    } catch (e: NumberFormatException) {
                        // skip bad line
                    }
                }
            }
        } catch (e: IOException) {
            // ignore
        }
    }

    private fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0]
    }
}
