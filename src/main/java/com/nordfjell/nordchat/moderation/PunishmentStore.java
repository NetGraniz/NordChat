package com.nordfjell.nordchat.moderation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** In-memory read snapshot with incremental transactional SQLite writes. */
final class PunishmentStore {
    static final int MAX_PENDING = 4096;
    static final PlayerState EMPTY = new PlayerState(0, 0, 0);

    record PlayerState(int level, long muteUntil, long lastViolation) {
        PlayerState { if (level < 0 || muteUntil < 0 || lastViolation < 0) throw new IllegalArgumentException("Invalid punishment"); }
        PlayerState withoutMute() { return new PlayerState(level, 0, lastViolation); }
        PlayerState withViolation(int nextLevel, long until, long now) { return new PlayerState(nextLevel, until, now); }
        static PlayerState stricter(PlayerState a, PlayerState b) {
            return new PlayerState(Math.max(a.level, b.level), Math.max(a.muteUntil, b.muteUntil), Math.max(a.lastViolation, b.lastViolation));
        }
    }
    private record Change(PlayerState state) { }
    private final Path database;
    private final Object pendingLock = new Object(), writerLock = new Object();
    private volatile Map<UUID, PlayerState> committed = Map.of();
    private final Map<UUID, Change> pending = new LinkedHashMap<>();
    private final Map<UUID, PlayerState> heldRestrictions = new HashMap<>();
    private volatile String problem = "";
    private boolean overflow, closed;
    private long retryAt;

    PunishmentStore(Path database) throws IOException {
        this.database = database.toAbsolutePath().normalize();
        initializeAndLoad();
    }
    private void initializeAndLoad() throws IOException {
        try (Connection c = open()) {
            initialize(c);
            Map<UUID, PlayerState> loaded = new HashMap<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT player_uuid,level,mute_until,last_violation FROM punishments")) {
                while (r.next()) loaded.put(UUID.fromString(r.getString(1)), new PlayerState(r.getInt(2), r.getLong(3), r.getLong(4)));
            }
            committed = Map.copyOf(loaded);
        } catch (SQLException | RuntimeException e) { throw new IOException("Invalid/unreadable punishment database", e); }
    }
    PlayerState get(UUID id) { synchronized (pendingLock) {
        PlayerState durable=committed.getOrDefault(id,EMPTY), held=heldRestrictions.get(id);
        return held==null?durable:PlayerState.stricter(durable,held);
    }}
    PlayerState desired(UUID id) { synchronized (pendingLock) {
        Change change=pending.get(id); return change==null?committed.getOrDefault(id,EMPTY):change.state==null?EMPTY:change.state;
    }}
    boolean put(UUID id, PlayerState state) { return offer(id,new Change(Objects.requireNonNull(state))); }
    boolean reset(UUID id) { return offer(id,new Change(null)); }
    private boolean offer(UUID id,Change change) { Objects.requireNonNull(id); synchronized(pendingLock) {
        if(closed||overflow)return false;
        Change previous=pending.get(id);
        if(previous!=null&&Objects.equals(previous.state,change.state))return true;
        if(previous==null&&Objects.equals(committed.get(id),change.state))return true;
        if(previous==null&&!committed.containsKey(id)&&EMPTY.equals(change.state))return true;
        if(previous==null&&pending.size()>=MAX_PENDING){overflow=true;problem="pending capacity exceeded; reconciliation required";return false;}
        if(change.state!=null)heldRestrictions.merge(id,change.state,PlayerState::stricter);
        pending.put(id,change);return true;
    }}
    int pruneExpired(long now,long resetMillis) {
        int changed=0;
        for(var entry:committed.entrySet()){
            PlayerState state=entry.getValue();
            if(state.equals(EMPTY)){
                if(reset(entry.getKey()))changed++;
            }else if(state.muteUntil>0&&state.muteUntil<=now){
                boolean accepted=state.lastViolation<=now-resetMillis?reset(entry.getKey()):put(entry.getKey(),state.withoutMute());
                if(accepted)changed++;
            }else if(state.muteUntil==0&&state.lastViolation>0&&state.lastViolation<=now-resetMillis&&reset(entry.getKey()))changed++;
        }
        return changed;
    }
    int pendingCount(){synchronized(pendingLock){return pending.size();}}
    int recordCount(){return committed.size();}
    boolean known(UUID id){synchronized(pendingLock){return committed.containsKey(id)||pending.containsKey(id);}}
    String problem(){return problem;}
    boolean available(){synchronized(pendingLock){return !closed&&problem.isEmpty();}}
    boolean flush(long now,boolean force){synchronized(writerLock){
        Map<UUID,Change> batch;
        synchronized(pendingLock){if(pending.isEmpty()||(!force&&retryAt!=0&&now-retryAt<0))return false;batch=new LinkedHashMap<>(pending);}
        try(Connection c=open()){
            c.setAutoCommit(false);
            try(PreparedStatement upsert=c.prepareStatement("INSERT INTO punishments(player_uuid,level,mute_until,last_violation) VALUES (?,?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET level=excluded.level,mute_until=excluded.mute_until,last_violation=excluded.last_violation");
                PreparedStatement delete=c.prepareStatement("DELETE FROM punishments WHERE player_uuid=?")){
                for(var entry:batch.entrySet()){
                    if(entry.getValue().state==null){delete.setString(1,entry.getKey().toString());delete.addBatch();}
                    else{PlayerState state=entry.getValue().state;upsert.setString(1,entry.getKey().toString());upsert.setInt(2,state.level);upsert.setLong(3,state.muteUntil);upsert.setLong(4,state.lastViolation);upsert.addBatch();}
                }
                upsert.executeBatch();delete.executeBatch();c.commit();
            }catch(SQLException e){c.rollback();throw e;}
            Map<UUID,PlayerState> next=new HashMap<>(committed);
            batch.forEach((id,change)->{if(change.state==null)next.remove(id);else next.put(id,change.state);});
            synchronized(pendingLock){committed=Map.copyOf(next);batch.forEach((id,change)->{if(pending.get(id)==change){pending.remove(id);heldRestrictions.remove(id);}});retryAt=0;if(!overflow)problem="";}
            return true;
        }catch(SQLException|IOException|RuntimeException e){synchronized(pendingLock){retryAt=now+5_000_000_000L;if(!overflow)problem="save failed ("+e.getClass().getSimpleName()+"); retry pending";}return false;}
    }}
    void close(){synchronized(pendingLock){closed=true;}}
    private Connection open()throws SQLException,IOException{
        Files.createDirectories(database.getParent());Connection c=DriverManager.getConnection("jdbc:sqlite:"+database);
        try(Statement s=c.createStatement()){s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=NORMAL");s.execute("PRAGMA busy_timeout=5000");}return c;
    }
    private static void initialize(Connection c)throws SQLException{try(Statement s=c.createStatement()){
        s.executeUpdate("CREATE TABLE IF NOT EXISTS punishments (player_uuid TEXT PRIMARY KEY, level INTEGER NOT NULL, mute_until INTEGER NOT NULL, last_violation INTEGER NOT NULL)");
        s.executeUpdate("CREATE INDEX IF NOT EXISTS punishments_mute_expiry ON punishments(mute_until)");
    }}
}
