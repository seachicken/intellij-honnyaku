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
