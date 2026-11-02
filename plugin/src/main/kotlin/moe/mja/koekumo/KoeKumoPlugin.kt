package moe.mja.koekumo

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import dev.arbjerg.lavalink.api.AudioPlayerManagerConfiguration
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class KoeKumoPlugin(
    private val config: KoeKumoPluginConfig
) : AudioPlayerManagerConfiguration {

    private val log = LoggerFactory.getLogger(KoeKumoPlugin::class.java)

    init {
        log.info("KoeKumo (声雲) SoundCloud Plugin initialized! (enabled: {})", config.enabled)
    }

    override fun configure(manager: AudioPlayerManager): AudioPlayerManager {
        if (!config.enabled) {
            log.info("KoeKumo plugin is disabled in application.yml")
            return manager
        }

        log.info("Registering KoeKumo native fMP4 SoundCloud source manager...")
        manager.registerSourceManager(KoeKumoSourceManager(config))
        return manager
    }
}
