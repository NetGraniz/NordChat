# NordChat 0.2.0

Chat controls and private messaging for Paper 26.2 and Folia 26.2, Java 25. Both platforms use the same JAR from `main`.

NordChat uses Paper's chat renderer. It has no external runtime plugin dependency, database, telemetry, update checker or network requests. A single coalescing worker saves player preferences.

## Commands and permissions

| Command | Permission | Default |
| --- | --- | --- |
| `/msg <player> <message>` (`/whisper`, `/pm`, `/w`) | `nordchat.msg` | Everyone |
| `/reply <message>` (`/r`), reply to the last incoming message | `nordchat.reply` | Everyone |
| `/last <message>`, message the last outgoing recipient | `nordchat.last` | Everyone |
| `/ignore <player>`, toggle an ignore that expires after the configured number of days | `nordchat.ignore` | Everyone |
| `/ignorehard <player>`, toggle a persistent ignore | `nordchat.ignorehard` | Everyone |
| `/ignorelist`, list entries with clickable removal | `nordchat.ignorelist` | Everyone |
| `/ignoredeathmsgs <player>` | `nordchat.ignoredeathmsgs` | Everyone |
| `/togglechat` | `nordchat.togglechat` | Everyone |
| `/toggleprivatemsgs` | `nordchat.toggleprivatemsgs` | Everyone |
| `/toggledeathmsgs`, change the current session | `nordchat.toggledeathmsgs` | Everyone |
| `/toggledeathmsgshard`, save the preference | `nordchat.toggledeathmsgshard` | Everyone |
| `/kill`, kill only yourself; arguments are rejected | `nordchat.kill` | Everyone |
| `/nordchat reload` | `nordchat.admin` | Operators |

## Permissions

The table above lists every registered permission. Explicitly deny a player permission to disable that feature; allowlisting a command in a command filter does not grant its permission.

NordChat does not register `/l`, which avoids a conflict with login aliases. Administrators who need vanilla targeting must use `/minecraft:kill` with its separate vanilla permission. No queue command is registered.

## Build and installation

Use Maven 3.9+ and JDK 25:

```text
mvn clean verify
```

On PowerShell, `./build.ps1` runs the release build. The output is `target/NordChat-0.2.0.jar`. See [BUILDING.md](BUILDING.md) and [FOLIA.md](FOLIA.md).

Stop the server before installation. Remove conflicting command owners, install one NordChat JAR and restart normally; do not use `/reload`. Add the player commands you want to expose to your command filter.

Back up the existing JAR and retain `config.yml` and `plugins/NordChat/players.yml`. The 0.1.5 update accepts legacy empty alias lists without resetting preferences or rewriting installed configuration. Do not delete `players.yml` to fix an alias-limit error. See [CHANGELOG.md](CHANGELOG.md) for that preference-loading fix.

