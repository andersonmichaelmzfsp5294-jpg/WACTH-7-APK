package com.example.heartrateweartest

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import org.json.JSONObject

/**
 * User-started foreground service for genuine Wear OS heart rate measurements.
 * Does not claim all-day clinical monitoring or survive user force-stop.
 */
class HealthMonitorService : Service() {
    private val measure by lazy { HealthServices.getClient(this).measureClient }
    private val haptics by lazy { HapticController(this) }
    private val motion by lazy {
        MotionReader(this, onSample = { x, y, z ->
            SensorRepository.updateAcceleration(x, y, z)
        }) { state, score ->
            SensorRepository.updateMotion(score)
            WatchRuntime.motionState = state
            WatchRuntime.motionScore = score
        }
    }
    private val offBody by lazy {
        OffBodyReader(this) { state -> SensorRepository.updateWearState(state) }
    }
    private val extras by lazy { AdditionalSensorReader(this) }
    private val promptNotifier by lazy { TaskPromptNotifier(this) }
    private val taskNotifications = LinkedHashSet<String>()
    private val gateway by lazy {
        GatewayClient(this,
            vibrate = { pattern -> haptics.vibrate(pattern) },
            onStatus = { WatchRuntime.message = it },
            onRemoteCommand = { action, settings, requestId ->
                applyRemoteCommand(action, settings, requestId)
            },
            onTaskProposal = { proposal ->
                if (WatchRuntime.active &&
                    getSharedPreferences("watch7_features", MODE_PRIVATE)
                        .getBoolean("enable_agent_prompts", false) &&
                    promptNotifier.show(proposal)
                ) {
                    taskNotifications.add(proposal.optString("task_id"))
                }
            },
            onTaskDecisionAck = { taskId, decision, accepted ->
                if (accepted && decision == "approve") {
                    promptNotifier.approved(taskId)
                } else {
                    promptNotifier.cancel(taskId)
                    taskNotifications.remove(taskId)
                }
                WatchRuntime.message = if (accepted)
                    "任务回应已确认：" + decision
                else "任务回应未获网关确认"
            },
            onConnectionChanged = { WatchRuntime.online = it },
            onTelemetryResult = { success ->
                if (success) {
                    WatchRuntime.lastUploadedAt = System.currentTimeMillis()
                    WatchRuntime.lastUploadError = ""
                } else {
                    WatchRuntime.lastUploadError = "上传失败，检查连接和授权"
                }
            }
        )
    }
    private var registered = false
    private var starting = false
    private var pendingRemoteStart: String? = null
    private val scheduler by lazy {
        SamplingScheduler().apply {
            register(CallbackSensorProvider(
                id = "accelerometer", onStart = { motion.start() },
                onStop = { motion.stop() }
            ))
            register(CallbackSensorProvider(
                id = "off_body", mode = "on_change",
                onStart = { offBody.start() }, onStop = { offBody.stop() }
            ))
            register(CallbackSensorProvider(
                id = "auxiliary_sensors", onStart = { extras.start() },
                onStop = { extras.stop() }
            ))
            register(CallbackSensorProvider(
                id = "heart_rate", onStart = {
                    // Health Services confirms registration asynchronously in callback.
                    registered = true
                    measure.registerMeasureCallback(DataType.HEART_RATE_BPM, mainExecutor, callback)
                    true
                }, onStop = {
                    if (registered) {
                        registered = false
                        measure.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
                    }
                }
            ))
        }
    }
    private var wifiLock: WifiManager.WifiLock? = null

