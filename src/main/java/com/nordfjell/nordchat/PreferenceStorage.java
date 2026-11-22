package com.nordfjell.nordchat;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CollectionEndEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.events.SequenceEndEvent;
import org.yaml.snakeyaml.events.SequenceStartEvent;

/** Immutable in-memory snapshots with incremental, batched SQLite persistence. */
final class PreferenceStorage implements AutoCloseable {
    static final int MAX_BYTES = 16 * 1024 * 1024;
    static final int MAX_IGNORES = 2048;
    static final int MAX_PENDING = 4096;
    private static final String LEGACY_MARKER = "players_yml_v1";
    private final Connection connection;
    private final Object stateLock = new Object(), writerLock = new Object();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, PlayerPreferences.Snapshot> players = new HashMap<>();
    private final Map<UUID, Long> dirty = new HashMap<>();
    private long generation, retryAt;
    private boolean overflow;
    private volatile String problem = "";

    PreferenceStorage(Path database, Path legacyYaml) throws IOException {
        try {
            Class.forName("org.sqlite.JDBC");
            Path db = database.toAbsolutePath().normalize();
            Files.createDirectories(db.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + db);
            try (Statement sql = connection.createStatement()) {
                sql.execute("PRAGMA journal_mode=WAL");
                sql.execute("PRAGMA synchronous=NORMAL");
                sql.execute("PRAGMA busy_timeout=5000");
                sql.execute("PRAGMA foreign_keys=ON");
                sql.execute("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY,name TEXT,chat_visible INTEGER NOT NULL,private_visible INTEGER NOT NULL,death_visible INTEGER NOT NULL)");
                sql.execute("CREATE TABLE IF NOT EXISTS temporary_ignores (owner_uuid TEXT NOT NULL,target_uuid TEXT NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(owner_uuid,target_uuid),FOREIGN KEY(owner_uuid) REFERENCES players(uuid) ON DELETE CASCADE)");
                sql.execute("CREATE TABLE IF NOT EXISTS hard_ignores (owner_uuid TEXT NOT NULL,target_uuid TEXT NOT NULL,PRIMARY KEY(owner_uuid,target_uuid),FOREIGN KEY(owner_uuid) REFERENCES players(uuid) ON DELETE CASCADE)");
                sql.execute("CREATE TABLE IF NOT EXISTS death_ignores (owner_uuid TEXT NOT NULL,target_uuid TEXT NOT NULL,PRIMARY KEY(owner_uuid,target_uuid),FOREIGN KEY(owner_uuid) REFERENCES players(uuid) ON DELETE CASCADE)");
                sql.execute("CREATE INDEX IF NOT EXISTS temporary_ignores_expiry ON temporary_ignores(expires_at)");
                sql.execute("CREATE TABLE IF NOT EXISTS storage_metadata (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            }
            migrateLegacy(legacyYaml.toAbsolutePath().normalize());
            load();
        } catch (ClassNotFoundException | SQLException e) { throw new IOException("Unable to open NordChat database", e); }
    }

    private void load() throws SQLException, IOException {
        Map<UUID, Map<UUID, Long>> temporary = new HashMap<>();
        Map<UUID, Set<UUID>> hard = new HashMap<>(), death = new HashMap<>();
        try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery("SELECT owner_uuid,target_uuid,expires_at FROM temporary_ignores")) {
            while (rows.next()) temporary.computeIfAbsent(uuid(rows.getString(1)), ignored -> new HashMap<>()).put(uuid(rows.getString(2)), rows.getLong(3));
        }
        loadSet("hard_ignores", hard); loadSet("death_ignores", death);
        try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery("SELECT uuid,name,chat_visible,private_visible,death_visible FROM players")) {
            while (rows.next()) {
                UUID id = uuid(rows.getString(1)); String name = rows.getString(2);
                if (name != null) names.put(id, name);
                players.put(id, new PlayerPreferences.Snapshot(rows.getInt(3) != 0, rows.getInt(4) != 0, rows.getInt(5) != 0,
                        temporary.getOrDefault(id, Map.of()), hard.getOrDefault(id, Set.of()), death.getOrDefault(id, Set.of())));
            }
        }
    }

