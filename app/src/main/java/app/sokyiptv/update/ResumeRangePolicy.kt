package app.sokyiptv.update

/** HTTP 206 must refer to the exact byte offset and immutable release length.
 * Otherwise restart on 200, or fail safely instead of corrupting the APK.
 */
internal object ResumeRangePolicy {
    fun canAppend(
        offset: Long,
        expectedBytes: Long,
        httpStatus: Int,
        contentRange: String?,
        savedValidator: String?,
        responseValidator: String?,
    ): Boolean {
        if (offset <= 0L || httpStatus != 206 || expectedBytes <= 0L) return false
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(contentRange.orEmpty())
            ?: return false
        val first = match.groupValues[1].toLongOrNull() ?: return false
        val last = match.groupValues[2].toLongOrNull() ?: return false
        val total = match.groupValues[3].toLongOrNull() ?: return false
        if (first != offset || last < first || last >= total || total != expectedBytes)
            return false
        return savedValidator == null || responseValidator == null ||
            savedValidator == responseValidator
    }
}
