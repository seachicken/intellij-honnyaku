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

To use a local server during development, set the path to an already-built executable
in the IDE process environment (or in the `runIde` run configuration):

```sh
HONNYAKU_SERVER_PATH=/absolute/path/to/honnyaku ./gradlew runIde
```

Without `HONNYAKU_SERVER_PATH`, the plugin uses its bundled executable. The plugin
does not build the server. Distribution builds always bundle the pinned
`honnyakuVersion`; update it after releasing server changes to ship token usage.

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

## Publishing

The first Marketplace submission must be uploaded manually. Build with
`./gradlew buildPluginVariants -PpluginVersion=0.1.0-beta.1`, register the plugin
on Marketplace with one of the target ZIPs, and select the `beta` channel.
After registration, automation can upload all six variants.

Add a Marketplace personal access token as the repository Actions secret `PUBLISH_TOKEN`.
The token must have permission to update this plugin.

Push a version tag to run `.github/workflows/plugin-publish.yml`:

```sh
git tag v0.1.0-beta.1
git push origin v0.1.0-beta.1
```

The workflow builds all six native distributions and publishes them to
Marketplace. The tag overrides the plugin
version in `gradle.properties`. Tags `vX.Y.Z-beta.N`, `vX.Y.Z-alpha.N`, and
`vX.Y.Z-rc.N` select their named Marketplace channel;
`vX.Y.Z` selects the default Marketplace channel.
Always use a new version for each publication.

To install beta updates, add `https://plugins.jetbrains.com/plugins/beta/list`
under Settings → Plugins → ⚙ → Manage Plugin Repositories.

For local publishing after registration:

```sh
# Supply PUBLISH_TOKEN through your environment.
./gradlew publishPlugin -PpluginVersion=0.1.0-beta.2
```

See [JetBrains' publishing documentation](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html).
