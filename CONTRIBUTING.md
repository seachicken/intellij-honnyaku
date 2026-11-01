# Contributing

## Versions

Manage versions in `gradle.properties`:

- `version`: IntelliJ plugin version.
- `honnyakuVersion`: bundled Honnyaku release version, without the `v` prefix.

## Development

```sh
./gradlew runIde
```

The plugin uses the bundled Honnyaku server by default. To use a local server,
set `HONNYAKU_SERVER_PATH` to its executable:

```sh
HONNYAKU_SERVER_PATH=/absolute/path/to/honnyaku ./gradlew runIde
```

## Distribution

```sh
./gradlew buildPluginVariants
```

Platform-specific plugin archives are written to `build/distributions/`.
Use these archives for installation and distribution.

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
