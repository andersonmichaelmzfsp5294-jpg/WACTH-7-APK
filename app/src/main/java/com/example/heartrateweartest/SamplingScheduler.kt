package com.example.heartrateweartest

/**
 * Physical sensor lifecycle contract. Adapters represent physical tracker
 * subscriptions; cards/tasks are callers, never independent subscriptions.
 */
interface SensorProvider {
    val id: String
    val resource: String
    val mode: String
    fun start(): Boolean
    fun stop()
}

class CallbackSensorProvider(
    override val id: String,
    override val mode: String = "continuous",
    override val resource: String = id,
    private val onStart: () -> Boolean,
    private val onStop: () -> Unit
) : SensorProvider {
    override fun start() = onStart()
    override fun stop() = onStop()
}

/**
 * One active subscription per physical resource. Multiple owners share a
 * tracker and release it only after the final owner exits.
 *
 * Open/close run synchronously. Providers with asynchronous registration must
 * report their later failure to their owner, which then releases its lease.
 * This is NOT a remote authorization or Samsung SDK policy gate.
 */
class SamplingScheduler {
    data class Result(val ok: Boolean, val reason: String = "", val opened: Boolean = false)
    data class TrackerState(val id: String, val mode: String, val owners: Int)

    private data class Active(
        val provider: SensorProvider,
        val owners: MutableSet<String>
    )
    private val providers = LinkedHashMap<String, SensorProvider>()
    private val active = LinkedHashMap<String, Active>()
    private var closed = false

    @Synchronized
    fun register(provider: SensorProvider) {
        check(!closed) { "scheduler_closed" }
        require(provider.id.isNotBlank() && provider.resource.isNotBlank())
        require(provider.mode in setOf("continuous", "on_change", "on_demand"))
        require(provider.id !in providers) { "duplicate_provider" }
        providers[provider.id] = provider
    }

    @Synchronized
    fun acquire(owner: String, id: String, mode: String? = null): Result {
        if (closed) return Result(false, "scheduler_closed")
        if (owner.isBlank()) return Result(false, "invalid_owner")
        val provider = providers[id] ?: return Result(false, "unsupported_sensor")
        val requestedMode = mode ?: provider.mode
        if (requestedMode != provider.mode) return Result(false, "mode_conflict")
        active[id]?.let {
            it.owners.add(owner)
            return Result(true)
        }
        if (active.values.any { it.provider.resource == provider.resource }) {
            return Result(false, "resource_conflict")
        }
        val started = try {
            provider.start()
        } catch (_: Exception) {
            false
        }
        if (!started) {
            // A provider may have partially subscribed before throwing.
            try { provider.stop() } catch (_: Exception) { /* fail closed */ }
            return Result(false, "provider_start_failed")
        }
        active[id] = Active(provider, mutableSetOf(owner))
        return Result(true, opened = true)
    }

    @Synchronized
    fun release(owner: String, id: String): Boolean {
        val slot = active[id] ?: return false
        if (!slot.owners.remove(owner)) return false
        if (slot.owners.isEmpty()) {
            try {
                slot.provider.stop()
            } catch (_: Exception) {
                // Preserve the lease when tracker shutdown is uncertain.
                slot.owners.add(owner)
                return false
            }
            active.remove(id)
        }
        return true
    }

    @Synchronized
    fun releaseAll(owner: String) {
        active.keys.toList().asReversed().forEach { id ->
            release(owner, id)
        }
    }

    @Synchronized
    fun snapshot(): List<TrackerState> =
        active.map { (id, slot) -> TrackerState(id, slot.provider.mode, slot.owners.size) }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        try {
            active.values.toList().asReversed().forEach { slot ->
                try {
                    slot.provider.stop()
                } catch (_: Exception) {
                    // Continue closing other resources during service destruction.
                }
            }
        } finally {
            active.clear()
        }
    }
}
