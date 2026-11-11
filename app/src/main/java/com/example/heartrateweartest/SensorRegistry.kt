package com.example.heartrateweartest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build

/**
 * Capability catalog describes paths, not independent physical chips.
 * Samsung Health Sensor SDK is intentionally not loaded in the v1 app.
 */
class SensorRegistry(context: Context) {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val activityRecognitionGranted =
        Build.VERSION.SDK_INT < 29 || context.checkSelfPermission(
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED

    data class Entry(
        val name: String,
        val provider: String,
        val available: Boolean,
        val measurement: String,
        val mode: String
    )

    fun basicCapabilities(): List<Entry> = listOf(
        Entry("heart_rate_bpm", "health_services", true,
              "bpm", "foreground_user_started"),
        Entry("accel_m_s2", "android_sensor",
              sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
              "m/s2_xyz", "continuous"),
        Entry("off_body", "android_sensor",
              sensors.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) != null,
              "on_body_off_body", "on_change"),
        Entry("gyro_rad_s", "android_sensor",
              sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
              "rad/s_xyz", "continuous"),
        Entry("pressure_hpa", "android_sensor",
              sensors.getDefaultSensor(Sensor.TYPE_PRESSURE) != null,
              "hPa", "continuous"),
        Entry("light_lux", "android_sensor",
              sensors.getDefaultSensor(Sensor.TYPE_LIGHT) != null,
              "lx", "on_change"),
        Entry("magnetic_field_uT", "android_sensor",
              sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null,
              "uT_xyz", "continuous"),
        Entry("rotation_vector", "android_sensor",
              sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null,
              "unitless_vector", "continuous"),
        Entry("game_rotation_vector", "android_sensor",
              sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null,
              "unitless_vector", "continuous"),
        Entry("gyro_uncalibrated_rad_s", "android_sensor",
              sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED) != null,
              "rad/s_vector", "continuous"),
        Entry("linear_accel_m_s2", "android_sensor",
              sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null,
              "m/s2_xyz", "continuous"),
        Entry("gravity_m_s2", "android_sensor",
              sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) != null,
              "m/s2_xyz", "continuous"),
        Entry("geomagnetic_rotation_vector", "android_sensor",
              sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) != null,
              "unitless_vector", "continuous"),
        Entry("step_count", "android_sensor",
              activityRecognitionGranted &&
                  sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null,
              "count", if (activityRecognitionGranted) "continuous" else "permission_required"),
        Entry("samsung_ppg", "samsung_sdk", false,
              "raw_three_channel_ppg", "not_integrated"),
        Entry("samsung_ecg", "samsung_sdk", false,
              "ecg_on_demand", "not_integrated")
    )
}
