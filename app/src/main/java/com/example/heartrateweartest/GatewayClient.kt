package com.example.heartrateweartest

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground-only Watch7 LAN client.
 * Requests and their acknowledgements are tracked across App process restarts.
 * A repeated request_id never drives the vibrator twice.
 */
class GatewayClient(
    context: Context,
    private val vibrate: (String) -> Boolean,
    private val onStatus: (String) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit = {},
    private val onTelemetryResult: (Boolean) -> Unit = {},
    private val onRemoteCommand: (String, JSONObject, String) -> String = { _, _, _ -> "rejected" },
    private val onTaskProposal: (JSONObject) -> Unit = {},
    private val onTaskDecisionAck: (String, String, Boolean) -> Unit = { _, _, _ -> },
) {
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("watch7_delivery", Context.MODE_PRIVATE)
    private val features = context.getSharedPreferences("watch7_features", Context.MODE_PRIVATE)
    private val deviceState = DeviceStateRepository(context.applicationContext)
    private val lastV2At = AtomicLong(0)
    private val nextV2MetricSeq = AtomicLong()
    private val lastV2StateAt = AtomicLong(0)
    private val v2StateSeq = AtomicLong(0)
    private val session = UUID.randomUUID().toString()
    private val nextSeq = AtomicLong()
    private val sending = AtomicBoolean(false)
    private val seenRequests = LinkedHashSet(
        prefs.getStringSet("processed_request_ids", emptySet()) ?: emptySet()
    )
    private val pendingAcks = LinkedHashMap<String, String>()
    private val seenControlIds = LinkedHashSet(
        prefs.getStringSet("seen_control_ids", emptySet()) ?: emptySet()
    )
    private val pendingControlAcks = LinkedHashMap<String, String>()
    private val taskClient by lazy {
        WatchTaskClient(features, session, ::request,
            { prompt -> mainHandler.post { onTaskProposal(prompt) } },
            { id, decision, accepted ->
                mainHandler.post { onTaskDecisionAck(id, decision, accepted) }
            })
    }
    private var polling: ScheduledFuture<*>? = null

    @Volatile private var active = false
    @Volatile private var baseUrl: String = ""
    @Volatile private var key: String = ""
    @Volatile private var lastReportedLink: Boolean? = null

    private fun reportLink(online: Boolean) {
        if (lastReportedLink != online) {
            lastReportedLink = online
            onConnectionChanged(online)
        }
    }

    init {
        val persisted = prefs.getString("pending_acks", "{}").orEmpty()
        try {
            val obj = JSONObject(persisted)
            for (name in obj.keys()) {
                val state = obj.optString(name)
                if (state in setOf("executed", "rejected", "failed")) pendingAcks[name] = state
            }
        } catch (_: Exception) {
            Log.w(TAG, "Pending ACK preferences invalid")
        }
    }

    fun configure(url: String, token: String): Boolean {
        val safeUrl = url.trim().trimEnd('/')
        val host = try { URL(safeUrl) } catch (_: Exception) { return false }
        if (host.protocol !in setOf("http", "https") || host.host.isBlank() ||
            host.userInfo != null || host.path !in setOf("", "/") ||
            token.length < 16 || safeUrl.length > 200
        ) return false
        baseUrl = safeUrl
        key = token
        return true
    }

    fun isConfigured() = baseUrl.isNotBlank() && key.isNotBlank()

    fun start() {
        if (!isConfigured()) {
            onStatus("Set Gateway LAN address first")
            return
        }
        if (active) return
        active = true
        polling = io.scheduleWithFixedDelay({
            if (active) {
                try {
                    retryPendingAcks()
                    pollCommands()
                    publishDeviceStateIfDue()
                    publishSummaryIfDue()
                    retryControlAcks()
                    pollControlCommands()
                    taskClient.retry()
                    taskClient.poll()
                } catch (error: Exception) {
                    reportLink(false)
                    Log.w(TAG, "Gateway poll failed: ${error.javaClass.simpleName}")
                }
            }
        }, 0, 1200, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        active = false
        polling?.cancel(false)
        polling = null
        reportLink(false)
    }

    fun shutdown() {
        stop()
        deviceState.shutdown()
        io.shutdownNow()
    }

    private fun publishDeviceStateIfDue() {
        if (!features.getBoolean("enable_v2", false)) return
        val now = System.currentTimeMillis()
        val interval = features.getLong("v2_state_interval_ms", 5_000L).coerceIn(5_000L, 10_000L)
        if (now - lastV2StateAt.get() < interval) return
        lastV2StateAt.set(now)
        try {
            val frame = deviceState.makeFrame(
                session, v2StateSeq.getAndIncrement(),
                online = lastReportedLink == true, nowMs = now)
            val result = request("POST", "/v2/telemetry", frame)
            if (!result.optBoolean("ok")) {
                Log.w(TAG, "V2 device state rejected")
            }
        } catch (error: Exception) {
            // No device state means missing status, not a fabricated healthy watch.
            Log.w(TAG, "V2 device state unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun publishSummaryIfDue() {
        if (!features.getBoolean("enable_v2", false)) return
        val now = System.currentTimeMillis()
        if (now - lastV2At.get() < 5_000L) return
        val frame = SensorRepository.makeSummaryFrame(
            session, nextV2MetricSeq.getAndIncrement(), now
        ) ?: return
        lastV2At.set(now)
        try {
            val result = request("POST", "/v2/telemetry", frame)
            if (!result.optBoolean("ok")) {
                Log.w(TAG, "V2 summary rejected")
            }
        } catch (_: Exception) {
            // v2 environmental/motion telemetry must not stop v1 heart uploads.
        }
    }

    fun sendHeartRate(bpm: Double, motionScore: Double?) {
        if (!active || !bpm.isFinite() || bpm <= 0 || !sending.compareAndSet(false, true)) return
        val capturedAt = System.currentTimeMillis()
        val seq = nextSeq.getAndIncrement()
        io.execute {
            try {
                if (!active) return@execute
                val payload = JSONObject()
                    .put("protocol_version", 1)
                    .put("device_id", "watch7")
                    .put("source", "watch7_real")
                    .put("session_id", session)
                    .put("seq", seq)
                    .put("captured_at_ms", capturedAt)
                    .put("bpm", bpm)
                if (motionScore != null && motionScore.isFinite() && motionScore >= 0) {
                    payload.put("motion_score", motionScore)
                } else {
                    payload.put("motion_score", JSONObject.NULL)
                }
                val answer = request("POST", "/v1/telemetry", payload)
                onTelemetryResult(answer.optBoolean("ok"))
                if (answer.optBoolean("ok")) {
                    reportLink(true)
                    Log.i(TAG, "SAMPLE_SENT seq=$seq")
                    // Independent v2 summaries are sent by the scheduled collector.
                } else {
                    reportLink(false)
                    Log.w(TAG, "SAMPLE_REJECTED seq=$seq reason=${answer.optString("reason")}")
                }
            } catch (error: Exception) {
                reportLink(false)
                onTelemetryResult(false)
                Log.w(TAG, "Sample upload failed: ${error.javaClass.simpleName}")
            } finally {
                sending.set(false)
            }
        }
    }

    private fun saveSeen(): Boolean {
        while (seenRequests.size > 100) seenRequests.remove(seenRequests.first())
        // commit() instead of apply(): a reboot must not execute this ID again.
        return prefs.edit().putStringSet("processed_request_ids", seenRequests.toSet()).commit()
    }

    private fun saveAcks() {
        val obj = JSONObject()
        for ((id, state) in pendingAcks) obj.put(id, state)
        prefs.edit().putString("pending_acks", obj.toString()).commit()
    }

    private fun retryPendingAcks() {
        for ((id, state) in pendingAcks.toMap()) {
            try {
                val answer = request("POST", "/v1/ack",
                    JSONObject().put("request_id", id).put("state", state)
                        .put("session_id", session))
                val reason = answer.optString("reason")
                if (answer.optBoolean("ok") ||
                    reason in setOf("not_pending", "expired", "session_mismatch")) {
                    pendingAcks.remove(id)
                    saveAcks()
                    Log.i(TAG, "HAPTIC_ACK request=$id state=$state")
                }
            } catch (error: Exception) {
                Log.w(TAG, "Haptic ACK retry pending: ${error.javaClass.simpleName}")
                break
            }
        }
    }

    /**
     * Task-bound haptics need a fresh Gateway decision after polling. A missing
     * or incompatible endpoint is deliberately treated as unknown (fail closed)
     * rather than as approval.
     */
    private fun authorizeTaskHaptic(requestId: String): Boolean? {
        return try {
            val response = request(
                "POST", "/v2/haptic-authorize",
                JSONObject().put("request_id", requestId)
                    .put("session_id", session)
            )
            if (!response.has("allowed")) {
                reportLink(false)
                null
            } else {
                reportLink(response.optBoolean("ok"))
                if (response.optBoolean("ok")) response.optBoolean("allowed") else null
            }
        } catch (error: Exception) {
            reportLink(false)
            Log.w(TAG, "Task haptic authorization unavailable: ${error.javaClass.simpleName}")
            null
        }
    }

    /** Persist at-most-once rejection when the Gateway explicitly denies a task. */
    private fun rememberRejectedTask(requestId: String) {
        if (requestId in seenRequests) return
        seenRequests.add(requestId)
        if (!saveSeen()) {
            seenRequests.remove(requestId)
            return
        }
        pendingAcks[requestId] = "rejected"
        saveAcks()
    }

    private fun pollCommands() {
        val pollStartedMono = SystemClock.elapsedRealtime()
        val result = request("GET", "/v1/commands", null)
        val roundTripMs = SystemClock.elapsedRealtime() - pollStartedMono
        reportLink(result.optBoolean("ok"))
        val serverNowMs = result.optLong("server_now_ms", -1)
        val commands = result.optJSONArray("commands") ?: return
        for (index in 0 until commands.length()) {
            val command = commands.optJSONObject(index) ?: continue
            val requestId = command.optString("request_id")
            val pattern = command.optString("pattern")
            val expires = command.optLong("expires_at_ms")
            val taskId = command.optString("task_id")
            val commandSession = command.optString("session_id")
            if (requestId.isEmpty() || requestId in seenRequests ||
                (commandSession.isNotEmpty() && commandSession != session)) continue
            // Server's wall clock and Watch7's wall clock can differ > 3 seconds.
            // Convert the remaining server lease into local *monotonic* time.
            // Subtract full request RTT conservatively to avoid late execution.
            val remainingMs = if (command.has("ttl_remaining_ms")) {
                // This value comes from the Gateway monotonic clock and does not
                // change when either device wall clock jumps after command creation.
                val leaseMs = command.optLong("ttl_remaining_ms", -1L)
                if (leaseMs !in 1L..3000L) continue
                leaseMs - roundTripMs
            } else if (serverNowMs > 0L) {
                expires - serverNowMs - roundTripMs
            } else {
                expires - System.currentTimeMillis() // Older gateway compatibility.
            }
            if (remainingMs !in 1L..3000L) continue
            // The Gateway has already applied its delivery gate while building
            // this poll response. This Watch-side check is the final consent
            // gate before the command can reach the vibrator.
            if (taskId.isNotBlank()) {
                when (authorizeTaskHaptic(requestId)) {
                    null -> continue // offline/old Gateway: do not execute
                    false -> {
                        rememberRejectedTask(requestId)
                        continue
                    }
                    true -> Unit
                }
            }
            val deadlineMono = SystemClock.elapsedRealtime() + remainingMs
            // Commit the ID *before* vibrating: at-most-once across process crashes.
            seenRequests.add(requestId)
            if (!saveSeen()) {
                seenRequests.remove(requestId)
                Log.e(TAG, "Could not persist haptic ID; refuse to vibrate")
                continue
            }
            mainHandler.post {
                // Last device-side gate: a server-side queue decision cannot
                // override an on-Watch revocation that happened after polling.
                val hapticOptIn = features.getBoolean("enable_remote_haptics", false) &&
                    features.getBoolean("enable_v2", false) &&
                    SensorRepository.wearState() == "on_body"
                val success = active && hapticOptIn &&
                    pattern in setOf("single", "double", "triple") &&
                    SystemClock.elapsedRealtime() <= deadlineMono && vibrate(pattern)
                val state = if (success) "executed" else "rejected"
                onStatus("Remote haptic: $state")
                io.execute {
                    pendingAcks[requestId] = state
                    saveAcks()
                    retryPendingAcks()
                }
            }
        }
    }

    /** Only a real on-Watch notification tap can invoke this callback. */
    fun submitTaskResponse(taskId: String, decision: String) {
        try {
            io.execute { taskClient.submit(taskId, decision) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "Task reply lost because Watch service is stopped")
        }
    }

    /** Submit result only after the Watch applied an operation. Main-thread safe. */
    fun completeRemoteCommand(requestId: String, outcome: String) {
        if (outcome !in setOf("executed", "rejected", "failed")) return
        try {
            io.execute {
                pendingControlAcks[requestId] = outcome
                retryControlAcks()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "Service stopped before control ACK could be sent")
        }
    }

    private fun retryControlAcks() {
        for ((requestId, outcome) in pendingControlAcks.toMap()) {
            try {
                val payload = JSONObject()
                    .put("request_id", requestId)
                    .put("state", outcome)
                    .put("session_id", session)
                val response = request("POST", "/v2/ack", payload)
                if (response.optBoolean("ok") ||
                    response.optString("reason") in setOf(
                        "expired", "not_pending", "unknown_command", "session_mismatch"
                    )
                ) {
                    pendingControlAcks.remove(requestId)
                }
            } catch (_: Exception) {
                // Retry at next poll; never turn a missing ACK into executed.
                break
            }
        }
    }

    private fun pollControlCommands() {
        if (!features.getBoolean("enable_remote_control", false) ||
            !features.getBoolean("enable_v2", false)) return
        val polledAt = SystemClock.elapsedRealtime()
        val response = try {
            request("GET", "/v2/commands", null)
        } catch (_: Exception) {
            // Older gateways may not support the v2 control plane.
            return
        }
        val rtt = SystemClock.elapsedRealtime() - polledAt
        for (index in 0 until (response.optJSONArray("commands")?.length() ?: 0)) {
            val cmd = response.optJSONArray("commands")!!.optJSONObject(index) ?: continue
            val id = cmd.optString("request_id")
            val action = cmd.optString("action")
            val remainingMs = cmd.optLong("ttl_remaining_ms", 0) - rtt
            if (id.isBlank() || id in seenControlIds ||
                action !in setOf("start", "stop", "configure") ||
                remainingMs !in 1L..12_000L) continue
            // Persist dedup before execution: a crash cannot re-run a command.
            seenControlIds.add(id)
            while (seenControlIds.size > 100) seenControlIds.remove(seenControlIds.first())
            if (!prefs.edit().putStringSet("seen_control_ids", seenControlIds.toSet()).commit()) {
                seenControlIds.remove(id)
                continue
            }
            val settings = cmd.optJSONObject("settings") ?: JSONObject()
            val deadline = SystemClock.elapsedRealtime() + remainingMs
            mainHandler.post {
                val outcome = when {
                    !active || !features.getBoolean("enable_remote_control", false) ||
                        SystemClock.elapsedRealtime() > deadline -> "rejected"
                    else -> try { onRemoteCommand(action, settings, id) }
                    catch (_: Exception) { "failed" }
                }
                if (outcome in setOf("executed", "rejected", "failed")) {
                    completeRemoteCommand(id, outcome)
                }
                // "pending": Health Services async onRegistered/onRegistrationFailed
                // completes the command; no premature executed acknowledgement.
            }
        }
    }

    private fun request(method: String, route: String, body: JSONObject?): JSONObject {
        val connection = URL(baseUrl + route).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 2500
        connection.readTimeout = 2500
        connection.setRequestProperty("X-Wearable-Token", key)
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val stream = if (connection.responseCode < 400)
                connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "WearableGateway"
    }
}

