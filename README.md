# NordChat

Chat, private messages, ignore controls, death messages, and moderation in one Paper/Folia plugin.

NordChat 0.4.0 replaces the standalone NordDeaths and NordFilter plugins. Component settings remain separated inside one directory:

- `plugins/NordChat/config.yml` — chat and private-message settings
- `plugins/NordChat/deaths.yml` — death-message templates
- `plugins/NordChat/filter.yml` — spam, language, and punishment policy
- `plugins/NordChat/banwords.yml` — blocked words
- `plugins/NordChat/players.db` — chat preferences and ignore data
- `plugins/NordChat/punishments.db` — moderation state

On first start, missing component files and databases are copied from the old `plugins/NordDeaths` and `plugins/NordFilter` directories. Existing SQLite and YAML data are preserved.

## Commands

Chat commands retain their existing names and permissions. Administration commands are:

- `/nordchat reload` — reload chat, death-message, and filter settings
- `/norddeaths reload` — reload only `deaths.yml`
- `/nordfilter health|reload|status|unmute|reset`

The old command names remain available so operator workflows do not change after the merge.

## Platform

- Java 25
- Paper/Folia 26.2
- SQLite JDBC is declared as a Paper runtime library
- Player-owned checks use entity scheduling; database work uses bounded background writers

See [BUILDING.md](BUILDING.md) for the verified build command.
