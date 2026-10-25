package app.sokyiptv.debug

/**
 * Debug-emulator-only prefill for this ONE-OFF test build, explicitly requested
 * by the user. Anyone can extract credentials from a debug APK; never copy this
 * debug source into the release variant. Does not modify existing saved providers.
 */
internal object TestXtreamDefaults {
    private fun decode(values: IntArray) = buildString(values.size) {
        values.forEach { append(it.toChar()) }
    }
    fun values(): Triple<String, String, String>? = Triple(
        decode(intArrayOf(104, 116, 116, 112, 58, 47, 47, 115, 97, 119, 97, 105, 112, 116, 118, 46, 118, 105, 112, 58, 56, 48)),
        decode(intArrayOf(114, 97, 103, 97, 98, 50, 48, 50, 48)),
        decode(intArrayOf(55, 55, 55, 114, 114, 50, 50, 50)),
    )
}
