package com.example.heartrateweartest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Optional public Android sensors, only while the user-started monitor runs.
 * No Samsung SDK, background scanning, or raw upload. ACTIVITY_RECOGNITION
 * only gates the optional cumulative Step Counter subscription.
 */
class AdditionalSensorReader(context: Context) : SensorEventListener {
    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val types = intArrayOf(
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_PRESSURE,
        Sensor.TYPE_LIGHT,
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GAME_ROTATION_VECTOR,
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_GRAVITY,
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
        Sensor.TYPE_STEP_COUNTER
    )
    private var enabled = false

    fun start(): Boolean {
        if (enabled) return true
        var count = 0
        types.forEach { type ->
            if (type == Sensor.TYPE_STEP_COUNTER && !hasActivityRecognitionPermission()) return@forEach
            val sensor = manager.getDefaultSensor(type)
            if (sensor != null && manager.registerListener(
                    this, sensor, SensorManager.SENSOR_DELAY_NORMAL
                )
            ) {
                count++
            }
        }
        enabled = count > 0
        return enabled
    }

    fun stop() {
        manager.unregisterListener(this)
        enabled = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!enabled) return
        val values = event.values
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> if (values.size >= 3) {
                SensorRepository.updateGyro(
                    values[0].toDouble(), values[1].toDouble(), values[2].toDouble()
                )
            }
            Sensor.TYPE_PRESSURE -> if (values.isNotEmpty()) {
                SensorRepository.updatePressure(values[0].toDouble())
            }
            Sensor.TYPE_LIGHT -> if (values.isNotEmpty()) {
                SensorRepository.updateLight(values[0].toDouble())
            }
            Sensor.TYPE_MAGNETIC_FIELD -> if (values.size >= 3) {
                SensorRepository.updateMagneticField(
                    values[0].toDouble(), values[1].toDouble(), values[2].toDouble()
                )
            }
            Sensor.TYPE_ROTATION_VECTOR ->
                SensorRepository.updateRotationVector(values.map { it.toDouble() })
            Sensor.TYPE_GAME_ROTATION_VECTOR ->
                SensorRepository.updateGameRotationVector(values.map { it.toDouble() })
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED ->
                SensorRepository.updateGyroUncalibrated(values.map { it.toDouble() })
            Sensor.TYPE_LINEAR_ACCELERATION -> if (values.size >= 3) {
                SensorRepository.updateLinearAcceleration(
                    values[0].toDouble(), values[1].toDouble(), values[2].toDouble()
                )
            }
            Sensor.TYPE_GRAVITY -> if (values.size >= 3) {
                SensorRepository.updateGravity(
                    values[0].toDouble(), values[1].toDouble(), values[2].toDouble()
                )
            }
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR ->
                SensorRepository.updateGeomagneticRotationVector(values.map { it.toDouble() })
            Sensor.TYPE_STEP_COUNTER -> if (values.isNotEmpty()) {
                SensorRepository.updateStepCount(values[0].toDouble())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun hasActivityRecognitionPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 29 ||
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
}
