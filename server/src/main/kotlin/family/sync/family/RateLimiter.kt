package family.sync.family

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val DEFAULT_MAX_KEYS = 10_000
private const val CLEANUP_EVERY = 256L

class RateLimiter(
    private val setting: RateLimitSetting,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxKeys: Int = DEFAULT_MAX_KEYS,
) {

    private val windowMillis: Long = setting.window.toMillis().coerceAtLeast(1L)

    init {
        require(setting.limit > 0) { "a rate limit must allow at least one request" }
    }

    private val windows = ConcurrentHashMap<String, Window>()
    private val requests = AtomicLong()

    fun tryAcquire(key: String): Boolean {
        val timestamp = now()
        val window = windows.computeIfAbsent(key) { Window(setting.limit) }
        val acquired = window.tryAcquire(timestamp, windowMillis)
        evictExpired(timestamp)
        return acquired
    }

    val trackedKeys: Int get() = windows.size

    private fun evictExpired(timestamp: Long) {
        val cleanupDue = requests.incrementAndGet() % CLEANUP_EVERY == 0L
        if (!cleanupDue && windows.size <= maxKeys) return
        windows.entries.removeIf { it.value.isStale(timestamp, windowMillis) }
        if (windows.size <= maxKeys) return
        windows.entries.sortedBy { it.value.lastSeen }
            .take(windows.size - maxKeys)
            .forEach { windows.remove(it.key, it.value) }
    }

    private class Window(private val capacity: Int) {
        private val hits = LongArray(capacity)
        private var oldest = 0
        private var size = 0
        var lastSeen: Long = 0L
            private set

        @Synchronized
        fun tryAcquire(timestamp: Long, windowMillis: Long): Boolean {
            lastSeen = timestamp
            val cutoff = timestamp - windowMillis
            while (size > 0 && hits[oldest] <= cutoff) {
                oldest = (oldest + 1) % capacity
                size--
            }
            if (size >= capacity) return false
            hits[(oldest + size) % capacity] = timestamp
            size++
            return true
        }

        @Synchronized
        fun isStale(timestamp: Long, windowMillis: Long): Boolean =
            size == 0 || lastSeen + windowMillis <= timestamp
    }
}
