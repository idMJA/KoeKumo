import org.ajoberstar.grgit.Grgit

plugins {
    `maven-publish`
    id("org.ajoberstar.grgit") version "5.2.2"
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.lavalink) apply false
}

val (projectVersion, isRelease) = versionFromGit()

allprojects {
    group = "moe.mja.koekumo"
    version = projectVersion

    repositories {
        mavenCentral()
        maven(url = "https://maven.mja.moe/releases")
        maven(url = "https://maven.mja.moe/snapshots")
        maven(url = "https://maven.lavalink.dev/releases")
        maven(url = "https://jitpack.io")
    }
}

subprojects {
    apply(plugin = "maven-publish")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(17)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    publishing {
        val mavenUsername = (findProperty("MAVEN_USERNAME") ?: System.getenv("MAVEN_USERNAME")) as String?
        val mavenPassword = (findProperty("MAVEN_PASSWORD") ?: System.getenv("MAVEN_PASSWORD")) as String?

        if (!mavenUsername.isNullOrEmpty() && !mavenPassword.isNullOrEmpty()) {
            repositories {
                val snapshots = "https://maven.mja.moe/snapshots"
                val releases = "https://maven.mja.moe/releases"
                maven(if (isRelease) releases else snapshots) {
                    credentials {
                        username = mavenUsername
                        password = mavenPassword
                    }
                }
            }
        } else {
            logger.lifecycle("Not publishing ${project.name} to maven.mja.moe because credentials are not set")
        }
    }
}

fun versionFromGit(): Pair<String, Boolean> {
    Grgit.open(mapOf("currentDir" to project.rootDir)).use { git ->
        val headTag = git.tag
            .list()
            .find { it.commit.id == git.head().id }

        val clean = git.status().isClean || System.getenv("CI") != null
        if (!clean) {
            logger.lifecycle("Git state is dirty, version is a snapshot.")
        }

        val shortCommit = git.head().id.take(7)
        return if (headTag != null && clean) headTag.name to true else shortCommit to false
    }
}
