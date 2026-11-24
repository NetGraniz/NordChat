# NordChat 2.0.0

NordChat provides chat, private messages, ignore lists, death messages, moderation, and tab-list presentation for Paper and Folia.

Configuration is split by responsibility inside `plugins/NordChat`: `config.yml`, `deaths.yml`, `filter.yml`, `banwords.yml`, and `tab.yml`. Player preferences are stored in `players.db`; moderation state is stored in `punishments.db`.

NordChat 2.0.0 reads only its current files and SQLite schema.

Administration:

- `/nordchat reload`
- `/nordchat moderation health`
- `/nordchat moderation reload`
- `/nordchat moderation status <player>`
- `/nordchat moderation unmute <player>`
- `/nordchat moderation reset <player>`

Build with JDK 25 and Maven 3.9+ using `build.ps1`.

## License

Current development on `main` is licensed under [GNU GPL v3.0 only](LICENSE). The latest published release remains available under the MIT License stated on its release page.
