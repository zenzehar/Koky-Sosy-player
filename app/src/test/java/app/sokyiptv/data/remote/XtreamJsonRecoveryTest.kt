package app.sokyiptv.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
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

    @Test fun platformReaderAcceptsVendorSyntaxWhichTheOldGsonFallbackDidNotFix() {
        val input = """[{"stream_id":1,"name":"Normal"}, {stream_id:2,name:'Vendor item'}]"""
        val recovered = XtreamJsonRecovery.recover(input, decoder, requireArray = true)
        assertTrue(recovered is JsonArray)
        assertEquals(2, (recovered as JsonArray).size)
        assertEquals("Vendor item", (recovered[1] as JsonObject)["name"]?.jsonPrimitive?.content)
        assertEquals("2", (recovered[1] as JsonObject)["stream_id"]?.jsonPrimitive?.content)
    }

    @Test fun fullyConsumedStructuralValidationRejectsExtraTrailingContent() {
        assertNull(XtreamJsonRecovery.recover("""[{"stream_id":1}] trailing""", decoder, true))
    }

    @Test fun numericallyTypedProviderIdsStillReadAsTextContent() {
        val input = """[{stream_id:1723467, name:'Channel', category_id:28}]"""
        val recovered = XtreamJsonRecovery.recover(input, decoder, true) as JsonArray
        val entry = recovered.single() as JsonObject
        assertEquals("1723467", entry["stream_id"]?.jsonPrimitive?.content)
        assertEquals("28", entry["category_id"]?.jsonPrimitive?.content)
    }

    @Test fun rejectsTruncatedAndWrongRootInsteadOfImportingPartialCatalogue() {
        assertNull(XtreamJsonRecovery.recover("""[{"stream_id":""", decoder, true))
        assertNull(XtreamJsonRecovery.recover("""{"not":"an array"}""", decoder, true))
    }

    @Test fun capPreventsFallbackWorkOnUnboundedProviderPayloads() {
        assertNull(XtreamJsonRecovery.recover(" ".repeat(12_000_001), decoder, true))
    }
}
