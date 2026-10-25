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
