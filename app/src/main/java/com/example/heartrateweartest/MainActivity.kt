package com.example.heartrateweartest

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import java.util.Locale

/** Phase 2: foreground sensor collection with optional LAN Gateway transport. */
class MainActivity : Activity() {
    private val measureClient by lazy { HealthServices.getClient(this).measureClient }
    private val haptics by lazy { HapticController(this) }
    private val gateway: GatewayClient by lazy {
        GatewayClient(this,
            vibrate = { pattern -> haptics.vibrate(pattern) },
            onStatus = { message -> runOnUiThread { showStatus(message) } },
            onConnectionChanged = { online ->
                runOnUiThread {
                    networkOnline = online
                    dashboard.updateConnection(gateway.isConfigured(), online)
                }
            })
    }
    private val dashboard: WatchDashboardUi by lazy {
        WatchDashboardUi(
            context = this,
            onToggle = { if (WatchRuntime.active) stopMonitoring() else startMonitoring() },
            onTestVibration = {
                val accepted = haptics.vibrate("double")
                showStatus(if (accepted) "已请求两次短振动" else "手表未检测到振动马达")
                Log.i(TAG, "HAPTIC_LOCAL_REQUEST accepted=$accepted")
            },
            onSettings = { showGatewaySettings() }
        )
    }
    private val motion by lazy {
        MotionReader(this) { state, score ->
            runOnUiThread {
                motionState = state
                motionScore = score
                updateDisplay()
            }
        }
    }
    private var networkOnline = false
    private var requested = false
    private var registering = false
    private var latestBpm: Double? = null
    private var motionState: String = "unknown"
    private var motionScore = 0.0

