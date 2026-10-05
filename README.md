# NordChat

> Release build and installation requirements: see [BUILDING.md](BUILDING.md).
> Older local paths below describe historical test fixtures, not the release build.

Lightweight Paper 26.2 chat controls built for Nord Fjell. It has no PacketEvents,
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

Run `build.ps1`. It compiles against the Paper API and Adventure libraries already
in an isolated local Paper fixture and creates build\NordChat-0.1.3.jar.
Version 0.1.3 uses Paper's chat renderer and one coalescing preference-storage
worker. See SECURITY-0.1.3.md for checks, persistence semantics and remaining
real-client smoke verification. No production installation has been performed.

## Installation

1. Stop the server.
2. Remove other plugins that own the same chat commands, such as SendMSG.
3. Copy the JAR to the server's `plugins` directory.
4. Start the server normally. Do not use `/reload`.
5. Add the commands to CommandWhitelist if command filtering is enabled.

