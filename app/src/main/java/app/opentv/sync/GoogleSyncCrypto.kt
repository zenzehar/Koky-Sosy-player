package app.opentv.sync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Passphrase-based end-to-end AES-GCM encryption, readable on different devices. */
internal object GoogleSyncCrypto {
    private val random = SecureRandom()
    private val magic = byteArrayOf(0x4b, 0x53, 0x47, 0x31) // KSG1
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12

    private fun derive(passphrase: String, salt: ByteArray): SecretKeySpec {
        require(passphrase.length >= 12) { "Use a cloud passphrase of at least 12 characters." }
        // HmacSHA1 PBKDF2 is available throughout the app's Android API >=23.
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 200_000, 256)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded,
                "AES",
            )
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(text: String, passphrase: String): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(passphrase, salt))
        val sealed = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return magic + salt + cipher.iv + sealed
    }

    fun decrypt(file: ByteArray, passphrase: String): String {
        require(file.size > magic.size + SALT_LENGTH + IV_LENGTH + 16 &&
            file.take(4) == magic.toList()) { "Unknown cloud history format." }
        val salt = file.copyOfRange(4, 4 + SALT_LENGTH)
        val iv = file.copyOfRange(4 + SALT_LENGTH, 4 + SALT_LENGTH + IV_LENGTH)
        val data = file.copyOfRange(4 + SALT_LENGTH + IV_LENGTH, file.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(passphrase, salt), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }
}
