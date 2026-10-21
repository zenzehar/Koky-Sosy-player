package app.sokyiptv.sync

import org.junit.Assert.*
import org.junit.Test

class SyncDiscoveryTest {
    @Test fun fourFieldsFromNewSenderAreAccepted() {
        val id = "bd245eca-0bd8-43fb-9a9b-512fe926dd11"
        val result = SyncDiscovery.parseReply(
            "SOKY_SYNC_HERE_V1|SDMC HAKO Pro|192.168.0.105:36063|$id",
            "192.168.0.105"
        )
        assertNotNull(result)
        assertEquals("192.168.0.105:36063", result!!.address)
        assertEquals("SDMC HAKO Pro", result.name)
        assertEquals(id, result.deviceId)
    }

    @Test fun legacyThreeFieldsStillWork() {
        val result = SyncDiscovery.parseReply("SOKY_SYNC_HERE_V1|Old TV|192.168.0.105:12345","192.168.0.105")
        assertEquals("", result?.deviceId)
    }

    @Test fun packetSpoofOrInvalidFieldsAreRejected() {
        val id = "bd245eca-0bd8-43fb-9a9b-512fe926dd11"
        assertNull(SyncDiscovery.parseReply("SOKY_SYNC_HERE_V1|TV|192.168.0.105:36063|$id","192.168.0.106"))
        assertNull(SyncDiscovery.parseReply("SOKY_SYNC_HERE_V1|TV|192.168.0.105:99999|$id","192.168.0.105"))
        assertNull(SyncDiscovery.parseReply("SOKY_SYNC_HERE_V1|TV|192.168.0.105:36063|bogus","192.168.0.105"))
        assertNull(SyncDiscovery.parseReply("BAD|TV|192.168.0.105:36063|$id","192.168.0.105"))
    }
}
