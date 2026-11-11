package com.example.heartrateweartest

import org.json.JSONObject
import org.json.JSONArray

/**
 * One authoritative in-process state for Watch7 sensor summaries.
 * Event sources write once. UI, gateway and later cards read snapshots.
 * No background collector or extra sensor subscription is created here.
 */
object SensorRepository {
    private data class Timed<T>(val value: T, val atMs: Long)

    private var active = false
    private var heart: Timed<Double>? = null
    private var accel: Timed<List<Double>>? = null
    private var motion: Timed<Double>? = null
    private var gyro: Timed<List<Double>>? = null
    private var gyroUncalibrated: Timed<List<Double>>? = null
    private var magnetic: Timed<List<Double>>? = null
    private var rotation: Timed<List<Double>>? = null
    private var gameRotation: Timed<List<Double>>? = null
    private var linearAcceleration: Timed<List<Double>>? = null
    private var gravity: Timed<List<Double>>? = null
    private var geomagneticRotation: Timed<List<Double>>? = null
    private var pressure: Timed<Double>? = null
    private var light: Timed<Double>? = null
    private var stepCount: Timed<Double>? = null
    private var worn = "unknown"
    private var wearAtMs = 0L

    @Synchronized
    fun begin() {
        active = true
        heart = null
        accel = null
        motion = null
        gyro = null
        gyroUncalibrated = null
        magnetic = null
        rotation = null
        gameRotation = null
        linearAcceleration = null
        gravity = null
        geomagneticRotation = null
        pressure = null
        light = null
        stepCount = null
        worn = "unknown"
        wearAtMs = 0
    }

    @Synchronized
    fun end() {
        active = false
        heart = null
        accel = null
        motion = null
        gyro = null
        gyroUncalibrated = null
        magnetic = null
        rotation = null
        gameRotation = null
        linearAcceleration = null
        gravity = null
        geomagneticRotation = null
        pressure = null
        light = null
        stepCount = null
        worn = "unknown"
        wearAtMs = 0
    }

    @Synchronized
    fun updateHeartRate(bpm: Double, nowMs: Long = System.currentTimeMillis()) {
        if (active && bpm.isFinite() && bpm in 25.0..240.0 && worn != "off_body") {
            heart = Timed(bpm, nowMs)
        }
    }

    @Synchronized
    fun updateAcceleration(x: Double, y: Double, z: Double,
                           nowMs: Long = System.currentTimeMillis()) {
        if (active && listOf(x, y, z).all { it.isFinite() }) {
            accel = Timed(listOf(x, y, z), nowMs)
        }
    }

    @Synchronized
    fun updateMotion(score: Double, nowMs: Long = System.currentTimeMillis()) {
        if (active && score.isFinite() && score >= 0.0) {
            motion = Timed(score, nowMs)
        }
    }

    @Synchronized
    fun updateGyro(x: Double, y: Double, z: Double,
                   nowMs: Long = System.currentTimeMillis()) {
        if (active && listOf(x, y, z).all { it.isFinite() && it in -500.0..500.0 }) {
            gyro = Timed(listOf(x, y, z), nowMs)
        }
    }

    private fun validVector(values: List<Double>, minimum: Int = 3): Boolean =
        values.size >= minimum && values.all { it.isFinite() && it in -500.0..500.0 }

    @Synchronized
    fun updateGyroUncalibrated(values: List<Double>,
                               nowMs: Long = System.currentTimeMillis()) {
        if (active && validVector(values)) gyroUncalibrated = Timed(values.toList(), nowMs)
    }

    @Synchronized
    fun updateMagneticField(x: Double, y: Double, z: Double,
                            nowMs: Long = System.currentTimeMillis()) {
        if (active && validVector(listOf(x, y, z))) {
            magnetic = Timed(listOf(x, y, z), nowMs)
        }
    }

    @Synchronized
    fun updateRotationVector(values: List<Double>,
                             nowMs: Long = System.currentTimeMillis()) {
        if (active && validVector(values)) rotation = Timed(values.toList(), nowMs)
    }

    @Synchronized
    fun updateGameRotationVector(values: List<Double>,
                                 nowMs: Long = System.currentTimeMillis()) {
        if (active && validVector(values)) gameRotation = Timed(values.toList(), nowMs)
    }

    @Synchronized
    fun updateLinearAcceleration(x: Double, y: Double, z: Double,
                                 nowMs: Long = System.currentTimeMillis()) {
        if (active && validVector(listOf(x, y, z))) {
            linearAcceleration = Timed(listOf(x, y, z), nowMs)
        }
    }

    @Synchronized
    fun updateGravity(x: Double, y: Double, z: Double,
                      nowMs: Long = System.currentTimeMillis()) {
        if (active && validVector(listOf(x, y, z))) {
            gravity = Timed(listOf(x, y, z), nowMs)
        }
    }

    @Synchronized
    fun updateGeomagneticRotationVector(values: List<Double>,
                                        nowMs: Long = System.currentTimeMillis()) {
        if (active && validVector(values)) {
            geomagneticRotation = Timed(values.toList(), nowMs)
        }
    }

    @Synchronized
    fun updatePressure(value: Double, nowMs: Long = System.currentTimeMillis()) {
        if (active && value.isFinite() && value in 300.0..1100.0) {
            pressure = Timed(value, nowMs)
        }
    }

