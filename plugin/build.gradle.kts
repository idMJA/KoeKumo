plugins {
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.lavalink)
}

lavalinkPlugin {
    name = "KoeKumo-plugin"
    apiVersion = libs.versions.lavalink.api
    serverVersion = libs.versions.lavalink.server
}

dependencies {
    implementation(project(":common"))
    implementation("org.json:json:20240303")
}

tasks.jar {
    archiveBaseName.set("koekumo-plugin")
    dependsOn(":common:compileTestKotlin")
}

publishing {
    publications {
        afterEvaluate {
            named<MavenPublication>("maven") {
                artifactId = "koekumo-plugin"
            }
        }
    }
}
