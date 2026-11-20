package com.survival.breadcrumb

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper

/** Keeps GPS + trail alive when screen is off or app is in background. */
class TrackerService : Service(), LocationListener {

    companion object {
        const val ACTION_STOP = "com.survival.breadcrumb.STOP"
        private const val CHANNEL = "trail"
        private const val NOTIF_ID = 1
        private const val FAST_MS = 1000L
        private const val SLOW_MS = 5000L          // still, no step sensor
        private const val SLOW_STEP_MS = 10_000L   // still, step sensor wakes us on movement

        @Volatile
        var running = false
    }

    private lateinit var lm: LocationManager
    private lateinit var sm: SensorManager
    private var registered = false
    private var slow = false

    private var stepsRegistered = false
    private var lastCount = -1f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BreadcrumbTracker.init(this)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startInForeground()
        } catch (e: Exception) {
            // Android refused foreground start (background restart rules). Give up cleanly.
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        registerSteps() // safe to call again, e.g. after permission granted
        if (!registered) {
            registered = request(false)
            if (!registered) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            lm.removeUpdates(this)
        } catch (e: SecurityException) {
            // ignore
        }
        if (stepsRegistered) sm.unregisterListener(stepListener)
        stepsRegistered = false
        BreadcrumbTracker.stepActive = false
        registered = false
        running = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- steps

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val stepped = if (e.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                true
            } else {
                // STEP_COUNTER: running total. First event only sets baseline.
                val v = e.values[0]
                val up = lastCount >= 0f && v > lastCount
                lastCount = v
                up
            }
            if (!stepped) return
            BreadcrumbTracker.onSteps()
            if (slow) request(false) // user moves again: back to fast GPS
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Hardware step sensor: tiny battery cost. Detector first (faster), counter as fallback. */
    private fun registerSteps() {
        if (stepsRegistered) return
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) return
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            ?: return
        lastCount = -1f
        stepsRegistered = try {
            sm.registerListener(stepListener, s, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (e: SecurityException) {
            false
        }
        BreadcrumbTracker.stepActive = stepsRegistered
    }

    // ------------------------------------------------------------------ GPS

    private fun request(slowMode: Boolean): Boolean {
        val slowMs = if (BreadcrumbTracker.stepActive) SLOW_STEP_MS else SLOW_MS
        return try {
            lm.removeUpdates(this)
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                if (slowMode) slowMs else FAST_MS,
                0f,
                this,
                Looper.getMainLooper()
            )
            slow = slowMode
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    override fun onLocationChanged(location: Location) {
        BreadcrumbTracker.onLocation(location)
        // Battery saver: standing still -> poll slower.
        val still = BreadcrumbTracker.isStill()
        if (still != slow) request(still)
    }

    // Must be implemented for API < 30.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    // --------------------------------------------------------- notification

    @Suppress("DEPRECATION")
    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Trail tracking", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TrackerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "STOP",
            stop
        ).build()
        val n = b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Breadcrumb tracking")
            .setContentText("Recording trail. Tap to open.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
