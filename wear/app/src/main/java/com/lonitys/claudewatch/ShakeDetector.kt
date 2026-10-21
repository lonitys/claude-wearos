package com.lonitys.claudewatch

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Detects a deliberate shake of the wrist: [requiredPeaks] strong movements,
 * alternating in direction, within [windowMs]. Walking or lifting the arm does
 * not reach the threshold often enough to trigger it.
 */
class ShakeDetector(
    private val sensorManager: SensorManager,
    private val onShake: () -> Unit,
    private val threshold: Float = 13f, // m/s² of linear acceleration
    private val requiredPeaks: Int = 4,
    private val windowMs: Long = 900,
    private val cooldownMs: Long = 1500,
) : SensorEventListener {
    private val gravity = FloatArray(3)
    private val peaks = ArrayDeque<Long>()
    private var lastSign = 0
    private var lastShake = 0L
    private var linear = false

    fun start(): Boolean {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.also { linear = true }
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { linear = false }
            ?: return false
        return sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        peaks.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = FloatArray(3)
        if (linear) {
            event.values.copyInto(v, endIndex = 3)
        } else {
            // Remove gravity with a simple low-pass filter.
            for (i in 0..2) {
                gravity[i] = 0.8f * gravity[i] + 0.2f * event.values[i]
                v[i] = event.values[i] - gravity[i]
            }
        }
        val magnitude = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (magnitude < threshold) return

        // Direction of the dominant axis; a shake goes back and forth.
        val axis = (0..2).maxBy { kotlin.math.abs(v[it]) }
        val sign = if (v[axis] > 0) 1 else -1
        if (sign == lastSign) return
        lastSign = sign

        val now = event.timestamp / 1_000_000
        peaks.addLast(now)
        while (peaks.isNotEmpty() && now - peaks.first() > windowMs) peaks.removeFirst()
        if (peaks.size >= requiredPeaks && now - lastShake > cooldownMs) {
            lastShake = now
            peaks.clear()
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