    @Synchronized
    fun updateLight(value: Double, nowMs: Long = System.currentTimeMillis()) {
        if (active && value.isFinite() && value in 0.0..200000.0) {
            light = Timed(value, nowMs)
        }
    }

    @Synchronized
    fun updateStepCount(value: Double, nowMs: Long = System.currentTimeMillis()) {
        if (active && value.isFinite() && value >= 0.0) {
            stepCount = Timed(value, nowMs)
        }
    }

    @Synchronized
    fun updateWearState(state: String, nowMs: Long = System.currentTimeMillis()) {
        require(state in setOf("on_body", "off_body", "unknown"))
        if (!active) return
        worn = state
        wearAtMs = nowMs
        if (state == "off_body") heart = null
    }

    @Synchronized
    fun wearState(): String = worn

    /** Only an active, worn, recently observed sample may be advertised. */
    @Synchronized
    fun lastValidHeartAgeMs(nowMs: Long = System.currentTimeMillis()): Long? {
        if (!active || worn == "off_body") return null
        val sample = heart ?: return null
        val ageMs = nowMs - sample.atMs
        return ageMs.takeIf { it in 0..15_000L }
    }

    @Synchronized
    fun makeDeviceStateFrame(
        session: String, seq: Long, online: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): JSONObject = JSONObject()
        .put("protocol_version", 2).put("device_id", "watch7")
        .put("source", "watch7_real")
        .put("session_id", session).put("seq", seq)
        .put("captured_at_ms", nowMs).put("kind", "device_state")
        .put("wear_state", worn).put("online", online).put("collecting", active)

    /**
     * v2 is opt-in. A metric is unavailable unless observed recently;
     * no old BPM is relabeled as fresh, especially after removal.
     */
    @Synchronized
    fun makeSummaryFrame(
        session: String, seq: Long, nowMs: Long = System.currentTimeMillis()
    ): JSONObject? {
        if (!active) return null
        val metrics = JSONObject()

        fun quality(age: Long, valid: Boolean): String =
            if (valid && age in 0..15_000) "valid" else "unavailable"

        fun addNumber(name: String, sample: Timed<Double>?, unit: String,
                      provider: String, physiological: Boolean = false) {
            if (sample == null) return
            val age = (nowMs - sample.atMs).coerceAtLeast(0)
            val status = when {
                physiological && worn == "off_body" -> "off_body"
                else -> quality(age, true)
            }
            val v = if (status == "valid") sample.value else JSONObject.NULL
            metrics.put(name, JSONObject()
                .put("value", v).put("unit", unit).put("quality", status)
                .put("provider", provider).put("age_ms", age))
        }

        addNumber("heart_rate_bpm", heart, "bpm", "health_services", true)
        addNumber("motion_score", motion, "m/s2", "android_sensor")
        addNumber("pressure_hpa", pressure, "hPa", "android_sensor")
        addNumber("light_lux", light, "lx", "android_sensor")
        addNumber("step_count", stepCount, "count", "android_sensor")

        fun addVector(name: String, sample: Timed<List<Double>>?, unit: String) {
            if (sample == null) return
            val age = (nowMs - sample.atMs).coerceAtLeast(0)
            val valid = age <= 15_000
            metrics.put(name, JSONObject()
                .put("value", if (valid) JSONArray(sample.value) else JSONObject.NULL)
                .put("unit", unit)
                .put("quality", if (valid) "valid" else "unavailable")
                .put("provider", "android_sensor").put("age_ms", age))
        }

        accel?.let { sample ->
            val age = (nowMs - sample.atMs).coerceAtLeast(0)
            val valid = age <= 15_000
            metrics.put("accel_m_s2", JSONObject()
                .put("value", if (valid) JSONArray(sample.value) else JSONObject.NULL)
                .put("unit", "m/s2")
                .put("quality", if (valid) "valid" else "unavailable")
                .put("provider", "android_sensor").put("age_ms", age))
        }
        gyro?.let { sample ->
            val age = (nowMs - sample.atMs).coerceAtLeast(0)
            val valid = age <= 15_000
            metrics.put("gyro_rad_s", JSONObject()
                .put("value", if (valid) JSONArray(sample.value) else JSONObject.NULL)
                .put("unit", "rad/s")
                .put("quality", if (valid) "valid" else "unavailable")
                .put("provider", "android_sensor").put("age_ms", age))
        }
        addVector("gyro_uncalibrated_rad_s", gyroUncalibrated, "rad/s")
        addVector("magnetic_field_uT", magnetic, "uT")
        addVector("rotation_vector", rotation, "unitless")
        addVector("game_rotation_vector", gameRotation, "unitless")
        addVector("linear_accel_m_s2", linearAcceleration, "m/s2")
        addVector("gravity_m_s2", gravity, "m/s2")
        addVector("geomagnetic_rotation_vector", geomagneticRotation, "unitless")
        if (metrics.length() == 0) return null
        return JSONObject()
            .put("protocol_version", 2).put("device_id", "watch7")
            .put("source", "watch7_real")
            .put("session_id", session).put("seq", seq)
            .put("captured_at_ms", nowMs).put("kind", "summary")
            .put("wear_state", worn).put("metrics", metrics)
    }
}
