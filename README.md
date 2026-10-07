# NordChat 0.2.0

One release JAR for Paper 26.2 and Folia 26.2: [compatibility notes](FOLIA.md).

Release build and installation requirements: see [BUILDING.md](BUILDING.md).
Version 0.1.5 fixes preference loading after restart; see [CHANGELOG.md](CHANGELOG.md).

Lightweight Paper/Folia 26.2 chat controls built for Nord Fjell. It has no PacketEvents,
ProtocolLib, Vault, LuckPerms API, database, metrics, update checker, or network calls.

## Player commands

- `/msg`, `/whisper`, `/pm`, `/w`
- `/r`, `/reply` — last incoming sender
- `/last` — last outgoing recipient
- `/ignore` — temporary ignore, expiring after the configured number of days
- `/ignorehard` — persistent ignore
- `/ignorelist` — clickable removal list
- `/ignoredeathmsgs`
- `/togglechat`
- `/toggleprivatemsgs`
- `/toggledeathmsgs` — session only
- `/toggledeathmsgshard` — persistent
- `/kill` — kills only the player who runs it; arguments are rejected

`/l` is intentionally not registered because AuthMe uses it as an alias for `/login`.
Administrators can still use the namespaced vanilla command `/minecraft:kill`.
A queue command is not included.

## Build

Run `mvn clean verify` or `./build.ps1` with Maven 3.9+ and JDK 25.
The build resolves its pinned API dependencies from Maven repositories and creates
`target/NordChat-0.1.5.jar`; no live server or old local fixture is required.
NordChat uses Paper's chat renderer and one coalescing preference-storage worker.

## Installation

1. Stop the server.
2. Remove other plugins that own the same chat commands, such as SendMSG.
3. Copy the JAR to the server's `plugins` directory.
4. Start the server normally. Do not use `/reload`.
5. Add the commands to CommandWhitelist if command filtering is enabled.

For updates, back up the existing JAR, configuration and `plugins/NordChat/players.yml`.
Install only one NordChat JAR. Keep the existing configuration and player store;
0.1.5 reads legacy empty-list aliases without resetting or rewriting settings at startup.
Do not delete `players.yml` to fix an alias-limit error.