    private val callback = object : MeasureCallback {
        override fun onAvailabilityChanged(
            dataType: DeltaDataType<*, *>, availability: Availability,
        ) {
            runOnUiThread {
                showStatus(if (availability.toString().contains("OFF_BODY"))
                    "手表未佩戴，等待真实心率" else "心率状态：$availability")
            }
        }

        override fun onRegistered() {
            runOnUiThread { showStatus("Live heart-rate callback registered") }
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(TAG, "Registration failed", throwable)
            runOnUiThread {
                showStatus("Heart-rate registration failed: ${throwable.message}")
                stopMonitoring()
            }
        }

        override fun onDataReceived(data: DataPointContainer) {
            val bpm = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value
            if (bpm != null && bpm.isFinite() && bpm > 0) {
                runOnUiThread {
                    latestBpm = bpm
                    updateDisplay()
                    gateway.sendHeartRate(bpm, if (motionState == "unknown") null else motionScore)
                    Log.i(TAG, "REAL_HEART_RATE_BPM=${"%.1f".format(Locale.US, bpm)}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // First setup can be provisioned once; subsequent launches need no ADB.
        val networkPrefs = getSharedPreferences("watch7_network", MODE_PRIVATE)
        val endpoint = intent.getStringExtra("gateway_url")
            ?: networkPrefs.getString("url", "").orEmpty()
        val credential = intent.getStringExtra("gateway_auth")
            ?: networkPrefs.getString("credential", "").orEmpty()
        val accepted = gateway.configure(endpoint, credential)
        if (accepted && intent.hasExtra("gateway_url")) {
            networkPrefs.edit().putString("url", endpoint)
                .putString("credential", credential).commit()
        }
        Log.i(TAG, "GATEWAY_CONFIGURED=$accepted")
        window.statusBarColor = android.graphics.Color.rgb(7, 12, 19)
        window.navigationBarColor = android.graphics.Color.rgb(7, 12, 19)
        setContentView(dashboard.view)
        updateDisplay()
        dashboard.updateMonitoring(false)
        dashboard.updateConnection(accepted, false)
        showStatus("点击开始监测，读取真实心率")
        Log.i(TAG, "WATCH_DASHBOARD_READY")
    }

    private fun showGatewaySettings() {
        val hasSamsung = BuildConfig.HAS_SAMSUNG_SDK
        val entries = listOf("修改 Driver 地址", "更新配对密钥", "切换 v2 数据上报",
            "授权/撤销远程采集控制", "三星高级健康测量（研究版）",
            "Agent 监测任务授权通知（可关闭）", "授权计步数据",
            "授权/撤销当前监测会话的远程振动")
        // Standard flavor never ships the Samsung measurement Activity.
        val visibleEntries = if (hasSamsung) entries else entries.filterIndexed { index, _ -> index != 4 }
        AlertDialog.Builder(this)
            .setTitle("连接设置")
            .setItems(visibleEntries.toTypedArray()) { _, index ->
                val actionIndex = if (!hasSamsung && index >= 4) index + 1 else index
                when (actionIndex) {
                    0 -> editGatewayValue(editCredential = false)
                    1 -> editGatewayValue(editCredential = true)
                    2 -> {
                        val features = getSharedPreferences("watch7_features", MODE_PRIVATE)
                        val next = !features.getBoolean("enable_v2", false)
                        features.edit().putBoolean("enable_v2", next)
                            .putBoolean("enable_remote_control",
                                next && features.getBoolean("enable_remote_control", false))
                            .putBoolean("enable_agent_prompts",
                                next && features.getBoolean("enable_agent_prompts", false))
                            .putBoolean("enable_remote_haptics",
                                next && features.getBoolean("enable_remote_haptics", false)).apply()
                        showStatus(if (next) "已开启 v2 实验上报；v1 仍保留" else "已关闭 v2 与远程采集控制")
                    }
                    3 -> {
                        val features = getSharedPreferences("watch7_features", MODE_PRIVATE)
                        if (features.getBoolean("enable_remote_control", false)) {
                            features.edit().putBoolean("enable_remote_control", false).commit()
                            showStatus("已撤销电脑远程采集控制")
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("允许电脑控制采集？")
                                .setMessage("仅当你亲自启动监测服务后，电脑可远程暂停/恢复心率和运动传感器，并修改设备状态心跳间隔。不会远程开启已停止的手表服务。可随时在此撤销。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("同意授权") { _, _ ->
                                    features.edit().putBoolean("enable_v2", true)
                                        .putBoolean("enable_remote_control", true).commit()
                                    showStatus("已授权当前手表远程采集；可在连接设置中撤销")
                                }.show()
                        }
                    }
                    4 -> if (hasSamsung) startActivity(
                        Intent().setClassName(this,
                            "com.example.heartrateweartest.SamsungMeasurementActivity")
                    )
                    5 -> toggleAgentPrompts()
                    6 -> requestActivityRecognition()
                    7 -> toggleRemoteHaptics()
                }
            }
            .show()
    }

    private fun toggleRemoteHaptics() {
        val features = getSharedPreferences("watch7_features", MODE_PRIVATE)
        if (features.getBoolean("enable_remote_haptics", false)) {
            features.edit().putBoolean("enable_remote_haptics", false).commit()
            showStatus("已撤销远程振动授权；待执行请求必须被拒绝")
            return
        }
        if (!WatchRuntime.active) {
            showStatus("请先在手表亲自开始监测，再授权本次远程振动")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("授权本次监测的远程振动？")
            .setMessage("仅允许已配对的受控 Driver 请求短促的单次、两次或三次振动。"
                + "不会允许远程录音、启动健康测量或控制机器人。"
                + "每次监测重启后都必须重新授权，可随时在连接设置中撤销。")
            .setNegativeButton("拒绝", null)
            .setPositiveButton("本次会话授权") { _, _ ->
                val saved = features.edit()
                    .putBoolean("enable_v2", true)
                    .putBoolean("enable_remote_haptics", true).commit()
                showStatus(if (saved) "已授权本次会话远程短振动；随时可撤销"
                    else "授权保存失败，未启用远程振动")
            }.show()
    }

    private fun requestActivityRecognition() {
        if (Build.VERSION.SDK_INT < 29) {
            showStatus("当前系统不需要单独的计步权限")
            return
        }
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            showStatus("计步权限已授权；重新开始监测后读取累计步数")
            return
        }
        requestPermissions(
            arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), REQUEST_ACTIVITY_RECOGNITION
        )
    }

    private fun toggleAgentPrompts() {
        val features = getSharedPreferences("watch7_features", MODE_PRIVATE)
        if (features.getBoolean("enable_agent_prompts", false)) {
            features.edit().putBoolean("enable_agent_prompts", false).commit()
            showStatus("已关闭任务授权通知；现有授权将在下一次网关心跳撤销")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("允许 Agent 提出限时监测任务？")
            .setMessage("只允许接收候选任务通知。每个任务仍必须在手表上明确点“同意”；没有同意则不会获得新的动作权限，随时可以拒绝或撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("允许接收请求") { _, _ ->
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_TASK_NOTIFICATION)
                } else {
                    enableAgentPrompts()
                }
            }.show()
    }

    private fun enableAgentPrompts() {
        getSharedPreferences("watch7_features", MODE_PRIVATE).edit()
            .putBoolean("enable_v2", true).putBoolean("enable_agent_prompts", true).commit()
        showStatus("可接收 Agent 任务请求，但每个任务仍须手表端确认")
    }

    private fun editGatewayValue(editCredential: Boolean) {
        val networkPrefs = getSharedPreferences("watch7_network", MODE_PRIVATE)
        val field = EditText(this).apply {
            setSingleLine(true)
            textSize = 13f
            hint = if (editCredential) "输入设备密钥" else "http://Driver局域网IP:15751"
            inputType = if (editCredential)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            if (!editCredential) setText(networkPrefs.getString("url", ""))
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 4, 18, 4)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(if (editCredential) "配对密钥" else "电脑网关地址")
            .setView(holder)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val address = if (editCredential)
                    networkPrefs.getString("url", "").orEmpty()
                else field.text.toString().trim()
                val credential = if (editCredential)
                    field.text.toString().trim()
                else networkPrefs.getString("credential", "").orEmpty()
                if (gateway.configure(address, credential)) {
                    networkPrefs.edit().putString("url", address)
                        .putString("credential", credential).commit()
                    networkOnline = false
                    dashboard.updateConnection(true, false)
                    if (WatchRuntime.active) {
                        startService(Intent(this, HealthMonitorService::class.java).apply {
                            action = HealthMonitorService.ACTION_RELOAD
                        })
                    }
                    showStatus("设置已保存，正在验证连接")
                } else {
                    showStatus("配置无效，请重新输入")
                }
            }.show()
    }

    private fun heartRatePermission(): String =
        if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE"
        else Manifest.permission.BODY_SENSORS

    private fun startMonitoring() {
        if (WatchRuntime.active) return
        if (checkSelfPermission(heartRatePermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(heartRatePermission()), REQUEST_HEART_RATE)
            return
        }
        requested = true
        showStatus("持续监测服务启动中…")
        try {
            startForegroundService(Intent(this, HealthMonitorService::class.java))
            dashboard.updateMonitoring(true)
        } catch (error: Exception) {
            requested = false
            dashboard.updateMonitoring(false)
            showStatus("服务启动失败：" + error.javaClass.simpleName)
            Log.e(TAG, "Foreground service launch failed", error)
        }
    }

    private fun stopMonitoring() {
        requested = false
        stopService(Intent(this, HealthMonitorService::class.java))
        dashboard.updateMonitoring(false)
        latestBpm = null
        dashboard.updateHeartRate(null)
        showStatus("监测已停止")
    }

    private fun updateDisplay() {
        dashboard.updateHeartRate(latestBpm)
        dashboard.updateMotion(motionState, motionScore)
    }

    private fun showStatus(text: String) {
        dashboard.showStatus(text)
    }

    private val liveUiHandler = Handler(Looper.getMainLooper())
    private val liveUiRefresh = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            requested = WatchRuntime.active
            latestBpm = if (requested && now - WatchRuntime.lastHeartAt in 0..15000)
                WatchRuntime.lastBpm else null
            motionState = WatchRuntime.motionState
            motionScore = WatchRuntime.motionScore
            updateDisplay()
            dashboard.updateMonitoring(requested)
            dashboard.updateConnection(gateway.isConfigured(), WatchRuntime.online)
            val uploaded = WatchRuntime.lastUploadedAt
            showStatus(when {
                !requested -> WatchRuntime.message
                !WatchRuntime.collecting -> "采集已暂停；远程控制通道仍运行"
                uploaded > 0 && now - uploaded in 0..15000 -> "真实心率上传正常"
                WatchRuntime.lastUploadError.isNotBlank() -> WatchRuntime.lastUploadError
                WatchRuntime.online -> "网关已连接，等待心率上传"
                else -> "网关尚未连接，请检查 Wi-Fi"
            })
            liveUiHandler.postDelayed(this, 1200)
        }
    }
    override fun onResume() {
        super.onResume()
        liveUiHandler.removeCallbacks(liveUiRefresh)
        liveUiHandler.post(liveUiRefresh)
    }
    override fun onPause() {
        liveUiHandler.removeCallbacks(liveUiRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        gateway.shutdown()
        haptics.cancel()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_HEART_RATE = 1001
        const val REQUEST_TASK_NOTIFICATION = 1002
        const val REQUEST_ACTIVITY_RECOGNITION = 1003
        const val TAG = "WearableAgent"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_TASK_NOTIFICATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                enableAgentPrompts()
            else showStatus("通知权限未获批准，任务请求保持关闭")
        }
        if (requestCode == REQUEST_HEART_RATE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMonitoring()
            } else {
                showStatus("Heart-rate permission denied")
            }
        }
        if (requestCode == REQUEST_ACTIVITY_RECOGNITION) {
            showStatus(if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) "计步权限已授权；重新开始监测后读取累计步数"
            else "计步权限未获批准，其他传感器仍可继续使用")
        }
    }
}