    private void loadSet(String table, Map<UUID, Set<UUID>> target) throws SQLException, IOException {
        try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery("SELECT owner_uuid,target_uuid FROM " + table)) {
            while (rows.next()) target.computeIfAbsent(uuid(rows.getString(1)), ignored -> new HashSet<>()).add(uuid(rows.getString(2)));
        }
    }

    private void migrateLegacy(Path legacyYaml) throws IOException, SQLException {
        if (metadata(LEGACY_MARKER) != null) return;
        Legacy legacy = Files.exists(legacyYaml) ? readLegacy(legacyYaml) : new Legacy(Map.of(), Map.of());
        Set<UUID> ids = union(legacy.names.keySet(), legacy.players.keySet());
        connection.setAutoCommit(false);
        try {
            for (UUID id : ids) writePlayer(id, legacy.names.get(id), legacy.players.getOrDefault(id, defaults()));
            setMetadata(LEGACY_MARKER, Integer.toString(ids.size()));
            connection.commit();
        } catch (SQLException | RuntimeException e) { connection.rollback(); throw e; }
        finally { connection.setAutoCommit(true); }
    }

    private Legacy readLegacy(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) throw new IOException("Invalid players.yml");
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString(); }
        catch (Exception e) { throw new IOException("Invalid players.yml encoding", e); }
        try {
            Object loaded = yaml().load(normalizeEmptyAliases(text)); Map<?, ?> root = loaded == null ? Map.of() : map(loaded);
            for (Object key : root.keySet()) if (!Set.of("names", "players").contains(key)) throw new IOException("Unknown preference root");
            if ((root.containsKey("names") && root.get("names") == null) || (root.containsKey("players") && root.get("players") == null)) throw new IOException("Null preference section");
            Map<UUID, String> legacyNames = new HashMap<>();
            for (var entry : optionalMap(root.get("names")).entrySet()) {
                UUID id = uuid(entry.getKey());
                if (!(entry.getValue() instanceof String name) || !validName(name)) throw new IOException("Invalid preference name");
                if (legacyNames.put(id, name) != null) throw new IOException("Duplicate player name");
            }
            Map<UUID, PlayerPreferences.Snapshot> legacyPlayers = new HashMap<>();
            for (var entry : optionalMap(root.get("players")).entrySet()) {
                UUID id = uuid(entry.getKey()); Map<?, ?> value = map(entry.getValue());
                for (Object key : value.keySet()) if (!Set.of("chat-visible", "private-messages-visible", "death-messages-visible", "hard-ignored", "ignored-death-messages", "temporary-ignored").contains(key)) throw new IOException("Unknown preference field");
                if (value.values().stream().anyMatch(Objects::isNull)) throw new IOException("Null preference field");
                Map<UUID, Long> temporary = new HashMap<>();
                for (var ignore : optionalMap(value.get("temporary-ignored")).entrySet()) {
                    if (!(ignore.getValue() instanceof Number number) || number.longValue() <= 0) throw new IOException("Invalid ignore expiry");
                    if (temporary.put(uuid(ignore.getKey()), number.longValue()) != null) throw new IOException("Duplicate temporary ignore");
                }
                PlayerPreferences.Snapshot snapshot = new PlayerPreferences.Snapshot(flag(value,"chat-visible"), flag(value,"private-messages-visible"),
                        flag(value,"death-messages-visible"), temporary, ids(value.get("hard-ignored")), ids(value.get("ignored-death-messages")));
                validate(snapshot); if (legacyPlayers.put(id, snapshot) != null) throw new IOException("Duplicate preferences");
            }
            return new Legacy(Map.copyOf(legacyNames), Map.copyOf(legacyPlayers));
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("Invalid players.yml", e); }
    }

    private static Yaml yaml() {
        LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setCodePointLimit(MAX_BYTES);
        options.setNestingDepthLimit(30); options.setMaxAliasesForCollections(20);
        return new Yaml(new SafeConstructor(options));
    }

    /** Expands only parser-confirmed aliases to safely tagged empty collections. */
    static String normalizeEmptyAliases(String text) {
        Map<String,String> empty=new HashMap<>(); CollectionStartEvent candidate=null;
        StringBuilder output=null; int copied=0,characterCursor=0,codePointCursor=0;
        for(Event event:yaml().parse(new StringReader(text))){
            if(candidate!=null){
                boolean sequence=candidate instanceof SequenceStartEvent&&event instanceof SequenceEndEvent;
                boolean mapping=candidate instanceof MappingStartEvent&&event instanceof org.yaml.snakeyaml.events.MappingEndEvent;
                String defaultTag=sequence?"tag:yaml.org,2002:seq":"tag:yaml.org,2002:map";
                if((sequence||mapping)&&(candidate.getTag()==null||defaultTag.equals(candidate.getTag())))empty.put(candidate.getAnchor(),sequence?"[]":"{}");
                candidate=null;
            }
            if(event instanceof AliasEvent alias){
                String replacement=empty.get(alias.getAnchor()); if(replacement==null)continue;
                int startIndex=event.getStartMark().getIndex(),endIndex=event.getEndMark().getIndex();
                int start=text.offsetByCodePoints(characterCursor,startIndex-codePointCursor);
                int end=text.offsetByCodePoints(start,endIndex-startIndex);
                if(output==null)output=new StringBuilder(text.length());
                output.append(text,copied,start).append(replacement);copied=end;characterCursor=end;codePointCursor=endIndex;
            }else if(event instanceof NodeEvent node&&node.getAnchor()!=null){
                empty.remove(node.getAnchor()); if(event instanceof CollectionStartEvent collection)candidate=collection;
            }
        }
        return output==null?text:output.append(text,copied,text.length()).toString();
    }

    PlayerPreferences.Snapshot preferences(UUID id) { synchronized (stateLock) { return players.get(id); } }
    String name(UUID id) { synchronized (stateLock) { return names.getOrDefault(id,id.toString()); } }
    boolean known(UUID id) { synchronized (stateLock) { return players.containsKey(id) || names.containsKey(id); } }
    String problem() { return problem; }
    int pending() { synchronized (stateLock) { return dirty.size(); } }

    boolean submit(UUID id, String name, PlayerPreferences.Snapshot snapshot) {
        Objects.requireNonNull(id); if (name != null && !validName(name)) throw new IllegalArgumentException("Invalid player name");
        if (snapshot != null) validate(snapshot);
        synchronized (stateLock) {
            if (overflow) return false;
            boolean changed = name != null && !name.equals(names.get(id));
            changed |= snapshot != null && !snapshot.equals(players.get(id));
            if (!changed) return true;
            if (!dirty.containsKey(id) && dirty.size() >= MAX_PENDING) { overflow=true; problem="pending capacity exceeded; reconciliation required"; return false; }
            if (name != null) names.put(id,name); if (snapshot != null) players.put(id,snapshot);
            dirty.put(id,++generation); return true;
        }
    }

    boolean flush(long now, boolean force) {
        synchronized (writerLock) {
            Map<UUID,Long> captured; Map<UUID,String> capturedNames = new HashMap<>(); Map<UUID,PlayerPreferences.Snapshot> capturedPlayers = new HashMap<>();
            synchronized (stateLock) {
                if (dirty.isEmpty() || (!force && retryAt != 0 && now < retryAt)) return false;
                captured=Map.copyOf(dirty); captured.keySet().forEach(id->{capturedNames.put(id,names.get(id)); capturedPlayers.put(id,players.getOrDefault(id,defaults()));});
            }
            try {
                connection.setAutoCommit(false); for (UUID id : captured.keySet()) writePlayer(id,capturedNames.get(id),capturedPlayers.get(id)); connection.commit();
                synchronized (stateLock) { captured.forEach((id,version)->dirty.computeIfPresent(id,(ignored,current)->current.equals(version)?null:current)); retryAt=0; if(!overflow) problem=""; }
                return true;
            } catch (SQLException e) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                synchronized (stateLock) { retryAt=now+5_000_000_000L; if(!overflow) problem="database write failed; retry pending"; }
                return false;
            } finally { try { connection.setAutoCommit(true); } catch (SQLException ignored) { } }
        }
    }

    private void writePlayer(UUID id, String name, PlayerPreferences.Snapshot snapshot) throws SQLException {
        String owner=id.toString();
        try(PreparedStatement sql=connection.prepareStatement("INSERT INTO players(uuid,name,chat_visible,private_visible,death_visible) VALUES(?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=COALESCE(excluded.name,players.name),chat_visible=excluded.chat_visible,private_visible=excluded.private_visible,death_visible=excluded.death_visible")){
            sql.setString(1,owner); sql.setString(2,name); sql.setInt(3,snapshot.chatVisible()?1:0); sql.setInt(4,snapshot.privateMessagesVisible()?1:0); sql.setInt(5,snapshot.deathMessagesVisible()?1:0); sql.executeUpdate();
        }
        for(String table:List.of("temporary_ignores","hard_ignores","death_ignores"))try(PreparedStatement sql=connection.prepareStatement("DELETE FROM "+table+" WHERE owner_uuid=?")){sql.setString(1,owner);sql.executeUpdate();}
        try(PreparedStatement sql=connection.prepareStatement("INSERT INTO temporary_ignores(owner_uuid,target_uuid,expires_at) VALUES(?,?,?)")){
            for(var entry:snapshot.temporaryIgnores().entrySet()){sql.setString(1,owner);sql.setString(2,entry.getKey().toString());sql.setLong(3,entry.getValue());sql.addBatch();} sql.executeBatch();
        }
        insertSet("hard_ignores",owner,snapshot.hardIgnores()); insertSet("death_ignores",owner,snapshot.ignoredDeathMessages());
    }
    private void insertSet(String table,String owner,Set<UUID> values)throws SQLException{
        try(PreparedStatement sql=connection.prepareStatement("INSERT INTO "+table+"(owner_uuid,target_uuid) VALUES(?,?)")){for(UUID value:values){sql.setString(1,owner);sql.setString(2,value.toString());sql.addBatch();}sql.executeBatch();}
    }
    private String metadata(String key)throws SQLException{try(PreparedStatement sql=connection.prepareStatement("SELECT value FROM storage_metadata WHERE key=?")){sql.setString(1,key);try(ResultSet row=sql.executeQuery()){return row.next()?row.getString(1):null;}}}
    private void setMetadata(String key,String value)throws SQLException{try(PreparedStatement sql=connection.prepareStatement("INSERT OR REPLACE INTO storage_metadata(key,value) VALUES(?,?)")){sql.setString(1,key);sql.setString(2,value);sql.executeUpdate();}}
    private static PlayerPreferences.Snapshot defaults(){return new PlayerPreferences.Snapshot(true,true,true,Map.of(),Set.of(),Set.of());}
    private static void validate(PlayerPreferences.Snapshot value){if(value.temporaryIgnores().size()>MAX_IGNORES||value.hardIgnores().size()>MAX_IGNORES||value.ignoredDeathMessages().size()>MAX_IGNORES)throw new IllegalArgumentException("Ignore limit");}
    static boolean validName(String name){return name!=null&&name.matches("[A-Za-z0-9_.-]{1,32}");}
    private static Map<?,?> map(Object value)throws IOException{if(value instanceof Map<?,?> result)return result;throw new IOException("Expected mapping");}
    private static Map<?,?> optionalMap(Object value)throws IOException{return value==null?Map.of():map(value);}
    private static UUID uuid(Object value)throws IOException{if(!(value instanceof String text))throw new IOException("Invalid UUID type");try{return UUID.fromString(text);}catch(IllegalArgumentException e){throw new IOException("Invalid UUID",e);}}
    private static boolean flag(Map<?,?> value,String key)throws IOException{Object field=value.get(key);if(field==null&&!value.containsKey(key))return true;if(field instanceof Boolean result)return result;throw new IOException("Invalid visibility flag");}
    private static Set<UUID> ids(Object value)throws IOException{if(value==null)return Set.of();if(!(value instanceof List<?> list)||list.size()>MAX_IGNORES)throw new IOException("Invalid ignore list");Set<UUID> result=new HashSet<>();for(Object item:list)if(!result.add(uuid(item)))throw new IOException("Duplicate ignore UUID");return Set.copyOf(result);}
    private static <T> Set<T> union(Set<T> first,Set<T> second){Set<T> result=new HashSet<>(first);result.addAll(second);return result;}
    @Override public void close()throws IOException{try{connection.close();}catch(SQLException e){throw new IOException(e);}}
    private record Legacy(Map<UUID,String> names,Map<UUID,PlayerPreferences.Snapshot> players){}
}
