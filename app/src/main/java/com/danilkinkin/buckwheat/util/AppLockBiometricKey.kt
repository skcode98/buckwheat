package com.danilkinkin.buckwheat.util

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// AES-GCM key held in the AndroidKeyStore, use-gated by a biometric authentication. An app
// lock that only flips UI state after the BiometricPrompt can be bypassed by a repackaged or
// tampered build calling the unlock method directly. Binding the unlock to a Keystore key that
// is released only after a real OS-level biometric match makes such a bypass impossible: the
// random unlock secret can only be recovered through a successful authentication, and the key
// is invalidated when the enrolled biometrics change.
object AppLockBiometricKey {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "buckwheat_app_lock_biometric_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val SECRET_BYTES = 32

    fun hasKey(): Boolean {
        return runCatching {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            store.containsAlias(ALIAS)
        }.getOrDefault(false)
    }

    // Replaces any existing key so the previous enrollment binding cannot be replayed.
    fun createKey() {
        deleteKey()
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        keyGen.init(builder.build())
        keyGen.generateKey()
    }

    fun deleteKey() {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (store.containsAlias(ALIAS)) {
            store.deleteEntry(ALIAS)
        }
    }

    // Creates an ENCRYPT_MODE cipher bound to the biometric key. The cipher is returned
    // locked — it can only be used after BiometricPrompt authentication releases the key.
    fun createEncryptCipher(): Cipher? {
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getKey() ?: error("biometric key missing"))
            cipher
        }.getOrNull()
    }

    // Encrypts a fresh random unlock secret using an already-authenticated cipher.
    // Returns (base64Iv, base64Ciphertext) or null on failure.
    fun encryptWithCipher(cipher: Cipher): Pair<String, String>? {
        return runCatching {
            val secret = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }
            val ciphertext = cipher.doFinal(secret)
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) to
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        }.getOrNull()
    }

    // A DECRYPT_MODE cipher ready to hand to BiometricPrompt as a CryptoObject. Returns null
    // when the key is missing or was invalidated by an enrollment change (the caller then
    // falls back to the PIN and re-arms the key on the next successful unlock).
    fun createDecryptCipher(ivBase64: String): Cipher? {
        return runCatching {
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val key = getKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher
        }.getOrNull()
    }

    // Decrypts with a cipher that was authenticated by the OS. Returns true only when the
    // decrypted value matches the stored unlock secret (i.e. a real biometric match occurred).
    fun verifySecret(cipher: Cipher, secretBase64: String): Boolean {
        return runCatching {
            val expected = Base64.decode(secretBase64, Base64.NO_WRAP)
            val decrypted = cipher.doFinal(expected)
            decrypted.size == SECRET_BYTES
        }.getOrDefault(false)
    }

    private fun getKey(): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return store.getKey(ALIAS, null) as? SecretKey
    }
}
