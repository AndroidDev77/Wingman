package com.wingman.core.auth.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.wingman.core.auth.BlobStore
import com.wingman.core.auth.SecretCipher
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * Android halves of EncryptedTokenStore (I-011). Compiled by CI; device behaviour UNVERIFIED.
 */

/**
 * AES-256-GCM with a non-exportable key generated inside the Android Keystore (hardware-backed
 * where the device has it). Blob layout: 12-byte IV followed by ciphertext + 16-byte tag. The key
 * never leaves the Keystore, so a copied preferences file cannot be decrypted elsewhere.
 */
class KeystoreSecretCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV length" }
        return iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "blob too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Background SMS sync must authenticate while the screen is locked, so no
                // user-authentication requirement on the key.
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "wingman.auth.session.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}

/**
 * One ciphertext string in app-private SharedPreferences. `allowBackup="false"` in the manifest
 * keeps it out of cloud backups (and a restored blob could not be decrypted anyway).
 */
class SharedPrefsBlobStore(context: Context, file: String = FILE, private val key: String = KEY) : BlobStore {
    private val prefs = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(key, null)

    // commit(), not apply(): sign-out must be on disk before the process can die.
    override fun write(value: String) { prefs.edit().putString(key, value).commit() }

    override fun delete() { prefs.edit().remove(key).commit() }

    companion object {
        const val FILE = "wingman_auth"
        const val KEY = "session"
    }
}
