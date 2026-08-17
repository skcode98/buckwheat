package com.danilkinkin.buckwheat.util

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

const val APP_LOCK_PIN_MIN_LENGTH = 4
const val APP_LOCK_PIN_MAX_LENGTH = 8

private const val V1_PREFIX = "v1"

private const val PBKDF2_ITERATIONS = 120_000
private const val PBKDF2_KEY_BITS = 256
private const val SALT_BYTES = 16

// Legacy SHA-256 scheme (static salt, no iterations) — kept only to verify hashes written by
// earlier app versions. New hashes are never produced in this format.
private const val LEGACY_SALT = "buckwheat-app-lock-v1:"

private val secureRandom = SecureRandom()

// Stores the PIN as PBKDF2-HMAC-SHA256 with a random per-install salt so the value is never
// kept in plaintext and offline brute force requires a key-stretching cost per guess. Format:
// "v1$<saltHex>$<hashHex>". The app-lock data is excluded from backups, so the hash can never
// travel into a plaintext backup file.
fun generatePinHash(pin: String): String {
    val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
    val derived = pbkdf2(pin, salt)
    return "$V1_PREFIX$${salt.toHex()}$${derived.toHex()}"
}

fun verifyPinHash(pin: String, stored: String?): Boolean {
    if (stored.isNullOrEmpty()) return false
    val parts = stored.split('$')
    return if (parts.size == 3 && parts[0] == V1_PREFIX) {
        val salt = parts[1].fromHexOrNull() ?: return false
        val expected = parts[2].fromHexOrNull() ?: return false
        val derived = pbkdf2(pin, salt)
        MessageDigest.isEqual(derived, expected)
    } else {
        // Legacy SHA-256 hash from before the PBKDF2 migration.
        legacyHash(pin) == stored
    }
}

fun isLegacyPinHash(stored: String?): Boolean =
    stored != null && stored.isNotEmpty() && stored.split('$').size != 3

private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    return factory.generateSecret(spec).encoded
}

private fun legacyHash(pin: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest("$LEGACY_SALT$pin".toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.fromHexOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        (hi shl 4 or lo).toByte()
    }
}

// Exponential backoff for failed unlock attempts: 30s, 1m, 2m, 4m, capped at 5m. A short
// numeric PIN is the app's weakest point, so lockouts must make in-app brute force infeasible.
fun appLockLockoutMillis(failedAttempts: Int): Long {
    if (failedAttempts <= 0) return 0L
    val base = 30_000L
    val cap = 5 * 60_000L
    val shift = (failedAttempts - 1).coerceAtMost(4)
    return minOf(base * (1L shl shift), cap)
}
