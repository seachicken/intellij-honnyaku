import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.tasks.bundling.Zip

plugins {
    id("org.jetbrains.kotlin.jvm")
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

version = providers.gradleProperty("pluginVersion").getOrElse(version.toString())

tasks.withType<Zip>().configureEach {
    filesMatching("**/bin/honnyaku") {
        permissions { unix("755") }
    }
}

intellijPlatform {
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf(version.toString().substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
    }
    pluginConfiguration.ideaVersion.sinceBuild = "261"
    pluginConfiguration.changeNotes = """
        <h2>Features</h2>
        <ul>
            <li>Translate visible Markdown, plain text, and code comments while the Honnyaku tool window is open</li>
            <li>View translations alongside the source with synchronized scrolling and persistent results</li>
            <li>Select a translation model and view reasoning effort, translation progress, and token usage</li>
            <li>Stop translation automatically when the tool window is hidden</li>
        </ul>
    """.trimIndent()
    nativeVariants {
        enabled = true
        linux.x86_64.from(layout.buildDirectory.dir("honnyaku/linux-x86_64"))
        linux.arm64.from(layout.buildDirectory.dir("honnyaku/linux-arm64"))
        mac.x86_64.from(layout.buildDirectory.dir("honnyaku/mac-x86_64"))
        mac.arm64.from(layout.buildDirectory.dir("honnyaku/mac-arm64"))
        windows.x86_64.from(layout.buildDirectory.dir("honnyaku/windows-x86_64"))
        windows.arm64.from(layout.buildDirectory.dir("honnyaku/windows-arm64"))
    }
}
