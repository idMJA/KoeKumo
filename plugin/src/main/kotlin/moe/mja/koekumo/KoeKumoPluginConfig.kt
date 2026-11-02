package moe.mja.koekumo

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "plugins.koekumo")
class KoeKumoPluginConfig : KoeKumoConfig()
