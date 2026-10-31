import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.file.ArchiveOperations
import javax.inject.Inject

// Inject ZIP operations without capturing the script in configuration-cache providers.
abstract class ArchiveAccess {
    @get:Inject abstract val archives: ArchiveOperations
}

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

kotlin { jvmToolchain(21) }

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.1")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}

val honnyakuVersion = providers.gradleProperty("honnyakuVersion").get()
version = providers.gradleProperty("pluginVersion").getOrElse(version.toString())

val nativeFiles = listOf("linux", "mac", "windows").flatMap { os ->
    listOf("x86_64", "arm64").map { arch ->
        val releaseOs = if (os == "mac") "darwin" else os
        val releaseArch = if (arch == "x86_64") "amd64" else arch
        val archive = configurations.create("honnyaku_${os}_${arch}") {
            isCanBeConsumed = false
            isTransitive = false
        }
        dependencies.add(archive.name, "honnyaku.release:honnyaku:$honnyakuVersion:${releaseOs}_${releaseArch}@zip")
        val archiveOperations = objects.newInstance<ArchiveAccess>().archives
        val unpack = tasks.register<Sync>("prepareHonnyaku_${os}_${arch}") {
            from(archive.elements.map { files -> files.map { archiveOperations.zipTree(it.asFile) } })
            into(layout.buildDirectory.dir("honnyaku/$os-$arch/bin"))
            filePermissions { unix("755") }
        }
        (os to arch) to unpack.map { it.destinationDir.parentFile }
    }
}.toMap()

intellijPlatform {
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf(version.toString().substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
    }
    pluginConfiguration.ideaVersion.sinceBuild = "261"
    nativeVariants {
        enabled = true
        linux.x86_64.from(nativeFiles.getValue("linux" to "x86_64"))
        linux.arm64.from(nativeFiles.getValue("linux" to "arm64"))
        mac.x86_64.from(nativeFiles.getValue("mac" to "x86_64"))
        mac.arm64.from(nativeFiles.getValue("mac" to "arm64"))
        windows.x86_64.from(nativeFiles.getValue("windows" to "x86_64"))
        windows.arm64.from(nativeFiles.getValue("windows" to "arm64"))
    }
}
