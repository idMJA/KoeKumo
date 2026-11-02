plugins {
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation("dev.arbjerg:lavaplayer:${libs.versions.lavaplayer.get()}")
    implementation("org.json:json:20240303")
    implementation("org.slf4j:slf4j-api:2.0.13")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "common"
        }
    }
}
