package app.sokyiptv.core

import android.app.Application
import app.sokyiptv.data.model.Source
import app.sokyiptv.data.model.SourceKind
import app.sokyiptv.data.model.LiveStreamFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class ProviderCredentialBackupTest {
    private val provider = Source(
        name = "Provider One",
        kind = SourceKind.XTREAM,
        url = "https://iptv.example:8080",
        username = "account123",
        password = "SensitivePasswordABC",
        liveFormat = LiveStreamFormat.MPEG_TS,
    )

    @Test fun encryptedPortableBackupRoundTripRestoresCredentialsButNotInternalIds() {
        val file = ProviderCredentialBackup.encrypt(listOf(provider), "unique-backup-secret-2026")
        val raw = String(file, Charsets.ISO_8859_1)
        assertFalse(raw.contains(provider.password!!))
        assertFalse(raw.contains(provider.username!!))
        assertFalse(raw.contains(provider.url))
        val result = ProviderCredentialBackup.decrypt(file, "unique-backup-secret-2026").single()
        assertEquals(0L, result.id)
        assertEquals(provider.name, result.name)
        assertEquals(provider.username, result.username)
        assertEquals(provider.password, result.password)
        assertEquals(provider.liveFormat, result.liveFormat)
    }

    @Test fun tamperingAndWrongPassphraseAreRejected() {
        val file = ProviderCredentialBackup.encrypt(listOf(provider), "unique-backup-secret-2026")
        assertThrows(Exception::class.java) {
            ProviderCredentialBackup.decrypt(file, "incorrect-passphrase")
        }
        val corrupted = file.clone()
        corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {
            ProviderCredentialBackup.decrypt(corrupted, "unique-backup-secret-2026")
        }
    }

    @Test fun plainTextAndShortSecretsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderCredentialBackup.encrypt(listOf(provider), "123")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderCredentialBackup.decrypt("username:password".toByteArray(), "12345678")
        }
    }
}
