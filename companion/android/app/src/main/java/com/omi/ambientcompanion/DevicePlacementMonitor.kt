package com.omi.ambientcompanion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import kotlin.math.abs
import kotlin.math.sqrt

object DevicePlacementMonitor : SensorEventListener {
    private const val STABLE_DELTA_THRESHOLD = 0.35f
    private const val STABLE_REQUIRED_MS = 3_000L
    private const val FACE_DOWN_Z_THRESHOLD = -7.0f

    private var manager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var offBodySensor: Sensor? = null
    private var stableSinceMs: Long = 0
    private var lastX: Float? = null
    private var lastY: Float? = null
    private var lastZ: Float? = null

    @Volatile var lastSampleAtMs: Long = 0
        private set
    @Volatile var stationary: Boolean = false
        private set
    @Volatile var faceDown: Boolean = false
        private set
    @Volatile var offBodyDetected: Boolean? = null
        private set

    @Synchronized
    fun start(context: Context) {
        if (manager != null) return
        val appContext = context.applicationContext
        manager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            offBodySensor = manager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
            offBodySensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
    }

    @Synchronized
    fun stop() {
        manager?.unregisterListener(this)
        manager = null
        accelerometer = null
        offBodySensor = null
    }

    fun recordingAllowed(prefs: AppPrefs): Boolean {
        if (!prefs.deskOnlyRecordingEnabled) return true
        if (lastSampleAtMs == 0L) return true
        val deskLikely = stationary && offBodyDetected != false
        if (!deskLikely) return false
        return !prefs.faceDownDeskOnlyEnabled || faceDown
    }

    fun label(prefs: AppPrefs? = null): String {
        val gate = when {
            prefs == null || !prefs.deskOnlyRecordingEnabled -> "off"
            prefs.faceDownDeskOnlyEnabled -> "desk + face-down"
            else -> "desk"
        }
        val offBody = offBodyDetected?.let { if (it) "yes" else "no" } ?: "unknown"
        val allowed = prefs?.let { if (recordingAllowed(it)) "allowed" else "blocked" } ?: "unknown"
        return "Placement gate: $gate, $allowed | stationary=${yesNo(stationary)} face-down=${yesNo(faceDown)} off-body=$offBody"
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> updateAccelerometer(event.values)
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> offBodyDetected = event.values.firstOrNull()?.let { it > 0.5f }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateAccelerometer(values: FloatArray) {
        if (values.size < 3) return
        val now = System.currentTimeMillis()
        val x = values[0]
        val y = values[1]
        val z = values[2]
        val priorX = lastX
        val priorY = lastY
        val priorZ = lastZ
        val delta = if (priorX == null || priorY == null || priorZ == null) {
            0f
        } else {
            sqrt((x - priorX) * (x - priorX) + (y - priorY) * (y - priorY) + (z - priorZ) * (z - priorZ))
        }
        if (delta <= STABLE_DELTA_THRESHOLD) {
            if (stableSinceMs == 0L) stableSinceMs = now
        } else {
            stableSinceMs = now
            stationary = false
        }
        val magnitude = sqrt(x * x + y * y + z * z)
        stationary = stableSinceMs > 0L && now - stableSinceMs >= STABLE_REQUIRED_MS && abs(magnitude - 9.81f) < 2.5f
        faceDown = stationary && z < FACE_DOWN_Z_THRESHOLD
        lastX = x
        lastY = y
        lastZ = z
        lastSampleAtMs = now
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
}
