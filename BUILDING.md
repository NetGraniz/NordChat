# Building NordChat

The supported release build uses Maven 3.9+ and JDK 25 on Windows, Linux or macOS.
Set JAVA_HOME to your own JDK 25 installation and put Maven on PATH. No live server
folder, private configuration, prebuilt old plugin, or machine-specific path is required.

## Build and tests

From this project's root, run `mvn clean verify`, or on PowerShell run
`./build.ps1`. The wrapper accepts `-MavenCommand /path/to/mvn`.
The JAR is `target/NordChat-0.1.5.jar`.
Existing main-based regression checks are run by the JUnit adapter with assertions enabled.


Only plugin metadata resources are filtered for the release version. Configuration
templates are copied unchanged. API libraries are provided by Paper and
are not bundled. The build pins Paper API 26.2 build 129 rather than depending on
a live server's library directory. This release requires JDK 25 and targets Paper 26.2.

The older README and test-support fixtures may describe historical local
integration environments. BUILDING.md and pom.xml define the release build;
test-support is not packaged in the plugin JAR.

## Isolated restart smoke test

`test-support/alias-restart-smoke.cjs` exercises a fresh, isolated Paper 26.2
runtime with 183 synthetic legacy preferences and two protocol clients.
It checks public/private delivery, visibility persistence, alias-free saves and
a cold restart. It must never receive a production directory or real player data.

Prepare a fresh directory containing an accepted EULA, the Paper runtime and only
the built NordChat JAR in `plugins`. Ensure local port 31566 is free. Install the
pinned 26.2-compatible Mineflayer dependencies described by the NordLoadTest project,
including its reviewed `prepare-data` step. Then run:

```
node test-support/alias-restart-smoke.cjs /path/to/fresh-runtime /path/to/java /path/to/mineflayer
```

The test generates its own configuration, binds to localhost only, stops both
Paper runs gracefully, and leaves logs and `smoke-result.json` in the test directory.
The older `integration.cjs` fixture is historical and is not this restart check.

## Server settings

Install the JAR on a stopped server, start it to create its default files, then
configure your own server values. Do not publish installed config files or player
stores. Existing configuration must be backed up and reviewed before updating.
No deployment or server configuration change is performed by the build.
