package moe.mja.koekumo

import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class KoeKumoIntegrationTest {

    @Test
    fun testScrapeClientIdAndResolveTrack() {
        val config = KoeKumoConfig()
        val client = SoundCloudClient(config)
        val http = HttpClientTools.createDefaultThreadLocalManager().getInterface()

        val clientId = client.getClientId(http)
        println("Scraped Client ID: $clientId")
        assertNotNull(clientId)
        assertTrue(clientId.length == 32)

        // Test track short URL: https://on.soundcloud.com/V6a3zv7kHTm2jtjisr
        val shortUrl = "https://on.soundcloud.com/V6a3zv7kHTm2jtjisr"
        val json = client.resolve(http, shortUrl)
        assertNotNull(json)
        println("Resolved Title from short URL: ${json?.optString("title")}")
        assertTrue(json?.optString("title")?.isNotBlank() == true)

        val streamUrl = client.getStreamUrl(http, json!!)
        assertNotNull(streamUrl)
        println("Resolved HLS Stream URL: $streamUrl")

        val playlist = HlsPlaylistParser.parse(http, URI(streamUrl!!))
        assertNotNull(playlist.initUrl)
        println("EXT-X-MAP Init URL: ${playlist.initUrl}")
        assertTrue(playlist.segments.isNotEmpty())
        println("Total HLS Segments: ${playlist.segments.size}")
    }
}
