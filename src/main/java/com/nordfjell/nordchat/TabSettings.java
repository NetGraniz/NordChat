package com.nordfjell.nordchat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

record TabSettings(String header, String footer, boolean plainPlayerNames, long intervalTicks) {
    private static final Set<String> KEYS = Set.of("header", "footer", "update-interval-ticks", "plain-player-names");

    static TabSettings load(Path path) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(path.toFile()); }
        catch (InvalidConfigurationException error) { throw new IOException("Invalid tab.yml", error); }
        if (!yaml.getKeys(false).equals(KEYS)) throw new IOException("tab.yml must contain exactly " + KEYS);
        String header = required(yaml, "header");
        String footer = required(yaml, "footer");
        long interval = yaml.getLong("update-interval-ticks", -1);
        if (interval < 20 || interval > 1200) throw new IOException("update-interval-ticks must be between 20 and 1200");
        if (!yaml.isBoolean("plain-player-names")) throw new IOException("plain-player-names must be boolean");
        try { MiniMessage.miniMessage().deserialize(header); MiniMessage.miniMessage().deserialize(footer); }
        catch (RuntimeException error) { throw new IOException("Invalid MiniMessage in tab.yml", error); }
        return new TabSettings(header, footer, yaml.getBoolean("plain-player-names"), interval);
    }

    private static String required(YamlConfiguration yaml, String key) throws IOException {
        if (!yaml.isString(key)) throw new IOException(key + " must be a string");
        String value = yaml.getString(key, "");
        if (value.isBlank() || value.length() > 4096) throw new IOException(key + " must contain 1 to 4096 characters");
        return value;
    }
}
