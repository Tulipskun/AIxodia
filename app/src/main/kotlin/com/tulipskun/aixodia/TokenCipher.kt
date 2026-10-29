package com.tulipskun.aixodia

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The one credential, at rest.
 *
 * The Cloudflare token can administer the account's D1 databases, so it is not
 * kept as plain text in the preferences file: it is sealed with AES-GCM under a
 * key that lives in the Android keystore and never leaves it. A backup of the
 * preference file is therefore a backup of ciphertext, useless on another
 * phone, where the keystore key does not exist.
 *
 * Values written before this existed are plain text. They are read as they are
 * and sealed on the next save, so an update does not lock the operator out of
 * their own account.
 */
object TokenCipher {
    private const val KEY_ALIAS = "aixodia_token_v1"
    private const val PREFIX = "v1:"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    /** GCM's standard nonce length, which is what [Cipher] hands back here. */
    private const val IV_BYTES = 12

    /** Sealed form. Unsealed input (no keystore, e.g. a unit test) is passed through. */
    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + body, Base64.NO_WRAP)
        }.getOrDefault(plain)
    }

    /**
     * The plain token. A value this keystore can no longer open — the key was
     * invalidated, or the file was restored onto another phone — is treated as
     * absent. Asking for the token again is honest; showing a token that will
     * fail every request is not.
     */
    fun open(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_BYTES)
            val body = raw.copyOfRange(IV_BYTES, raw.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
