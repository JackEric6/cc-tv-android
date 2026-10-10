package com.github.tvbox.osc.util.live

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YspLiveChannelCatalogTest {
    @Test
    fun bundledCatalogMeetsChannelCounts() {
        val groups = YspLiveChannelCatalog.createGroups()

        assertEquals("央视", groups[0].asJsonObject.get("group").asString)
        assertEquals("卫视", groups[1].asJsonObject.get("group").asString)
        assertEquals(21, channels(groups[0]).size())
        assertEquals(41, channels(groups[1]).size())
    }

    @Test
    fun everyBundledChannelUsesAUniqueResolvableReference() {
        val groups = YspLiveChannelCatalog.createGroups()
        val refs = mutableSetOf<String>()
        groups.forEach { group ->
            channels(group).forEach { element ->
                val channel = element.asJsonObject
                val ref = channel.getAsJsonArray("urls")[0].asString.substringBefore('$')
                assertTrue(ref.startsWith("ysp://"))
                assertTrue("duplicate reference: $ref", refs.add(ref))
                assertNotNull(YspLiveChannelCatalog.find(ref.removePrefix("ysp://")))
                assertTrue(channel.getAsJsonObject("header").has("Referer"))
            }
        }
        assertEquals(62, refs.size)
    }

    @Test
    fun accountOnlyChannelsAreNotIncluded() {
        assertNull(YspLiveChannelCatalog.find("cctvsjdl"))
        assertNull(YspLiveChannelCatalog.find("cctvfyzq"))
    }

    @Test
    fun cKeyMatchesYangshipinProtocolVector() {
        var nextByte = 0
        val cKey = YspLiveResolver.createCKeyForTest(
            channelId = "2024078201",
            nowMillis = 1_700_000_000_123,
            guid = "00112233445566778899aabbccddeeff",
            uid = "A1B2C3D4",
        ) { size ->
            ByteArray(size) { (nextByte++ and 0xff).toByte() }
        }

        assertEquals(
            "--01IGU5JFTCxrv30RkJnlsAZYhduYcTRWoewhYVniq0KwOFM_weIIA-pxz6IqDpV43pOb3OIBJreO9ay-wMGPLviIF3dsAjcVTUiKLS7kYxs6fDVR-tNstMGhvhOOWNqDqdidhIo9QeI5FXZQO7bZYnszb6rx90OmCwwMXNBKXYhtFJ4UHSFZaC7ohiz2aUsxF67NuAumCcA8N1MFomIWFNVt6gdLi-yk7fLklRNSuWTzX9VOXjH9uJ2vxqcEw0RCZ6NhcRBSsKiYDXRSmEU0c4Q_oqH5GcyKv1VEW8IMA-sK-aw3_Am5QWuZ8nbgoTZoZiEWb9yqR174K6NrHBQL_Qg--Un3LzTRcFH_iBzaIOh3woXVhoabDnPEyXomA_jFmABBtWFpBJ6LaBpEPW8tZGrTfYtNlGrxfYYISFihmliSVveulblf6ywSuvCDL4B1VkvmNvbB7akoH1pAuz2XvV4YD2pfc",
            cKey,
        )
    }

    private fun channels(group: JsonElement): JsonArray = group.asJsonObject.getAsJsonArray("channels")
}
