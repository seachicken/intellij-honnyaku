import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.changelog.markdownToHTML

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog") version "2.5.0"
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
    pluginConfiguration.description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
        val start = "<!-- Plugin description -->"
        val end = "<!-- Plugin description end -->"
        val lines = it.lines()
        val startIndex = lines.indexOf(start)
        val endIndex = lines.indexOf(end)

        if (startIndex < 0 || endIndex <= startIndex) {
            throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
        }
        lines.subList(startIndex + 1, endIndex).joinToString("\n").let(::markdownToHTML)
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf(version.toString().substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
    }
    pluginConfiguration.ideaVersion.sinceBuild = "261"
    pluginConfiguration.changeNotes = """
        <h2>Improvements</h2>
        <ul>
            <li>Add support for translating AsciiDoc files</li>
            <li>Add a retranslate action to clear cached translations for the current file and preview language</li>
            <li>Keep language, model, and token usage controls accessible through overflow menus in narrow tool windows</li>
            <li>Improve translation request recovery when the translation server stops</li>
            <li>Update bundled Honnyaku to 0.2.2</li>
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
