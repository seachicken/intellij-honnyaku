import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "intellij-honnyaku"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.20"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

dependencyResolutionManagement {
    // Configure all projects' repositories
    repositories {
        mavenCentral()
        ivy {
            name = "honnyakuReleases"
            url = uri("https://github.com/seachicken/honnyaku/releases/download")
            patternLayout {
                artifact("v[revision]/[artifact]_[revision]_[classifier].[ext]")
            }
            metadataSources { artifact() }
            content { includeGroup("honnyaku.release") }
        }

        // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform {
            defaultRepositories()
        }
    }
}
