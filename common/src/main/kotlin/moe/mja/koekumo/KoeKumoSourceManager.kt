package moe.mja.koekumo

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.DataInput
import java.io.DataOutput
import java.util.regex.Pattern

class KoeKumoSourceManager(
    val config: KoeKumoConfig
) : AudioSourceManager {

    private val log = LoggerFactory.getLogger(KoeKumoSourceManager::class.java)

    private val httpInterfaceManager: HttpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager()
    private val client = SoundCloudClient(config)

    private val soundCloudUrlPattern = Pattern.compile("^https?://(?:www\\.|m\\.|on\\.)?soundcloud\\.com/.+")
    private val searchPrefix = "scsearch:"

    fun getClient(): SoundCloudClient = client

    fun getHttpInterface(): HttpInterface = httpInterfaceManager.getInterface()

    private val name = "soundcloud"

    override fun getSourceName(): String {
        return name
    }

    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {
        val id = reference.identifier

        if (id.startsWith(searchPrefix)) {
            val query = id.substring(searchPrefix.length).trim()
            return loadSearch(query)
        }

        if (soundCloudUrlPattern.matcher(id).matches() || id.startsWith("https://snd.sc/")) {
            return loadUrl(id)
        }

        return null
    }

    private fun loadSearch(query: String): AudioItem? {
        if (query.isBlank()) return AudioReference.NO_TRACK

        getHttpInterface().use { http ->
            val tracks = client.search(http, query, limit = 10)
            if (tracks.isEmpty()) return AudioReference.NO_TRACK

            val audioTracks = tracks.mapNotNull { parseTrack(it) }
            if (audioTracks.isEmpty()) return AudioReference.NO_TRACK

            return BasicAudioPlaylist("SoundCloud Search: $query", audioTracks, null, true)
        }
    }

    private fun loadUrl(url: String): AudioItem? {
        getHttpInterface().use { http ->
            val resolved = client.resolve(http, url) ?: return AudioReference.NO_TRACK
            val kind = resolved.optString("kind")

            if ("track".equals(kind, ignoreCase = true)) {
                return parseTrack(resolved) ?: AudioReference.NO_TRACK
            }

            if ("playlist".equals(kind, ignoreCase = true)) {
                val title = resolved.optString("title", "SoundCloud Playlist")
                val tracksJson = resolved.optJSONArray("tracks") ?: return AudioReference.NO_TRACK
                val audioTracks = mutableListOf<AudioTrack>()

                for (i in 0 until tracksJson.length()) {
                    val tr = tracksJson.optJSONObject(i) ?: continue
                    parseTrack(tr)?.let { audioTracks.add(it) }
                }

                if (audioTracks.isEmpty()) return AudioReference.NO_TRACK
                return BasicAudioPlaylist(title, audioTracks, null, false)
            }

            return AudioReference.NO_TRACK
        }
    }

    private fun parseTrack(json: JSONObject): AudioTrack? {
        val id = json.opt("id")?.toString() ?: return null
        val title = json.optString("title", "Unknown Title")
        val user = json.optJSONObject("user")
        val author = user?.optString("username", "Unknown Artist") ?: "Unknown Artist"
        val duration = json.optLong("duration", 0L)
        val permalink = json.optString("permalink_url", "https://soundcloud.com/tracks/$id")
        val artwork = json.optString("artwork_url", null)
            ?.replace("-large", "-t500x500")

        val info = AudioTrackInfo(
            title,
            author,
            duration,
            id,
            false,
            permalink,
            artwork,
            null
        )

        return KoeKumoAudioTrack(info, json, this)
    }

    override fun isTrackEncodable(track: AudioTrack): Boolean = true

    override fun encodeTrack(track: AudioTrack, output: DataOutput) {
        // Track encoding is handled natively by Lavaplayer's DefaultAudioPlayerManager
    }

    override fun decodeTrack(trackInfo: AudioTrackInfo, input: DataInput): AudioTrack {
        return KoeKumoAudioTrack(trackInfo, null, this)
    }

    override fun shutdown() {
        runCatching { httpInterfaceManager.close() }
    }
}
