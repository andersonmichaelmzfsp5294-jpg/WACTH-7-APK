package com.example.heartrateweartest

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONObject

/**
 * Visible Watch-side consent. The Gateway only proposes; users approve with
 * their Watch notification action. No notification == no permission granted.
 */
class TaskPromptNotifier(private val context: Context) {
    companion object {
        private const val CHANNEL = "phanthy_watch_task_consent"
        private const val ID = 8276
    }

    private val notifications =
        context.getSystemService(NotificationManager::class.java)

    init {
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL, "Agent 监测任务授权", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableVibration(false)
            setSound(null, null)
            description = "需要手表用户主动允许才能运行的限时监测任务"
        })
    }

    private fun action(taskId: String, decision: String): PendingIntent {
        val intent = Intent(context, HealthMonitorService::class.java).apply {
            this.action = HealthMonitorService.ACTION_TASK_DECISION
            data = Uri.parse("phanthy-watch://consent/" + Uri.encode(taskId) + "/" + decision)
            putExtra("task_id", taskId)
            putExtra("decision", decision)
        }
        val requestCode = taskId.hashCode() xor decision.hashCode()
        return PendingIntent.getService(context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(proposal: JSONObject): Boolean {
        val id = proposal.optString("task_id")
        val ttl = proposal.optLong("ttl_remaining_ms", 0L)
        if (!id.matches(Regex("[0-9a-f-]{36}")) || ttl !in 1L..120_000L ||
            Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) return false
        val title = proposal.optString("title").take(60)
        val duration = proposal.optInt("duration_seconds", 0)
        val interval = proposal.optInt("min_interval_seconds", 0)
        val threshold = proposal.optDouble("motion_threshold", 0.45)
        if (duration !in 30..3600 || interval !in 5..3600) return false
        val scope = "运动评分达到阈值时，经 Agent 允许最多每" +
            interval + "秒振动一次；有效" + duration + "秒。仅设备运动数据，不用于诊断。"
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Agent 请求授权：" + title)
            .setContentText(scope)
            .setStyle(Notification.BigTextStyle().bigText(scope))
            .setOngoing(false)
            .setAutoCancel(false)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_input_add, "同意", action(id, "approve")).build())
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "拒绝",
                action(id, "reject")).build())
            .build()
        notifications.notify(id, ID, notification)
        return true
    }

    fun cancel(taskId: String) {
        notifications.cancel(taskId, ID)
    }

    fun approved(taskId: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) return false
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("限时任务已获授权")
            .setContentText("你可以随时撤销此任务；手表停止连接也将阻止后续动作。")
            .setAutoCancel(false)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "立即撤销",
                action(taskId, "revoke")).build())
            .build()
        notifications.notify(taskId, ID, n)
        return true
    }
}
