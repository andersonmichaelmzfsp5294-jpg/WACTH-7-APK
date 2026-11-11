package com.example.heartrateweartest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject

/**
 * One read-only device heartbeat owner. Values are obtained from the system
 * at send time, never inferred from an earlier heart-rate callback.
 *
 * No new sensor subscriptions, foreground services or runtime permissions.
 * Missing system information is omitted rather than fabricated.
 */
class DeviceStateRepository(private val context: Context) {
    private val ttsCapability by lazy { WatchTtsCapability(context) }

    fun makeFrame(
        session: String, seq: Long, online: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): JSONObject {
        val frame = SensorRepository.makeDeviceStateFrame(
            session, seq, online = online, nowMs = nowMs
        )
        val battery = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        }
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0 && level <= scale) {
                frame.put("battery_pct", (level * 100.0 / scale).coerceIn(0.0, 100.0))
            }
            val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (status in listOf(
                    BatteryManager.BATTERY_STATUS_CHARGING,
                    BatteryManager.BATTERY_STATUS_FULL,
                    BatteryManager.BATTERY_STATUS_DISCHARGING,
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING
                )
            ) {
                frame.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL)
            }
        }

        val permission = if (Build.VERSION.SDK_INT >= 36)
            "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
        frame.put("heart_permission",
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
                "granted" else "denied")
        if (Build.VERSION.SDK_INT >= 29) {
            frame.put("activity_recognition_permission",
                if (context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
                    PackageManager.PERMISSION_GRANTED
                ) "granted" else "denied")
        }
        frame.put("agent_prompts_enabled", context.getSharedPreferences(
            "watch7_features", Context.MODE_PRIVATE).getBoolean("enable_agent_prompts", false))
        frame.put("remote_control_enabled", context.getSharedPreferences(
            "watch7_features", Context.MODE_PRIVATE).getBoolean("enable_remote_control", false))
        // Explicit, separate opt-in; it must not survive a foreground
        // monitoring session restart or be inferred from Agent task prompts.
        frame.put("haptic_enabled", WatchRuntime.active && context.getSharedPreferences(
            "watch7_features", Context.MODE_PRIVATE).getBoolean("enable_remote_haptics", false))
        val tts = ttsCapability.snapshot()
        frame.put("tts_status", tts.optString("status", "engine_initializing"))
        // Capability enumeration is not offline proof; keep this false until
        // a user-approved, network-disabled device check is recorded.
        frame.put("tts_offline_supported", false)
        frame.put("api_level", Build.VERSION.SDK_INT)
        try {
            val version = context.packageManager.getPackageInfo(context.packageName, 0)
                .versionName.orEmpty()
            if (version.matches(Regex("[A-Za-z0-9._+-]{1,64}"))) {
                frame.put("app_version", version)
            }
        } catch (_: Exception) {
            // No version metadata is better than a fake version.
        }
        SensorRepository.lastValidHeartAgeMs(nowMs)?.let {
            frame.put("last_valid_heart_age_ms", it)
        }
        return frame
    }

    fun shutdown() {
        ttsCapability.shutdown()
    }
}
