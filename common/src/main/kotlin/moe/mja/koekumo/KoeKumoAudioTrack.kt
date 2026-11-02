package moe.mja.koekumo

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAacTrackConsumer
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegFileLoader
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegTrackConsumer
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegTrackInfo
import com.sedmelluq.discord.lavaplayer.container.mpeg.reader.MpegFileTrackProvider
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.tools.http.HttpStreamTools
import com.sedmelluq.discord.lavaplayer.tools.io.ChainedInputStream
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import org.apache.http.client.methods.HttpGet
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.Future

class KoeKumoAudioTrack(
    trackInfo: AudioTrackInfo,
    private val rawTrackJson: JSONObject?,
    private val sourceManager: KoeKumoSourceManager
) : DelegatedAudioTrack(trackInfo) {

    private val log = LoggerFactory.getLogger(KoeKumoAudioTrack::class.java)

    override fun process(executor: LocalAudioTrackExecutor) {
        val streamUrl: String
        sourceManager.getHttpInterface().use { http ->
            val json = rawTrackJson ?: sourceManager.getClient().resolve(http, trackInfo.uri)
                ?: throw FriendlyException("Could not resolve SoundCloud track metadata", FriendlyException.Severity.SUSPICIOUS, null)

            streamUrl = sourceManager.getClient().getStreamUrl(http, json)
                ?: throw FriendlyException("Could not find playable HLS stream for SoundCloud track", FriendlyException.Severity.SUSPICIOUS, null)
        }

        sourceManager.getHttpInterface().use { http ->
            val playlist = HlsPlaylistParser.parse(http, URI(streamUrl))
            if (playlist.initUrl == null) {
                throw FriendlyException("SoundCloud fMP4 HLS playlist does not contain #EXT-X-MAP init segment", FriendlyException.Severity.SUSPICIOUS, null)
            }

            val player = Fmp4Player(sourceManager, playlist, executor, trackInfo)
            try {
                executor.executeProcessingLoop(player::provideFrames, player::performSeek, true)
            } finally {
                player.close()
            }
        }
    }

    override fun makeShallowClone(): AudioTrack {
        return KoeKumoAudioTrack(trackInfo, rawTrackJson, sourceManager)
    }

    override fun getSourceManager(): AudioSourceManager {
        return sourceManager
    }

    private class Fmp4Player(
        private val sourceManager: KoeKumoSourceManager,
        private val playlist: HlsPlaylistParser.HlsMediaPlaylist,
        private val executor: LocalAudioTrackExecutor,
        private val trackInfo: AudioTrackInfo
    ) : AutoCloseable {
        private val log = LoggerFactory.getLogger(Fmp4Player::class.java)

        private var provider: Fmp4StreamProvider? = null
        private var chain: ChainedInputStream? = null
        private var consumer: MpegTrackConsumer? = null
        private var reader: MpegFileTrackProvider? = null

        fun provideFrames() {
            ensureOpened()
            reader?.provideFrames()
        }

        fun performSeek(positionMs: Long) {
            val clamped = Math.max(0, positionMs)
            val segmentIndex = findSegmentIndex(playlist.segments, clamped)
            openChain(segmentIndex, clamped)
        }

        private fun ensureOpened() {
            if (reader == null) {
                openChain(0, 0)
            }
        }

        private fun openChain(segmentIndex: Int, positionMs: Long) {
            closeChain()

            val initUrl = playlist.initUrl ?: return
            val p = Fmp4StreamProvider(sourceManager, initUrl, playlist.segments, segmentIndex)
            provider = p
            val c = ChainedInputStream(p)
            chain = c

            val loader = MpegFileLoader(NonSeekableInputStream(c))
            loader.parseHeaders()

            val track = selectAudioTrack(loader.trackList)
                ?: throw FriendlyException("No AAC audio track found in SoundCloud fMP4 stream", FriendlyException.Severity.SUSPICIOUS, null)

            track.initialise()

            var segmentStartMs = 0L
            for (i in 0 until minOf(segmentIndex, playlist.segments.size)) {
                segmentStartMs += playlist.segments[i].durationMs ?: 0L
            }
            track.seekPerformed(positionMs, segmentStartMs)

            val r = loader.loadReader(track)
                ?: throw FriendlyException("Failed to initialize MP4 track reader", FriendlyException.Severity.SUSPICIOUS, null)

            consumer = track
            reader = r
        }

        private fun selectAudioTrack(tracks: List<MpegTrackInfo>): MpegTrackConsumer? {
            for (t in tracks) {
                if ("soun" == t.handler && "mp4a" == t.codecName) {
                    return MpegAacTrackConsumer(executor.processingContext, t)
                }
            }
            return null
        }

        private fun closeChain() {
            runCatching { provider?.close() }
            provider = null
            runCatching { consumer?.close() }
            consumer = null
            reader = null
            chain = null
        }

        override fun close() {
            closeChain()
        }

        private fun findSegmentIndex(segments: List<HlsPlaylistParser.Segment>, posMs: Long): Int {
            var total = 0L
            for (i in segments.indices) {
                val dur = segments[i].durationMs ?: return i
                total += dur
                if (posMs < total) return i
            }
            return maxOf(0, segments.size - 1)
        }
    }

    private class Fmp4StreamProvider(
        private val sourceManager: KoeKumoSourceManager,
        private val initUrl: String,
        private val segments: List<HlsPlaylistParser.Segment>,
        private val startSegment: Int
    ) : ChainedInputStream.Provider, AutoCloseable {

        private val prefetchExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "koekumo-prefetch").apply { isDaemon = true }
        }

        private var cursor = 0
        private var pending: Future<ByteArray>? = null

        override fun next(): InputStream? {
            val url = urlFor(cursor) ?: return null

            val data: ByteArray = pending?.let {
                val bytes = it.get()
                pending = null
                bytes
            } ?: fetchBytes(url)

            cursor++

            val upcoming = urlFor(cursor)
            if (upcoming != null) {
                pending = prefetchExecutor.submit<ByteArray> { fetchBytes(upcoming) }
            }

            return ByteArrayInputStream(data)
        }

        private fun urlFor(index: Int): String? {
            if (index == 0) return initUrl
            val segIndex = startSegment + index - 1
            if (segIndex >= segments.size) return null
            return segments[segIndex].url
        }

        private fun fetchBytes(url: String): ByteArray {
            sourceManager.getHttpInterface().use { http ->
                HttpStreamTools.streamContent(http, HttpGet(url)).use { stream ->
                    return stream.readAllBytes()
                }
            }
        }

        override fun close() {
            prefetchExecutor.shutdownNow()
        }
    }
}
