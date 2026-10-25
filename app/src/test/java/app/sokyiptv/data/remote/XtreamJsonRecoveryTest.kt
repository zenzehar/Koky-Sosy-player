package app.sokyiptv.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class XtreamJsonRecoveryTest {
    private val decoder = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test fun missingFinalArrayBracketCanBeRepairedOnlyWhenFullJsonValidates() {
        val input = """[{"category_id":"1","category_name":"Movies"}"""
        val result = XtreamJsonRecovery.recover(input, decoder, requireArray = true)
        assertTrue(result is JsonArray)
        assertEquals("Movies", (result as JsonArray)[0]
            .let { it as JsonObject }["category_name"]?.jsonPrimitive?.content)
    }

    @Test fun lenientPanelSyntaxIsCanonicalizedBeforeImport() {
        val input = """[{stream_id:17,name:'Live One',category_id:'2'}]"""
        val result = XtreamJsonRecovery.recover(input, decoder, requireArray = true)
        assertTrue(result is JsonArray)
        assertEquals("Live One", (result as JsonArray)[0]
            .let { it as JsonObject }["name"]?.jsonPrimitive?.content)
    }

    @Test fun rejectsTruncatedAndWrongRootInsteadOfImportingPartialCatalogue() {
        assertNull(XtreamJsonRecovery.recover("""[{"stream_id":""", decoder, true))
        assertNull(XtreamJsonRecovery.recover("""{"not":"an array"}""", decoder, true))
    }

    @Test fun capPreventsFallbackWorkOnUnboundedProviderPayloads() {
        assertNull(XtreamJsonRecovery.recover(" ".repeat(12_000_001), decoder, true))
    }
}
