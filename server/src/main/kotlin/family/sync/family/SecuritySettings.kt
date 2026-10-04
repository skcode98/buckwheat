package family.sync.family

import family.sync.auth.tokenLifetimeFromEnv
import java.time.Duration

const val DEFAULT_MAX_REQUEST_BYTES: Long = 4L * 1024 * 1024
private const val MAX_WINDOW_SECONDS = 86_400L

data class RateLimitSetting(
    val limit: Int,
    val window: Duration,
)

data class SecuritySettings(
    val tokenLifetime: Duration = tokenLifetimeFromEnv(),
    val maxRequestBytes: Long = DEFAULT_MAX_REQUEST_BYTES,
    val maxDisplayNameLength: Int = 100,
    val createRate: RateLimitSetting = RateLimitSetting(20, Duration.ofHours(1)),
    val joinRate: RateLimitSetting = RateLimitSetting(30, Duration.ofMinutes(10)),
    val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): SecuritySettings = SecuritySettings(
            tokenLifetime = tokenLifetimeFromEnv(env),
            maxRequestBytes = env.positiveLong("MAX_REQUEST_BYTES", DEFAULT_MAX_REQUEST_BYTES),
            maxDisplayNameLength = env.positiveInt("MAX_DISPLAY_NAME_LENGTH", 100),
            createRate = env.rateLimit(
                "FAMILY_CREATE_RATE_LIMIT", 20, "FAMILY_CREATE_RATE_WINDOW_SECONDS", 3_600,
            ),
            joinRate = env.rateLimit(
                "FAMILY_JOIN_RATE_LIMIT", 30, "FAMILY_JOIN_RATE_WINDOW_SECONDS", 600,
            ),
        )
    }
}

private fun Map<String, String>.positiveInt(name: String, fallback: Int): Int =
    this[name]?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: fallback

private fun Map<String, String>.positiveLong(name: String, fallback: Long): Long =
    this[name]?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: fallback

private fun Map<String, String>.rateLimit(
    limitName: String,
    limitFallback: Int,
    windowName: String,
    windowFallbackSeconds: Long,
): RateLimitSetting {
    val window = positiveLong(windowName, windowFallbackSeconds).coerceAtMost(MAX_WINDOW_SECONDS)
    return RateLimitSetting(positiveInt(limitName, limitFallback), Duration.ofSeconds(window))
}
