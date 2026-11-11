package com.example.heartrateweartest

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Very simple motion proxy for an interview demo. NOT a medical or
 * validated human-activity classifier.
 */
class MotionReader(context: Context,
                   private val onSample: (Double, Double, Double) -> Unit = { _, _, _ -> },
                   private val onMotion: (String, Double) -> Unit) :
    SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var scoreSum = 0.0
    private var sampleCount = 0

    fun start(): Boolean {
        scoreSum = 0.0
        sampleCount = 0
        return accelerometer?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        } ?: false
    }

    fun stop() {
        manager.unregisterListener(this)
        scoreSum = 0.0
        sampleCount = 0
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val (x, y, z) = event.values.take(3).map { it.toDouble() }
        onSample(x, y, z)
        val magnitude = sqrt(x * x + y * y + z * z)
        scoreSum += abs(magnitude - 9.81)
        sampleCount += 1
        if (sampleCount >= 12) {
            val score = scoreSum / sampleCount
            onMotion(if (score > 0.8) "moving" else "still", score)
            sampleCount = 0
            scoreSum = 0.0
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
