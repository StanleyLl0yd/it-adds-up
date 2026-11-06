package io.github.stanleyll0yd.itaddsup.domain

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable password recovery: no server or device key. Header is authenticated as AAD. */
object EncryptedBackup {
    const val MAX_BYTES = 5 * 1024 * 1024
    private val magic = byteArrayOf(0x49, 0x41, 0x55, 1)

    fun encrypt(payload: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 8) { "Пароль: минимум 8 символов" }
        require(payload.size <= MAX_BYTES)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val header = magic + salt + iv
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iv)
        cipher.updateAAD(header)
        return header + cipher.doFinal(payload)
    }

    fun decrypt(bytes: ByteArray, password: CharArray): ByteArray {
        require(bytes.size in 48..MAX_BYTES + 48) { "Неподдерживаемый размер копии" }
        require(bytes.copyOfRange(0, 4).contentEquals(magic)) { "Неподдерживаемая версия копии" }
        val cipher =
            cipher(
                Cipher.DECRYPT_MODE,
                password,
                bytes.copyOfRange(4, 20),
                bytes.copyOfRange(20, 32),
            )
        cipher.updateAAD(bytes.copyOfRange(0, 32))
        return cipher.doFinal(bytes, 32, bytes.size - 32)
    }

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, iv: ByteArray): Cipher {
        val spec = PBEKeySpec(password, salt, 210000, 256)
        val keyBytes =
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            }
        } finally {
            keyBytes.fill(0)
        }
    }
}
