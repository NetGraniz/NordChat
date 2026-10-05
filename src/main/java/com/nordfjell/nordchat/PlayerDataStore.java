package com.nordfjell.nordchat;

import java.nio.file.Path;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Online cache stays on the main thread; the owned worker never touches Bukkit/YAML state. */
final class PlayerDataStore {
    private final NordChatPlugin plugin;
    private final PreferenceStorage storage;
    private final Map<UUID, PlayerPreferences> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService writer;
    private String reportedProblem = "";

    PlayerDataStore(NordChatPlugin plugin) {
        this.plugin = plugin;
        PreferenceStorage initialized;
        try { initialized = new PreferenceStorage(Path.of(plugin.getDataFolder().getPath(), "players.yml")); }
        catch (IOException e) {
            plugin.getLogger().severe("Invalid/unreadable players.yml; chat and preference commands blocked. "
                    + "Repair the file offline and restart. File not overwritten. " + e.getClass().getSimpleName());
            initialized = null;
        }
        storage = initialized;
        if (storage == null) { writer = null; return; }
        writer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "NordChat-preference-storage");
            thread.setDaemon(true);
            return thread;
        });
        writer.scheduleWithFixedDelay(() -> {
            storage.flush(System.nanoTime(), false);
            String problem = storage.problem();
            if (!problem.equals(reportedProblem)) {
                if (!problem.isEmpty()) plugin.getLogger().severe("Preference storage: " + problem);
                else plugin.getLogger().info("Preference storage recovered.");
                reportedProblem = problem;
            }
        }, 500, 500, TimeUnit.MILLISECONDS);
    }
    boolean available() { return storage != null; }
    boolean canChange() { return available() && storage.problem().isEmpty(); }
    PlayerPreferences load(UUID playerId, String currentName) {
        if (!available()) throw new IllegalStateException("Preference storage unavailable");
        PlayerPreferences existing = cache.get(playerId);
        if (existing != null) {
            storage.submit(playerId, currentName, null);
            return existing;
        }
        PlayerPreferences preferences = cache.computeIfAbsent(playerId, id -> {
            var saved = storage.preferences(id);
            return saved == null ? new PlayerPreferences() : PlayerPreferences.from(saved);
        });
        storage.submit(playerId, currentName, preferences.snapshot());
        return preferences;
    }
    PlayerPreferences getLoaded(UUID playerId) { return cache.get(playerId); }
    void rememberName(UUID playerId, String name) {
        if (available() && PreferenceStorage.validName(name)) storage.submit(playerId, name, null);
    }
    String knownName(UUID playerId) { return available() ? storage.name(playerId) : playerId.toString(); }
    boolean known(UUID playerId) { return available() && storage.known(playerId); }
    void unload(UUID playerId) { cache.remove(playerId); } // Every mutation is already submitted; no quit-time file write.
    boolean save(UUID playerId) {
        PlayerPreferences preferences = cache.get(playerId);
        return preferences != null && storage.submit(playerId, null, preferences.snapshot());
    }
    void saveAll() {
        if (writer == null) return;
        cache.forEach((id, prefs) -> storage.submit(id, null, prefs.snapshot()));
        writer.execute(() -> storage.flush(System.nanoTime(), true));
        writer.shutdown();
        try {
            if (!writer.awaitTermination(2, TimeUnit.SECONDS)) {
                plugin.getLogger().severe("Preference writer did not finish within 2 seconds; pending changes may be lost.");
                writer.shutdownNow();
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); writer.shutdownNow(); }
        if (storage.pending() != 0) plugin.getLogger().severe("Unsaved preference changes remain: " + storage.pending());
    }
}