    private val callback = object : MeasureCallback {
        override fun onAvailabilityChanged(
            dataType: DeltaDataType<*, *>, availability: Availability
        ) {
            if (availability.toString().contains("OFF_BODY")) {
                SensorRepository.updateWearState("off_body")
                WatchRuntime.message = "未佩戴，等待真实心率"
                WatchRuntime.lastBpm = null
            }
        }
        override fun onRegistered() {
            WatchRuntime.message = "真实心率采集已启动"
            pendingRemoteStart?.let { gateway.completeRemoteCommand(it, "executed") }
            pendingRemoteStart = null
        }
        override fun onRegistrationFailed(throwable: Throwable) {
            WatchRuntime.message = "传感器注册失败：" + throwable.javaClass.simpleName
            Log.e(TAG, "Health registration failed", throwable)
            pendingRemoteStart?.let { gateway.completeRemoteCommand(it, "failed") }
            pendingRemoteStart = null
            stopSelf()
        }
        override fun onDataReceived(data: DataPointContainer) {
            val bpm = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value
            if (WatchRuntime.active && WatchRuntime.collecting &&
                SensorRepository.wearState() != "off_body" &&
                bpm != null && bpm.isFinite() && bpm > 0) {
                SensorRepository.updateHeartRate(bpm)
                WatchRuntime.lastBpm = bpm
                WatchRuntime.lastHeartAt = System.currentTimeMillis()
                gateway.sendHeartRate(
                    bpm,
                    if (WatchRuntime.motionState == "unknown") null
                    else WatchRuntime.motionScore
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TASK_DECISION) {
            val taskId = intent.getStringExtra("task_id").orEmpty()
            val decision = intent.getStringExtra("decision").orEmpty()
            val allowed = decision in setOf("approve", "reject", "revoke")
            val consent = getSharedPreferences("watch7_features", MODE_PRIVATE)
                .getBoolean("enable_agent_prompts", false)
            if (WatchRuntime.active && taskId in taskNotifications && allowed &&
                (decision != "approve" || consent)
            ) {
                // Keep the notification visible until the Gateway confirms the
                // decision. A transient LAN failure must not look like approval
                // or make the user's pending choice disappear.
                gateway.submitTaskResponse(taskId, decision)
            }
            return if (WatchRuntime.active) START_STICKY else START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RELOAD && WatchRuntime.active) {
            val p = getSharedPreferences("watch7_network", MODE_PRIVATE)
            val accepted = gateway.configure(
                p.getString("url", "").orEmpty(),
                p.getString("credential", "").orEmpty()
            )
            WatchRuntime.online = false
            WatchRuntime.message = if (accepted) "网关地址已更新" else "网关配置无效"
            return START_STICKY
        }
        if (WatchRuntime.active || starting) return START_STICKY
        // Remote vibration consent belongs to a single user-started service
        // session. Never silently re-grant it after a process restart.
        getSharedPreferences("watch7_features", MODE_PRIVATE).edit()
            .putBoolean("enable_remote_haptics", false).commit()
        starting = true

        if (!hasHealthPermission()) {
            WatchRuntime.message = "缺少心率权限"
            starting = false
            stopSelf()
            return START_NOT_STICKY
        }
        val p = getSharedPreferences("watch7_network", MODE_PRIVATE)
        if (!gateway.configure(
                p.getString("url", "").orEmpty(),
                p.getString("credential", "").orEmpty()
            )) {
            WatchRuntime.message = "未配置设备网关地址或密钥"
            starting = false
            stopSelf()
            return START_NOT_STICKY
        }
        val channelId = "phanthy_watch_health"
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            channelId, "手表实时心率监测", NotificationManager.IMPORTANCE_LOW
        ))
        startForeground(5347, Notification.Builder(this, channelId)
            .setContentTitle("Phanthy Wearable")
            .setContentText("正在采集与上传真实心率")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build())

        // Wear OS can idle Wi-Fi with the screen off. Only hold while explicitly monitoring.
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF, "phanthy_watch7_live"
            ).apply { setReferenceCounted(false); acquire() }
        } catch (error: Exception) {
            Log.w(TAG, "Wi-Fi lock unavailable", error)
        }

        SensorRepository.begin()
        WatchRuntime.active = true
        WatchRuntime.collecting = true
        WatchRuntime.online = false
        WatchRuntime.lastBpm = null
        WatchRuntime.lastHeartAt = 0
        WatchRuntime.lastUploadedAt = 0
        gateway.start()
        // Service owns the lease; future Cards/tasks share these physical trackers.
        for (sensor in listOf("accelerometer", "off_body", "auxiliary_sensors")) {
            val result = scheduler.acquire("foreground_service", sensor)
            if (!result.ok) Log.w(TAG, "Optional sensor $sensor: ${result.reason}")
        }
        val capabilities = measure.getCapabilitiesAsync()
        capabilities.addListener({
            try {
                if (!WatchRuntime.active) return@addListener
                if (DataType.HEART_RATE_BPM !in
                    capabilities.get().supportedDataTypesMeasure) {
                    WatchRuntime.message = "此手表不支持 Health Services 实时心率"
                    stopSelf()
                } else {
                    val result = scheduler.acquire("foreground_service", "heart_rate")
                    if (!result.ok) {
                        WatchRuntime.message = "心率订阅失败：${result.reason}"
                        stopSelf()
                    }
                }
            } catch (error: Exception) {
                WatchRuntime.message = "心率采集启动失败：" + error.javaClass.simpleName
                Log.e(TAG, "Health Services unavailable", error)
                stopSelf()
            } finally {
                starting = false
            }
        }, mainExecutor)
        return START_STICKY
    }

    /**
     * Runs on the main thread while a user-started foreground service is alive.
     * "pending" delays the START ACK until Health Services onRegistered.
     * Cold start of a stopped app is intentionally impossible via this path.
     */
    private fun applyRemoteCommand(action: String, settings: JSONObject, requestId: String): String {
        val features = getSharedPreferences("watch7_features", MODE_PRIVATE)
        if (!WatchRuntime.active || !features.getBoolean("enable_remote_control", false) ||
            !features.getBoolean("enable_v2", false)
        ) return "rejected"

        return when (action) {
            "configure" -> {
                val ms = settings.optLong("device_heartbeat_interval_ms", -1L)
                if (ms !in 5_000L..10_000L) "rejected"
                else if (features.edit().putLong("v2_state_interval_ms", ms).commit()) {
                    WatchRuntime.message = "设备状态心跳间隔已更新"
                    "executed"
                } else "failed"
            }
            "stop" -> {
                pendingRemoteStart?.let { gateway.completeRemoteCommand(it, "rejected") }
                pendingRemoteStart = null
                scheduler.releaseAll("foreground_service")
                if (scheduler.snapshot().isNotEmpty()) {
                    WatchRuntime.message = "传感器释放未完成"
                    "failed"
                } else {
                    SensorRepository.end()
                    WatchRuntime.collecting = false
                    WatchRuntime.lastBpm = null
                    WatchRuntime.lastHeartAt = 0L
                    WatchRuntime.message = "采集已远程暂停；连接仍在运行"
                    "executed"
                }
            }
            "start" -> {
                when {
                    WatchRuntime.collecting -> "executed"
                    pendingRemoteStart != null -> "rejected"
                    !hasHealthPermission() -> "rejected"
                    else -> {
                        SensorRepository.begin()
                        WatchRuntime.collecting = true
                        val accel = scheduler.acquire("foreground_service", "accelerometer")
                        val offbody = scheduler.acquire("foreground_service", "off_body")
                        scheduler.acquire("foreground_service", "auxiliary_sensors")
                        if (!accel.ok) Log.w(TAG, "Remote accel unavailable: ${accel.reason}")
                        if (!offbody.ok) Log.w(TAG, "Remote off-body unavailable: ${offbody.reason}")
                        pendingRemoteStart = requestId
                        val heart = scheduler.acquire("foreground_service", "heart_rate")
                        if (!heart.ok) {
                            pendingRemoteStart = null
                            scheduler.releaseAll("foreground_service")
                            WatchRuntime.collecting = false
                            SensorRepository.end()
                            "failed"
                        } else {
                            WatchRuntime.message = "远程请求采集，等待 Health Services 确认"
                            "pending"
                        }
                    }
                }
            }
            else -> "rejected"
        }
    }

    private fun hasHealthPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 36)
            "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        getSharedPreferences("watch7_features", MODE_PRIVATE).edit()
            .putBoolean("enable_remote_haptics", false).commit()
        taskNotifications.forEach { promptNotifier.cancel(it) }
        taskNotifications.clear()
        WatchRuntime.active = false
        WatchRuntime.collecting = false
        WatchRuntime.online = false
        WatchRuntime.lastBpm = null
        WatchRuntime.lastHeartAt = 0
        scheduler.close()
        SensorRepository.end()
        gateway.shutdown()
        try { wifiLock?.let { if (it.isHeld) it.release() } }
        catch (error: Exception) { Log.w(TAG, "Wi-Fi release failed", error) }
        wifiLock = null
        haptics.cancel()
        WatchRuntime.message = "监测已暂停"
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.heartrateweartest.STOP_MONITOR"
        const val ACTION_RELOAD = "com.example.heartrateweartest.RELOAD_MONITOR"
        const val ACTION_TASK_DECISION = "com.example.heartrateweartest.TASK_CONSENT"
        private const val TAG = "WatchMonitorService"
    }
}
