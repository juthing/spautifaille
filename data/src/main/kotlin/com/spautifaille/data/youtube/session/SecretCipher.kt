package com.spautifaille.data.youtube.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffrement symétrique des secrets stockés (cookies YouTube). Abstraction pour que le stockage soit testable
 * sans Android Keystore (indisponible sous Robolectric).
 */
internal interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** Lève une exception si [blob] est corrompu ou chiffré avec une autre clé (clé Keystore invalidée). */
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256/GCM : format `IV (12 octets) || texte chiffré + tag`. L'IV est choisi par le [Cipher] (obligatoire avec les
 * clés Android Keystore, qui refusent un IV imposé).
 */
internal class AesGcmSecretCipher(private val key: () -> SecretKey) : SecretCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "IV GCM inattendu : ${iv.size} octets" }
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "blob chiffré trop court" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Clé AES-256 non exportable dans l'Android Keystore, créée au premier usage. */
internal object AndroidKeystoreKeys {
    private const val PROVIDER = "AndroidKeyStore"

    fun secretKey(alias: String): () -> SecretKey = { getOrCreate(alias) }

    @Synchronized
    private fun getOrCreate(alias: String): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
