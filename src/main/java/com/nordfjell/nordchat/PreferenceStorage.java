package com.nordfjell.nordchat;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Main-thread submissions, immutable snapshots, one writer. No Bukkit/Player references. */
final class PreferenceStorage {
    static final int MAX_BYTES = 16 * 1024 * 1024;
    static final int MAX_PLAYERS = 50000;
    static final int MAX_IGNORES = 2048;
    static final int MAX_PENDING = 4096;
    @FunctionalInterface interface Persister { void save(Path file, byte[] bytes) throws IOException; }
    private final Path file;
    private final Persister persister;
    private final Object stateLock = new Object(), writerLock = new Object();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, PlayerPreferences.Snapshot> players = new HashMap<>();
    private final Map<UUID, Long> dirty = new HashMap<>();
    private long generation, retryAt;
    private int playerCount;
    private boolean overflow;
    private volatile String problem = "";

    PreferenceStorage(Path file) throws IOException { this(file, PreferenceStorage::atomicSave); }
    PreferenceStorage(Path file, Persister persister) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        this.persister = persister;
        load();
    }
    private static Yaml yaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(MAX_BYTES);
        options.setNestingDepthLimit(30);
        options.setMaxAliasesForCollections(20);
        return new Yaml(new SafeConstructor(options));
    }
    private void load() throws IOException {
        if (!Files.exists(file)) return; // New installation; cannot detect administrative deletion.
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) throw new IOException("Invalid preference file");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
            Object loaded = yaml().load(text);
            Map<?, ?> root = loaded == null ? Map.of() : map(loaded);
            for (Object key : root.keySet()) if (!Set.of("names", "players").contains(key))
                throw new IOException("Unknown preference root");
            if ((root.containsKey("names") && root.get("names") == null)
                    || (root.containsKey("players") && root.get("players") == null))
                throw new IOException("Null preference section");
            Map<?, ?> nameSection = optionalMap(root.get("names"));
            Map<?, ?> playerSection = optionalMap(root.get("players"));
            if (nameSection.size() > MAX_PLAYERS || playerSection.size() > MAX_PLAYERS)
                throw new IOException("Preference player limit");
            Map<UUID, String> loadedNames = new HashMap<>();
            Map<UUID, PlayerPreferences.Snapshot> loadedPlayers = new HashMap<>();
            for (var entry : nameSection.entrySet()) {
                UUID id = uuid(entry.getKey());
                if (!(entry.getValue() instanceof String name) || !validName(name))
                    throw new IOException("Invalid preference name");
                if (loadedNames.put(id, name) != null) throw new IOException("Duplicate preference account");
            }
            for (var entry : playerSection.entrySet()) {
                UUID id = uuid(entry.getKey());
                Map<?, ?> value = map(entry.getValue());
                for (Object key : value.keySet()) if (!Set.of("chat-visible", "private-messages-visible",
                        "death-messages-visible", "hard-ignored", "ignored-death-messages", "temporary-ignored").contains(key))
                    throw new IOException("Unknown player preference field");
                if (value.values().stream().anyMatch(Objects::isNull)) throw new IOException("Null player preference field");
                Map<UUID, Long> temporary = new HashMap<>();
                Map<?, ?> source = optionalMap(value.get("temporary-ignored"));
                if (source.size() > MAX_IGNORES) throw new IOException("Ignore limit");
                for (var ignore : source.entrySet()) {
                    UUID target = uuid(ignore.getKey());
                    if (!(ignore.getValue() instanceof Number number) || !(number instanceof Integer || number instanceof Long)
                            || number.longValue() <= 0) throw new IOException("Invalid ignore expiry");
                    if (temporary.put(target, number.longValue()) != null) throw new IOException("Duplicate ignore");
                }
                var snapshot = new PlayerPreferences.Snapshot(flag(value, "chat-visible"), flag(value, "private-messages-visible"),
                        flag(value, "death-messages-visible"), temporary, ids(value.get("hard-ignored")),
                        ids(value.get("ignored-death-messages")));
                if (loadedPlayers.put(id, snapshot) != null) throw new IOException("Duplicate player preferences");
            }
            Set<UUID> all = new HashSet<>(loadedNames.keySet()); all.addAll(loadedPlayers.keySet());
            if (all.size() > MAX_PLAYERS) throw new IOException("Preference player limit");
            names.putAll(loadedNames); players.putAll(loadedPlayers); playerCount = all.size();
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("Invalid preference YAML", e); }
    }
    private static Map<?, ?> map(Object value) throws IOException {
        if (!(value instanceof Map<?, ?> result)) throw new IOException("Expected preference section");
        return result;
    }
    private static Map<?, ?> optionalMap(Object value) throws IOException { return value == null ? Map.of() : map(value); }
    private static UUID uuid(Object value) throws IOException {
        if (!(value instanceof String text)) throw new IOException("Invalid UUID type");
        try {
            UUID id = UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid UUID", e); }
    }
    private static boolean flag(Map<?, ?> section, String key) throws IOException {
        Object value = section.get(key);
        if (value == null && !section.containsKey(key)) return true;
        if (!(value instanceof Boolean flag)) throw new IOException("Invalid visibility flag");
        return flag;
    }
    private static Set<UUID> ids(Object value) throws IOException {
        if (value == null) return Set.of();
        if (!(value instanceof List<?> list) || list.size() > MAX_IGNORES) throw new IOException("Invalid ignore list");
        Set<UUID> result = new HashSet<>();
        for (Object item : list) if (!result.add(uuid(item))) throw new IOException("Duplicate ignore UUID");
        return Set.copyOf(result);
    }
    static boolean validName(String name) { return name != null && name.matches("[A-Za-z0-9_.-]{1,32}"); }
    PlayerPreferences.Snapshot preferences(UUID id) {
        synchronized (stateLock) { return players.get(id); }
    }
    String name(UUID id) { synchronized (stateLock) { return names.getOrDefault(id, id.toString()); } }
    boolean known(UUID id) { synchronized (stateLock) { return players.containsKey(id) || names.containsKey(id); } }
    String problem() { return problem; }
    int pending() { synchronized (stateLock) { return dirty.size(); } }
    boolean submit(UUID id, String name, PlayerPreferences.Snapshot snapshot) {
        Objects.requireNonNull(id);
        if (name != null && !validName(name)) throw new IllegalArgumentException("Invalid player name");
        if (snapshot != null && (snapshot.temporaryIgnores().size() > MAX_IGNORES
                || snapshot.hardIgnores().size() > MAX_IGNORES || snapshot.ignoredDeathMessages().size() > MAX_IGNORES))
            throw new IllegalArgumentException("Ignore limit");
        synchronized (stateLock) {
            if (overflow) return false;
            boolean changeName = name != null && !name.equals(names.get(id));
            boolean changePreferences = snapshot != null && !snapshot.equals(players.get(id));
            if (!changeName && !changePreferences) return true;
            if ((!dirty.containsKey(id) && dirty.size() >= MAX_PENDING)
                    || (!known(id) && playerCount >= MAX_PLAYERS)) {
                overflow = true; problem = "capacity exceeded; reconciliation required"; return false;
            }
            if (!known(id)) playerCount++;
            if (changeName) names.put(id, name);
            if (changePreferences) players.put(id, snapshot);
            dirty.put(id, ++generation);
            return true;
        }
    }
    boolean flush(long now, boolean force) {
        synchronized (writerLock) {
            Map<UUID, String> capturedNames;
            Map<UUID, PlayerPreferences.Snapshot> capturedPlayers;
            long capturedGeneration;
            synchronized (stateLock) {
                if (dirty.isEmpty() || (!force && retryAt != 0 && now - retryAt < 0)) return false;
                capturedGeneration = generation;
                capturedNames = Map.copyOf(names); capturedPlayers = Map.copyOf(players);
            }
            try {
                persister.save(file, encode(capturedNames, capturedPlayers));
                synchronized (stateLock) {
                    dirty.values().removeIf(value -> value <= capturedGeneration);
                    retryAt = 0;
                    if (!overflow) problem = "";
                }
                return true;
            } catch (IOException | RuntimeException e) {
                synchronized (stateLock) {
                    retryAt = now + 5_000_000_000L;
                    if (!overflow) problem = "save failed (" + e.getClass().getSimpleName() + "); retry pending";
                }
                return false;
            }
        }
    }
    private static byte[] encode(Map<UUID, String> names, Map<UUID, PlayerPreferences.Snapshot> players) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>(), nameSection = new TreeMap<>(), playerSection = new TreeMap<>();
        names.forEach((id, name) -> nameSection.put(id.toString(), name));
        players.forEach((id, prefs) -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("chat-visible", prefs.chatVisible()); values.put("private-messages-visible", prefs.privateMessagesVisible());
            values.put("death-messages-visible", prefs.deathMessagesVisible());
            values.put("hard-ignored", prefs.hardIgnores().stream().map(UUID::toString).sorted().toList());
            values.put("ignored-death-messages", prefs.ignoredDeathMessages().stream().map(UUID::toString).sorted().toList());
            Map<String, Long> temporary = new TreeMap<>();
            prefs.temporaryIgnores().forEach((target, expiry) -> temporary.put(target.toString(), expiry));
            values.put("temporary-ignored", temporary); playerSection.put(id.toString(), values);
        });
        root.put("names", nameSection); root.put("players", playerSection);
        byte[] bytes = yaml().dump(root).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Preference file limit");
        return bytes;
    }
    private static void atomicSave(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "nordchat-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
