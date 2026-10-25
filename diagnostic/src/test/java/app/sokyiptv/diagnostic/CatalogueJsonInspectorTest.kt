package app.sokyiptv.diagnostic

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class CatalogueJsonInspectorTest {
    private fun check(s: String) = CatalogueJsonInspector.inspect(
        ByteArrayInputStream(s.toByteArray(Charsets.UTF_8)), "application/json"
    )

    @Test fun smallValidJsonUsesSameLenientParserAsMainApp() {
        val result = check("""[{"category_id":"1","category_name":"Family"},{"category_id":"2","category_name":"Movies"}]""")
        assertTrue(result.detail, result.detail.contains("kotlinx parser ACCEPTS"))
        assertTrue(result.detail, result.detail.contains("array items=2"))
        assertEquals(null, result.html)
    }

    @Test fun detectsFullyDownloadedMalformedArrayRatherThanGuessingFromOpeningBracket() {
        val result = check("""[{"category_id":"1", "bad": } ]""")
        assertTrue(result.detail, result.detail.contains("kotlinx parser REJECTS"))
        assertFalse(result.detail.contains("Family"))
    }

    @Test fun malformedVODCategoriesAtEndReportsSafeClosingShape() {
        val result = check("""[{"category_id":"7","category_name":"Films"}""")
        assertTrue(result.detail, result.detail.contains("kotlinx parser REJECTS"))
        assertTrue(result.detail, result.detail.contains("closed object"))
        assertFalse(result.detail.contains("Films"))
    }

    @Test fun twoPointThreeMegabyteLiveResponseUsesExactSokyParserNotStreamingSubstitute() {
        // D3 streamed this size even though it is practical to test the exact parser.
        val large = "[{\"name\":\"" + "z".repeat(2_300_000) + "\"}]"
        val result = check(large)
        assertTrue(result.detail, result.detail.contains("kotlinx parser ACCEPTS"))
        assertTrue(result.detail, result.detail.contains("array items=1"))
    }

    @Test fun emptyArrayAndHtmlAreDistinguished() {
        assertTrue(check("[]").detail.contains("array items=0"))
        val html = CatalogueJsonInspector.inspect(
            ByteArrayInputStream("<html><title>Challenge</title></html>".toByteArray()),
            "text/html"
        )
        assertTrue(html.detail.contains("HTML response"))
        assertNotNull(html.html)
    }
}
