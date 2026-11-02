package moe.mja.koekumo

import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import org.apache.http.client.methods.HttpGet
import java.net.URI
import java.util.regex.Pattern

object HlsPlaylistParser {
    private val mapUriPattern = Pattern.compile("URI=\"([^\"]+)\"")

    data class Segment(val url: String, val durationMs: Long?)
    data class HlsMediaPlaylist(val initUrl: String?, val segments: List<Segment>)

    fun parse(http: HttpInterface, playlistUri: URI): HlsMediaPlaylist {
        val lines = HttpClientTools.fetchResponseLines(http, HttpGet(playlistUri), "SoundCloud HLS Playlist")
        var initUrl: String? = null
        val segments = mutableListOf<Segment>()
        var pendingDurationMs: Long? = null

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-MAP:")) {
                    val m = mapUriPattern.matcher(line)
                    if (m.find()) {
                        initUrl = resolveUrl(playlistUri, m.group(1))
                    }
                } else if (line.startsWith("#EXTINF:")) {
                    pendingDurationMs = parseDuration(line.substring("#EXTINF:".length))
                }
            } else {
                segments.add(Segment(resolveUrl(playlistUri, line), pendingDurationMs))
                pendingDurationMs = null
            }
        }

        return HlsMediaPlaylist(initUrl, segments)
    }

    private fun resolveUrl(baseUri: URI, uriStr: String): String {
        return if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
            uriStr
        } else {
            baseUri.resolve(uriStr).toString()
        }
    }

    private fun parseDuration(value: String): Long? {
        val comma = value.indexOf(',')
        val raw = if (comma >= 0) value.substring(0, comma).trim() else value.trim()
        return runCatching { (raw.toDouble() * 1000.0).toLong() }.getOrNull()
    }
}
