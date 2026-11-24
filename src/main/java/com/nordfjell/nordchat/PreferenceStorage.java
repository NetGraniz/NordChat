package com.nordfjell.nordchat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Immutable in-memory snapshots with incremental, batched SQLite persistence. */
final class PreferenceStorage implements AutoCloseable {
    static final int MAX_IGNORES = 2048;
    static final int MAX_PENDING = 4096;
    private final Connection connection;
    private final Object stateLock = new Object(), writerLock = new Object();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, PlayerPreferences.Snapshot> players = new HashMap<>();
    private final Map<UUID, Long> dirty = new HashMap<>();
    private long generation, retryAt;
    private boolean overflow;
    private volatile String problem = "";

    PreferenceStorage(Path database) throws IOException {
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
            }
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
    private static PlayerPreferences.Snapshot defaults(){return new PlayerPreferences.Snapshot(true,true,true,Map.of(),Set.of(),Set.of());}
    private static void validate(PlayerPreferences.Snapshot value){if(value.temporaryIgnores().size()>MAX_IGNORES||value.hardIgnores().size()>MAX_IGNORES||value.ignoredDeathMessages().size()>MAX_IGNORES)throw new IllegalArgumentException("Ignore limit");}
    static boolean validName(String name){return name!=null&&name.matches("[A-Za-z0-9_.-]{1,32}");}
    private static UUID uuid(Object value)throws IOException{if(!(value instanceof String text))throw new IOException("Invalid UUID type");try{return UUID.fromString(text);}catch(IllegalArgumentException e){throw new IOException("Invalid UUID",e);}}
    @Override public void close()throws IOException{try{connection.close();}catch(SQLException e){throw new IOException(e);}}
}
