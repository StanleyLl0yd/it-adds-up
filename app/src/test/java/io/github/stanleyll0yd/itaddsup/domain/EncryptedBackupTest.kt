package io.github.stanleyll0yd.itaddsup.domain

import org.junit.Assert.*
import org.junit.Test

class EncryptedBackupTest {
    @Test
    fun portablePasswordRoundTripAndFreshRandomness() {
        val payload = "synthetic test ledger".toByteArray()
        val a = EncryptedBackup.encrypt(payload, "test-passphrase".toCharArray())
        val b = EncryptedBackup.encrypt(payload, "test-passphrase".toCharArray())
        assertFalse(a.contentEquals(b))
        assertArrayEquals(payload, EncryptedBackup.decrypt(a, "test-passphrase".toCharArray()))
    }

    @Test
    fun tamperWrongPasswordAndVersionAreRejected() {
        val bytes = EncryptedBackup.encrypt(byteArrayOf(1, 2, 3), "test-passphrase".toCharArray())
        assertThrows(Exception::class.java) {
            EncryptedBackup.decrypt(bytes, "incorrect".toCharArray())
        }
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {
            EncryptedBackup.decrypt(bytes, "test-passphrase".toCharArray())
        }
        bytes[3] = 2
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackup.decrypt(bytes, "test-passphrase".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackup.decrypt(byteArrayOf(), "test-passphrase".toCharArray())
        }
    }
}
