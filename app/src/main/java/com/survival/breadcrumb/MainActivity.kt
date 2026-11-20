package com.survival.breadcrumb

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity(), SensorEventListener {

    private companion object {
        const val BG = 0xFF0B0F14.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GRAY = 0xFF8A97A3.toInt()
        const val AMBER = 0xFFFFB300.toInt()
        const val GREEN = 0xFF2EE66B.toInt()
        const val RED = 0xFFFF5252.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val DARK_BTN = 0xFF263340.toInt()
        const val GREY_BTN = 0xFF455A64.toInt()
        const val GREEN_BTN = 0xFF1FA85A.toInt()
    }

    private lateinit var canvasView: BreadcrumbCanvasView
    private lateinit var tvBig1: TextView
    private lateinit var tvLabel1: TextView
    private lateinit var tvBig2: TextView
    private lateinit var tvLabel2: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvCoords: TextView
    private lateinit var btnReturn: Button
    private lateinit var btnMode: Button
    private lateinit var btnAwake: Button

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private lateinit var sm: SensorManager
    private var rot: Sensor? = null
    private var compassMag = 0f      // magnetic heading, smoothed
    private var declination = 0f
    private var lastShownHeading = -999f
    private var awake = true

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BreadcrumbTracker.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        buildUi()
        requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        BreadcrumbTracker.onChange = { refresh() }
        rot?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (hasFine() && !TrackerService.running) startTracking()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        BreadcrumbTracker.onChange = null
        sm.unregisterListener(this)
        handler.removeCallbacks(ticker)
    }

    // ------------------------------------------------------------ permissions

    private fun hasFine() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        val need = ArrayList<String>()
        if (!hasFine()) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    private fun startTracking() {
        val i = Intent(this, TrackerService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            tvStatus.text = "CANNOT START TRACKER"
        }
    }

    // ------------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(sp: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT_BOLD)
    }

    private fun btn(text: String, bg: Int, fg: Int, sp: Float, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = sp
            setTextColor(fg)
            setTypeface(Typeface.DEFAULT_BOLD)
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

    private fun row(h: Int, vararg b: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (x in b) {
            addView(x, LinearLayout.LayoutParams(0, dp(h), 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
    }

    private fun column(big: TextView, small: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(big)
        addView(small)
    }

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(10), dp(6), dp(10), dp(8))
        }

        // two big numbers
        tvBig1 = label(38f, WHITE, true)
        tvLabel1 = label(11f, GRAY)
        tvBig2 = label(38f, AMBER, true)
        tvLabel2 = label(11f, GRAY)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(column(tvBig1, tvLabel1), LinearLayout.LayoutParams(0, wrap, 1f))
        top.addView(column(tvBig2, tvLabel2), LinearLayout.LayoutParams(0, wrap, 1f))
        root.addView(top, LinearLayout.LayoutParams(match, wrap))

        tvStatus = label(12f, GRAY)
        tvStatus.setOnClickListener { requestMissingPermissions() }
        root.addView(tvStatus, LinearLayout.LayoutParams(match, wrap))

        // radar
        canvasView = BreadcrumbCanvasView(this)
        canvasView.setOnClickListener { canvasView.cycleZoom() }
        root.addView(canvasView, LinearLayout.LayoutParams(match, 0, 1f).apply {
            setMargins(0, dp(4), 0, dp(4))
        })

        tvCoords = label(15f, WHITE).apply { typeface = Typeface.MONOSPACE }
        root.addView(tvCoords, LinearLayout.LayoutParams(match, wrap))

        // buttons
        val btnPin = btn("DROP PIN", AMBER, BLACK, 20f) { dropPin() }
        btnReturn = btn("RETURN", GREEN_BTN, BLACK, 20f) { toggleReturn() }
        root.addView(row(66, btnPin, btnReturn), LinearLayout.LayoutParams(match, wrap))

        btnMode = btn("FIT", DARK_BTN, WHITE, 15f) {
            canvasView.follow = !canvasView.follow
            refresh()
        }
        val btnShare = btn("SHARE POSITION", DARK_BTN, WHITE, 15f) { sharePosition() }
        root.addView(row(52, btnMode, btnShare), LinearLayout.LayoutParams(match, wrap))

        btnAwake = btn("SCREEN: ON", DARK_BTN, WHITE, 13f) { toggleAwake() }
        val btnReset = btn("NEW TRAIL", DARK_BTN, RED, 13f) { confirmReset() }
        root.addView(row(44, btnAwake, btnReset), LinearLayout.LayoutParams(match, wrap))

        setContentView(root)
    }

    // -------------------------------------------------------------- actions

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun dropPin() {
        val t = BreadcrumbTracker
        when {
            t.returnMode -> toast("Return mode. Tap TRACK first.")
            !t.dropManual() -> toast("No GPS fix yet.")
            else -> toast("Pin dropped.")
        }
    }

    private fun toggleReturn() {
        val t = BreadcrumbTracker
        when {
            t.returnMode -> t.stopReturn()
            t.pins.isEmpty() -> toast("No trail yet.")
            else -> t.startReturn()
        }
    }

    private fun toggleAwake() {
        awake = !awake
        if (awake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnAwake.text = if (awake) "SCREEN: ON" else "SCREEN: AUTO-OFF"
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Erase trail?")
            .setMessage("All pins deleted. Cannot undo.")
            .setPositiveButton("ERASE") { _, _ -> BreadcrumbTracker.reset() }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun sharePosition() {
        val c = BreadcrumbTracker.current
        if (c == null) {
            toast("No GPS fix yet.")
            return
        }
        val lat = String.format(Locale.US, "%.6f", c.latitude)
        val lon = String.format(Locale.US, "%.6f", c.longitude)
        val text = "I need help. My position: $lat, $lon (accuracy ${c.accuracy.roundToInt()} m). " +
            "https://maps.google.com/?q=$lat,$lon"
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("position", text))
        toast("Copied.")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(send, "Send position"))
        } catch (e: Exception) {
            // clipboard copy already done
        }
    }

    // -------------------------------------------------------------- refresh

    private fun fmt(m: Float?): String = when {
        m == null -> "--"
        m < 1000f -> "${m.roundToInt()} m"
        else -> String.format(Locale.US, "%.1f km", m / 1000f)
    }

    private fun battery(): Int {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    private fun refresh() {
        val t = BreadcrumbTracker
        val cur = t.current

        if (t.returnMode) {
            if (t.arrived) {
                tvBig1.text = "HOME"
                tvLabel1.text = "ARRIVED AT START"
            } else {
                tvBig1.text = fmt(t.targetDistance())
                tvLabel1.text = "TO NEXT PIN - ${t.pinsLeft()} LEFT"
            }
            tvBig1.setTextColor(GREEN)
        } else {
            tvBig1.text = fmt(t.distFromLastPin())
            tvLabel1.text = "FROM LAST PIN"
            tvBig1.setTextColor(WHITE)
        }
        tvBig2.text = fmt(t.distFromStart())
        tvLabel2.text = "FROM START - WALKED ${fmt(t.pathDistance())}"

        val weak = hasFine() && cur != null && t.gpsLost
        val gps = when {
            !hasFine() -> "LOCATION PERMISSION NEEDED (TAP HERE)"
            cur == null -> "SEARCHING GPS..."
            weak -> "WEAK GPS - last fix +-${cur.accuracy.roundToInt()} m"
            else -> "GPS +-${cur.accuracy.roundToInt()} m"
        }
        val bat = battery()
        tvStatus.text = "$gps  |  PINS ${t.pins.size}  |  BAT ${if (bat >= 0) "$bat%" else "?"}"
        tvStatus.setTextColor(if (weak || !hasFine()) RED else GRAY)

        tvCoords.text = if (cur != null) {
            String.format(Locale.US, "%.6f, %.6f", cur.latitude, cur.longitude)
        } else "-- , --"

        // return button look
        btnReturn.text = if (t.returnMode) "TRACK" else "RETURN"
        val bg = btnReturn.background as GradientDrawable
        bg.setColor(if (t.returnMode) GREY_BTN else GREEN_BTN)
        btnReturn.setTextColor(if (t.returnMode) WHITE else BLACK)
        btnMode.text = if (canvasView.follow) "SHOW FULL TRAIL" else "FOLLOW ME"

        if (cur != null) {
            declination = GeomagneticField(
                cur.latitude.toFloat(), cur.longitude.toFloat(), cur.altitude.toFloat(),
                System.currentTimeMillis()
            ).declination
        }
        applyHeading()
        canvasView.invalidate()
    }

    // -------------------------------------------------------------- compass

    private fun norm(d: Float): Float = ((d % 360f) + 360f) % 360f

    private fun applyHeading() {
        val cur = BreadcrumbTracker.current
        val h: Float = if (rot != null) {
            norm(compassMag + declination)
        } else if (cur != null && cur.hasBearing() && cur.speed > 0.8f) {
            cur.bearing // no compass: GPS course works only while moving
        } else {
            canvasView.headingDeg
        }
        if (abs(h - lastShownHeading) > 1f) {
            lastShownHeading = h
            canvasView.headingDeg = h
            canvasView.invalidate()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rm = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rm, event.values)
        // Phone held upright (normal walking pose): heading of the back camera direction.
        val use = if (abs(rm[8]) < 0.7f) {
            val r2 = FloatArray(9)
            SensorManager.remapCoordinateSystem(rm, SensorManager.AXIS_X, SensorManager.AXIS_Z, r2)
            r2
        } else rm
        val o = FloatArray(3)
        SensorManager.getOrientation(use, o)
        val az = norm(Math.toDegrees(o[0].toDouble()).toFloat())
        val delta = ((az - compassMag + 540f) % 360f) - 180f
        compassMag = norm(compassMag + 0.2f * delta)
        applyHeading()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
