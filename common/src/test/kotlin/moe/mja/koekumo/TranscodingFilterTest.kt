package moe.mja.koekumo

import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val STREAM_BASE = "https://api-v2.soundcloud.com/media/soundcloud:tracks:12345/stream"

private fun transcoding(
    protocol: String = "hls",
    mime: String = "audio/mp4",
    url: String = "$STREAM_BASE/hls",
    drm: Boolean? = null,
    encryption: String? = null
): JSONObject {
    val format = JSONObject()
        .put("protocol", protocol)
        .put("mime_type", mime)
    val result = JSONObject()
        .put("url", url)
        .put("preset", "mp4")
        .put("duration", 180000L)
        .put("snipped", false)
        .put("quality", "sq")
        .put("format", format)
    if (drm != null) result.put("drm", drm)
    if (encryption != null) result.put("encryption_type", encryption)
    return result
}

private fun trackJson(vararg transcodings: JSONObject): JSONObject {
    val array = JSONArray()
    transcodings.forEach { array.put(it) }
    return JSONObject()
        .put("id", 12345L)
        .put("kind", "track")
        .put("title", "Fixture Track")
        .put("duration", 180000L)
        .put("permalink_url", "https://soundcloud.com/artist/fixture-track")
        .put("artwork_url", JSONObject.NULL)
        .put("user", JSONObject().put("username", "Artist"))
        .put("media", JSONObject().put("transcodings", array))
}

class TranscodingFilterTest {

    private val client = SoundCloudClient(KoeKumoConfig(clientId = "0123456789abcdef0123456789abcdef"))
    private val sourceManager = KoeKumoSourceManager(KoeKumoConfig())

    @Test
    fun `rejects transcoding flagged drm`() {
        assertNull(client.findPlayableHlsTranscoding(trackJson(transcoding(drm = true))))
    }

    @Test
    fun `rejects transcoding carrying encryption type`() {
        assertNull(client.findPlayableHlsTranscoding(trackJson(transcoding(encryption = "cenc"))))
    }

    @Test
    fun `rejects progressive only transcoding`() {
        val only = transcoding(protocol = "progressive", mime = "audio/mpeg")
        assertNull(client.findPlayableHlsTranscoding(trackJson(only)))
    }

    @Test
    fun `rejects track with no media object`() {
        assertNull(client.findPlayableHlsTranscoding(JSONObject().put("id", 12345L)))
    }

    @Test
    fun `rejects transcoding with blank url`() {
        assertNull(client.findPlayableHlsTranscoding(trackJson(transcoding(url = ""))))
    }

    @Test
    fun `selects non drm hls audio mp4 transcoding`() {
        val playable = transcoding()
        val chosen = client.findPlayableHlsTranscoding(trackJson(playable))
        assertNotNull(chosen)
        assertEquals(playable.optString("url"), chosen!!.optString("url"))
    }

    @Test
    fun `prefers non drm audio mp4 over non drm audio mpeg`() {
        val mpeg = transcoding(mime = "audio/mpeg", url = "$STREAM_BASE/mpeg")
        val mp4 = transcoding(mime = "audio/mp4", url = "$STREAM_BASE/mp4")
        val chosen = client.findPlayableHlsTranscoding(trackJson(mpeg, mp4))
        assertEquals(mp4.optString("url"), chosen!!.optString("url"))
    }

    @Test
    fun `skips drm audio mp4 and selects non drm hls fallback`() {
        val drmTrack = transcoding(drm = true, url = "$STREAM_BASE/drm")
        val fallback = transcoding(url = "$STREAM_BASE/fallback")
        val chosen = client.findPlayableHlsTranscoding(trackJson(drmTrack, fallback))
        assertEquals(fallback.optString("url"), chosen!!.optString("url"))
    }

    @Test
    fun `rejects track whose only stream is a thirty second mp3 preview`() {
        val preview = transcoding(
            mime = "audio/mpeg",
            url = "https://api-v2.soundcloud.com/media/soundcloud:tracks:1632906831/d2c031d1/preview/hls"
        )
        assertNull(client.findPlayableHlsTranscoding(trackJson(preview)))
    }

    @Test
    fun `rejects preview transcoding even when mime type is audio mp4`() {
        val preview = transcoding(
            url = "https://api-v2.soundcloud.com/media/soundcloud:tracks:1632906831/d2c031d1/preview/hls"
        )
        assertNull(client.findPlayableHlsTranscoding(trackJson(preview)))
    }

    @Test
    fun `selects full stream fmp4 hls over preview hls`() {
        val preview = transcoding(url = "$STREAM_BASE/preview/hls")
        val full = transcoding(url = "$STREAM_BASE/stream/hls")
        val chosen = client.findPlayableHlsTranscoding(trackJson(preview, full))
        assertEquals(full.optString("url"), chosen!!.optString("url"))
    }

    @Test
    fun `getStreamUrl returns null for drm only track without requesting a token`() {
        val http = HttpClientTools.createDefaultThreadLocalManager().getInterface()
        val result = client.getStreamUrl(http, trackJson(transcoding(drm = true, url = "http://127.0.0.1:9/token")))
        assertNull(result)
    }

    @Test
    fun `parseTrack drops drm only track from search and playlist results`() {
        val track = trackJson(transcoding(drm = true))
        assertNull(sourceManager.parseTrack(track))
    }

    @Test
    fun `parseTrack keeps non drm hls track from search and playlist results`() {
        val track = trackJson(transcoding())
        assertNotNull(sourceManager.parseTrack(track))
    }
}
