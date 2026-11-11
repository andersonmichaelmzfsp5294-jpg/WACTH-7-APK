package com.example.heartrateweartest

/** Shared in-process snapshot of the explicitly started monitoring service. */
object WatchRuntime {
    @Volatile var active = false // Foreground service alive, may be remotely paused.
    @Volatile var collecting = false // Physical sensors currently subscribed.
    @Volatile var online = false
    @Volatile var lastBpm: Double? = null
    @Volatile var motionScore = 0.0
    @Volatile var motionState = "unknown"
    @Volatile var lastHeartAt = 0L
    @Volatile var lastUploadedAt = 0L
    @Volatile var lastUploadError = ""
    @Volatile var message = "等待启动"
}
