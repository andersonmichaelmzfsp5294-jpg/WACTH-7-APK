package com.example.heartrateweartest

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Consent transport, not an authorization authority. Watch notification taps
 * are forwarded to the authenticated Gateway and remain unapproved until ACK.
 * Keep the queue bounded; network failure must NOT imply approval.
 */
class WatchTaskClient(
    private val features: SharedPreferences,
    private val sessionId: String,
    private val request: (String, String, JSONObject?) -> JSONObject,
    private val onProposal: (JSONObject) -> Unit,
    private val onDecisionAck: (String, String, Boolean) -> Unit
) {
    private val displayed = LinkedHashSet<String>()
    private val pending = LinkedHashMap<String, String>()

    fun submit(taskId: String, decision: String) {
        if (!taskId.matches(Regex("[0-9a-f-]{36}")) ||
            decision !in setOf("approve", "reject", "revoke") ||
            pending.size >= 16 && taskId !in pending) return
        pending[taskId] = decision
        retry()
    }

    fun retry() {
        for ((id, decision) in pending.toMap()) {
            try {
                val response = request("POST", "/v2/task-response",
                    JSONObject().put("task_id", id).put("decision", decision)
                        .put("session_id", sessionId))
                val accepted = response.optBoolean("ok")
                val terminal = accepted || response.optString("reason") in setOf(
                    "unknown_task", "session_mismatch", "no_pending_consent"
                )
                if (terminal) {
                    pending.remove(id)
                    onDecisionAck(id, decision, accepted)
                }
            } catch (_: Exception) {
                break // Retry later; never silently assume approved.
            }
        }
    }

    fun poll() {
        if (!features.getBoolean("enable_agent_prompts", false) ||
            !features.getBoolean("enable_v2", false)) return
        val response = try { request("GET", "/v2/task-prompts", null) }
        catch (_: Exception) { return }
        val tasks = response.optJSONArray("prompts") ?: return
        for (index in 0 until tasks.length()) {
            val task = tasks.optJSONObject(index) ?: continue
            val id = task.optString("task_id")
            if (id in displayed || id in pending ||
                task.optLong("ttl_remaining_ms", 0L) !in 1L..120_000L) continue
            displayed.add(id)
            while (displayed.size > 48) displayed.remove(displayed.first())
            onProposal(task)
        }
    }
}
