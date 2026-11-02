# KoeKumo (声雲)

Lavalink v4 and Lavaplayer SoundCloud Audio Source written in Kotlin with native Fragmented MP4 (fMP4) HLS AAC demuxing.

## Features
- **Pure JVM Native Demuxing**: Reads and demuxes SoundCloud streams entirely within the Lavaplayer engine without external command-line helpers.
- **SoundCloud fMP4 HLS Support**: Streams modern `#EXT-X-MAP` initialization segments (`init.mp4`) and media fragments straight into Lavaplayer's native MPEG demuxer (`MpegFileLoader` + `MpegAacTrackConsumer`).
- **Dual Compatibility**: Usable as a Lavalink v4 plugin or standalone in Lavaplayer bots (JDA, Kord, etc.).
- **Dynamic Client ID Scraper**: Automatically parses the active SoundCloud `client_id` from live CDN assets with fallback resolution.
- **Short URL Expansion**: Supports `on.soundcloud.com` and `snd.sc` shortlinks.
- **Background Prefetching**: Prefetches upcoming fMP4 chunks asynchronously to eliminate segment boundary audio stutter.
- **Search Support**: Native `scsearch:` track query provider.

## Usage

### 1. Lavalink v4 Plugin
Add to your `application.yml`:
```yaml
lavalink:
  plugins:
    # Release version:
    - dependency: "moe.mja.koekumo:koekumo-plugin:x.x.x"
      repository: "https://maven.mja.moe/releases"
    # Or snapshots:
    # - dependency: "moe.mja.koekumo:koekumo-plugin:<short-commit-hash>"
    #   repository: "https://maven.mja.moe/snapshots"

  server:
    sources:
      # Disable legacy soundcloud source to prevent format conflicts
      soundcloud: false

plugins:
  koekumo:
    enabled: true
    # Optional: explicitly provide client_id (auto-scraped if omitted)
    # clientId: "your_client_id"
```

### 2. Standalone Lavaplayer
Add the repository and dependency:
```kotlin
repositories {
    maven("https://maven.mja.moe/releases")
    maven("https://maven.mja.moe/snapshots")
}

dependencies {
    implementation("moe.mja.koekumo:common:x.x.x")
    // Or snapshot commit (e.g. 7d60077):
    // implementation("moe.mja.koekumo:common:<short-commit-hash>")
}
```
Register the source manager:
```kotlin
val manager = DefaultAudioPlayerManager()
manager.registerSourceManager(KoeKumoSourceManager(KoeKumoConfig()))
```
