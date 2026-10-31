# Contributing

## Versions

Manage versions in `gradle.properties`:

- `version`: IntelliJ plugin version.
- `honnyakuVersion`: bundled Honnyaku release version, without the `v` prefix.

## Development

```sh
./gradlew runIde
```

Gradle downloads the pinned release archive for the current host and puts its executable,
README, and license under the sandbox plugin's `bin/` directory. The plugin starts that
bundled executable over stdio; no separate Honnyaku installation is needed.

## Build platform-specific distributions

```sh
./gradlew buildPluginVariants
```

This downloads the six release ZIPs and builds six plugin archives under `build/distributions/`:
macOS, Linux, and Windows, each for x86_64 and ARM64. Each archive contains only its target's
executable. Gradle's `nativeVariants` adds OS/architecture dependencies to `plugin.xml` and
suffixes the version, allowing Marketplace to deliver the matching archive.

To build just one target:

```sh
./gradlew buildPluginVariants_mac_arm64
```

Use the variant archives for installation and distribution. The ordinary `buildPlugin`
archive is platform-independent and does not contain a Honnyaku executable.

`publishPlugin` builds and uploads all configured variants when Marketplace credentials
are configured. Building does not publish anything.

See [JetBrains' OS/architecture-specific version documentation](https://plugins.jetbrains.com/docs/marketplace/os-architecture-specific-plugin-versions.html).
