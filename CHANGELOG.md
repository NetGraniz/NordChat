# Changes

## 0.3.0

- Move player names, visibility preferences, and ignore relations to indexed SQLite storage.
- Import the existing `players.yml` once without modifying it.
- Coalesce changed players into bounded background transactions instead of rewriting the entire roster.

## 0.1.5

- Fix preference saves sharing a single empty ignore-list container and emitting excessive YAML aliases. A store with 1,000 players now round-trips through repeated save/restart checks without aliases.
- Read existing stores containing many aliases to empty sequences or mappings without rewriting or resetting player settings. Only parser-identified, safely tagged empty collections are expanded in memory; limits for non-empty/recursive aliases, unsafe tags, malformed data, file size, nesting and schema remain enforced.
- Cover legacy 183-player stores, Unicode comments, quoted strings, redefined anchors and malicious/non-empty alias cases in regression tests.
- Add an isolated Paper restart smoke test with synthetic preferences and two protocol clients for public/private delivery and persisted visibility.

Back up `players.yml` and configuration before installing. No manual deletion, clearing of settings or increase of YAML security limits is required. Existing chat visibility, private-message/death-message settings, names and ignores remain in place. A subsequent normal save writes independent lists.
