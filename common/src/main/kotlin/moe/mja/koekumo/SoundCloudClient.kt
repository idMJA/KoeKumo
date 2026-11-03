package moe.mja.koekumo

import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import org.apache.http.client.methods.HttpGet
import org.apache.http.util.EntityUtils
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

class SoundCloudClient(private val config: KoeKumoConfig) {
    private val log = LoggerFactory.getLogger(SoundCloudClient::class.java)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val soundCloudBase = "https://soundcloud.com"
    private val apiBase = "https://api-v2.soundcloud.com"

    private val clientIdPattern = Pattern.compile("(?:[?&/]?(?:client_id)[\\s:=&]*\"?|\"data\":\\{\"id\":\")([A-Za-z0-9]{32})\"?")
    private val assetPattern = Pattern.compile("https://a-v2\\.sndcdn\\.com/assets/[a-zA-Z0-9-]+\\.js")

    @Volatile
    private var cachedClientId: String? = null

    fun getClientId(http: HttpInterface): String {
        config.clientId?.takeIf { it.isNotBlank() }?.let { return it }

        cachedClientId?.let { return it }

        synchronized(this) {
            cachedClientId?.let { return it }

            val req = HttpGet(soundCloudBase).apply {
                setHeader("User-Agent", userAgent)
                setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            }

            val html = http.execute(req).use { resp ->
                EntityUtils.toString(resp.entity, StandardCharsets.UTF_8)
            }

            val directMatcher = clientIdPattern.matcher(html)
            if (directMatcher.find()) {
                val found = directMatcher.group(1)
                cachedClientId = found
                log.info("Discovered SoundCloud client_id directly: {}", found)
                return found
            }

            val assetMatcher = assetPattern.matcher(html)
            val scriptUrls = mutableListOf<String>()
            while (assetMatcher.find()) {
                scriptUrls.add(assetMatcher.group())
            }

            for (scriptUrl in scriptUrls.asReversed()) {
                try {
                    val scriptReq = HttpGet(scriptUrl).apply {
                        setHeader("User-Agent", userAgent)
                    }
                    val js = http.execute(scriptReq).use { resp ->
                        EntityUtils.toString(resp.entity, StandardCharsets.UTF_8)
                    }
                    val m = clientIdPattern.matcher(js)
                    if (m.find()) {
                        val found = m.group(1)
                        cachedClientId = found
                        log.info("Discovered SoundCloud client_id from asset ({}): {}", scriptUrl, found)
                        return found
                    }
                } catch (e: Exception) {
                    log.debug("Failed to inspect asset script {}", scriptUrl, e)
                }
            }

            throw IllegalStateException("Failed to scrape SoundCloud client_id")
        }
    }

    fun resolve(http: HttpInterface, url: String): JSONObject? {
        val normalizedUrl = normalizeUrl(http, url)
        val cid = getClientId(http)
        val target = "$apiBase/resolve?url=${URLEncoder.encode(normalizedUrl, StandardCharsets.UTF_8)}&client_id=$cid"
        val firstAttempt = getJson(http, target)
        if (firstAttempt != null) return firstAttempt

        // If SoundCloud rotated or invalidated client_id, invalidate cache and retry once
        log.warn("SoundCloud resolve returned null/401/404, invalidating cached client_id and retrying...")
        cachedClientId = null
        val freshCid = getClientId(http)
        val retryTarget = "$apiBase/resolve?url=${URLEncoder.encode(normalizedUrl, StandardCharsets.UTF_8)}&client_id=$freshCid"
        return getJson(http, retryTarget)
    }

    private fun normalizeUrl(http: HttpInterface, rawUrl: String): String {
        val expanded = expandShortUrl(http, rawUrl)
        return expanded.replace("://m.soundcloud.com/", "://soundcloud.com/")
    }

    fun search(http: HttpInterface, query: String, limit: Int = 10): List<JSONObject> {
        val cid = getClientId(http)
        val target = "$apiBase/search/tracks?q=${URLEncoder.encode(query, StandardCharsets.UTF_8)}&client_id=$cid&limit=$limit"
        val json = getJson(http, target) ?: return emptyList()
        val collection = json.optJSONArray("collection") ?: return emptyList()
        val list = mutableListOf<JSONObject>()
        for (i in 0 until collection.length()) {
            val item = collection.optJSONObject(i)
            if (item != null && item.optString("kind") == "track") {
                list.add(item)
            }
        }
        return list
    }

    fun getStreamUrl(http: HttpInterface, trackJson: JSONObject): String? {
        val cid = getClientId(http)
        val media = trackJson.optJSONObject("media") ?: return null
        val transcodings = media.optJSONArray("transcodings") ?: return null

        var chosenUrl: String? = null
        for (i in 0 until transcodings.length()) {
            val t = transcodings.getJSONObject(i)
            val format = t.optJSONObject("format")
            val protocol = format?.optString("protocol")
            val mimeType = format?.optString("mime_type")

            if ("hls".equals(protocol, ignoreCase = true) && mimeType?.contains("audio/mp4") == true) {
                chosenUrl = t.optString("url")
                break
            }
        }

        if (chosenUrl == null) {
            for (i in 0 until transcodings.length()) {
                val t = transcodings.getJSONObject(i)
                if ("hls".equals(t.optJSONObject("format")?.optString("protocol"), ignoreCase = true)) {
                    chosenUrl = t.optString("url")
                    break
                }
            }
        }

        if (chosenUrl.isNullOrBlank()) return null

        val streamInfoReq = "$chosenUrl?client_id=$cid"
        val streamData = getJson(http, streamInfoReq) ?: return null
        return streamData.optString("url").takeIf { it.isNotBlank() }
    }

    private fun expandShortUrl(http: HttpInterface, url: String): String {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
        if (host == "on.soundcloud.com" || host == "snd.sc") {
            var current = url
            for (i in 0 until 5) {
                val req = HttpGet(current).apply {
                    setHeader("User-Agent", userAgent)
                    config = org.apache.http.client.config.RequestConfig.custom()
                        .setRedirectsEnabled(false)
                        .build()
                }
                val redirected = http.execute(req).use { resp ->
                    val status = resp.statusLine.statusCode
                    if (status in 300..399) {
                        resp.getFirstHeader("Location")?.value
                    } else null
                } ?: break
                current = redirected
                val curHost = runCatching { URI(current).host?.lowercase() }.getOrNull()
                if (curHost == "soundcloud.com" || curHost == "www.soundcloud.com" || curHost == "m.soundcloud.com") {
                    break
                }
            }
            log.info("Expanded short URL {} -> {}", url, current)
            return current
        }
        return url
    }

    private fun getJson(http: HttpInterface, url: String): JSONObject? {
        val req = HttpGet(url).apply {
            setHeader("User-Agent", userAgent)
            setHeader("Accept", "application/json, text/javascript, */*; q=0.01")
            setHeader("Origin", soundCloudBase)
            setHeader("Referer", "$soundCloudBase/")
        }
        return http.execute(req).use { resp ->
            val status = resp.statusLine.statusCode
            if (status in 200..299) {
                val body = EntityUtils.toString(resp.entity, StandardCharsets.UTF_8)
                JSONObject(body)
            } else {
                log.warn("SoundCloud API returned status {} for {}", status, url)
                null
            }
        }
    }
}
