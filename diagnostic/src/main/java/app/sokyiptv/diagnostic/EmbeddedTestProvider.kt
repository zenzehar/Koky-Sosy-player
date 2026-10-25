package app.sokyiptv.diagnostic

/**
 * TEMPORARY USER-REQUESTED TEST CREDENTIALS.
 *
 * This class is deliberately included in one-off diagnostic APKs only.
 * Numeric char arrays are light obfuscation, NOT encryption or secret protection:
 * anyone who downloads or decompiles this APK can recover the provider login.
 * Never reuse in the production Soky player, and delete test releases after
 * explicit user approval; remote downloads and Git history may remain.
 *
 * All future one-off diagnostic builds should branch from this D6 baseline
 * until user declares testing complete.
 */
internal object EmbeddedTestProvider {
    private fun decode(values: IntArray): String = buildString(values.size) {
        values.forEach { append(it.toChar()) }
    }

    fun create(): ProviderImport.Provider = ProviderImport.Provider(
        name = "Embedded testing account",
        kind = "XTREAM",
        url = decode(intArrayOf(104, 116, 116, 112, 58, 47, 47, 115, 97, 119, 97, 105, 112, 116, 118, 46, 118, 105, 112, 58, 56, 48)),
        username = decode(intArrayOf(114, 97, 103, 97, 98, 50, 48, 50, 48)),
        password = decode(intArrayOf(55, 55, 55, 114, 114, 50, 50, 50)),
        userAgent = "SokyIPTV/0.1 (Android)",
    )
}
