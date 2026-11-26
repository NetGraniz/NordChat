package com.nordfjell.nordchat.moderation;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModerationFeature implements Listener {
    private final JavaPlugin plugin;
    public ModerationFeature(JavaPlugin plugin){this.plugin=Objects.requireNonNull(plugin);}
    private record ChatJob(Player player,Component message){}
    private record ReloadResult(FilterSettings settings,String error){}
    private static final int CHAT_CAPACITY=128,CHAT_BUDGET=16;
    private final MainThreadBridge<ChatJob,FilterEngine.Result> chatBridge=new MainThreadBridge<>(CHAT_CAPACITY);
    private final Map<UUID,Player> sessions=new ConcurrentHashMap<>();
    private final Map<UUID,Long> noticeAt=new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<ReloadResult> reloadResults=new ArrayBlockingQueue<>(1);
    private final AtomicBoolean reloadPending=new AtomicBoolean();
    private final PlainTextComponentSerializer plainText=PlainTextComponentSerializer.plainText();
    private PunishmentStore punishmentStore;
    private volatile FilterSettings settings;
    private volatile FilterEngine engine;
    private ScheduledExecutorService writer;
    private ScheduledTask pump;
    private volatile boolean accepting;
    private int ticks;
    private Path directory;
    private String reportedProblem="";

    public void requestReload(CommandSender sender){
        if(writer==null){sender.sendMessage(Component.text("Repair punishments.db offline and restart; reload cannot reset failed storage."));return;}
        if(!reloadPending.compareAndSet(false,true)){sender.sendMessage(Component.text("Reload already pending."));return;}
        writer.execute(()->{ReloadResult result;try{result=new ReloadResult(FilterSettings.load(directory),null);}catch(IOException|RuntimeException e){result=new ReloadResult(null,e.getClass().getSimpleName());}if(accepting&&!reloadResults.offer(result)){reloadPending.set(false);plugin.getLogger().warning("Moderation reload result queue is full; reload was not applied.");}});
        sender.sendMessage(Component.text("NordChat filter reload queued. Check console/health for the result."));
    }

    public void enable(){
        directory=plugin.getDataFolder().toPath();
        try{punishmentStore=new PunishmentStore(directory.resolve("punishments.db"));}
        catch(IOException e){plugin.getLogger().severe("Invalid/unreadable punishment database; moderation fails closed. Repair offline/restart. "+e.getClass().getSimpleName());}
        try{settings=FilterSettings.load(directory);}
        catch(IOException e){plugin.getLogger().severe("Invalid moderation configuration; chat/PM blocked until valid reload. "+e.getClass().getSimpleName());}
        if(punishmentStore!=null){
            if(settings!=null)engine=new FilterEngine(punishmentStore,settings);
            writer=Executors.newSingleThreadScheduledExecutor(task->{
                Thread thread=new Thread(task,"NordChat-moderation-storage");thread.setDaemon(true);return thread;
            });
            writer.scheduleWithFixedDelay(new Runnable(){long nextCleanup;public void run(){
                long wall=System.currentTimeMillis();FilterSettings current=settings;
                if(current!=null&&wall>=nextCleanup){punishmentStore.pruneExpired(wall,current.resetMillis());nextCleanup=wall+60_000L;}
                punishmentStore.flush(System.nanoTime(),false);
                String problem=punishmentStore.problem();
                if(!problem.equals(reportedProblem)){
                    if(problem.isEmpty())plugin.getLogger().info("Moderation storage recovered.");
                    else plugin.getLogger().severe("Moderation storage: "+problem);
                    reportedProblem=problem;
                }
            }},100,100,TimeUnit.MILLISECONDS);
        }
        for(Player player:Bukkit.getOnlinePlayers())sessions.put(player.getUniqueId(),player);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        pump=Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,ignored -> tick(),1,1);
        accepting=true;
        if(engine!=null)plugin.getLogger().info("Moderation enabled: bounded entity-owned checks and atomic background persistence.");
        else plugin.getLogger().severe("Moderation started in fail-closed mode.");
    }
    public void disable(){
        accepting=false;chatBridge.close();if(pump!=null)pump.cancel();reloadResults.clear();
        if(writer!=null){
            writer.execute(()->punishmentStore.flush(System.nanoTime(),true));writer.shutdown();
            try{if(!writer.awaitTermination(2,TimeUnit.SECONDS)){
                plugin.getLogger().severe("Moderation writer still active after two seconds; pending changes may remain.");writer.shutdownNow();
            }}catch(InterruptedException e){Thread.currentThread().interrupt();writer.shutdownNow();}
            if(punishmentStore.pendingCount()>0)plugin.getLogger().severe("Unsaved moderation changes: "+punishmentStore.pendingCount());
            punishmentStore.close();
        }
        sessions.clear();noticeAt.clear();
    }
    private void tick(){
        ReloadResult reload=reloadResults.poll();
        if(reload!=null){
            if(reload.settings()!=null){
                settings=reload.settings();
                if(punishmentStore!=null){
                    if(engine==null)engine=new FilterEngine(punishmentStore,settings);else engine.settings(settings);
                }
                plugin.getLogger().info("Moderation configuration reloaded.");
            }else plugin.getLogger().severe("Moderation reload rejected; previous settings retained. "+reload.error());
            reloadPending.set(false);
        }
        long now=System.nanoTime();
        chatBridge.drainAsync(CHAT_BUDGET,now,ticket -> {
            Player player=ticket.payload.player();
            Runnable retired=()->{ticket.cancel();ticket.release();};
            if(!player.getScheduler().execute(plugin,()->{
                try {
                    if(ticket.result.isDone()||System.nanoTime()-ticket.deadline>=0){ticket.cancel();return;}
                    ticket.complete(inspectMain(ticket.payload));
                }catch(RuntimeException error){ticket.result.completeExceptionally(error);}
                finally{ticket.release();}
            },retired,1L))retired.run();
        });
        if(++ticks%20==0&&engine!=null)engine.prune(now);
    }
    @EventHandler public void onJoin(PlayerJoinEvent event){
        sessions.put(event.getPlayer().getUniqueId(),event.getPlayer());
    }
    @EventHandler public void onQuit(PlayerQuitEvent event){
        UUID id=event.getPlayer().getUniqueId();
        if(sessions.get(id)==event.getPlayer()){sessions.remove(id);noticeAt.remove(id);}
        // Keep bounded duplicate history through a reconnect until the configured window expires.
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void onChat(AsyncChatEvent event){
        if(!accepting){event.viewers().clear();return;}
        FilterEngine.Result result;
        ChatJob job=new ChatJob(event.getPlayer(),event.message());
        if(Bukkit.isOwnedByCurrentRegion(event.getPlayer()))result=inspectMain(job);
        else{
            var ticket=chatBridge.offer(job,System.nanoTime()+2_000_000_000L);
            if(ticket==null){event.viewers().clear();return;}
            try{result=ticket.result.get(2,TimeUnit.SECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();ticket.cancel();event.viewers().clear();return;}
            catch(ExecutionException|TimeoutException|CancellationException e){ticket.cancel();event.viewers().clear();return;}
        }
        // Suppress recipients, not the signed event. Paper retains ownership of acknowledgment.
        if(!result.allowed())event.viewers().clear();
    }
    private FilterEngine.Result inspectMain(ChatJob job){
        if(!Bukkit.isOwnedByCurrentRegion(job.player()))throw new IllegalStateException("Moderation must run on the player's owning region");
        Player player=job.player();UUID id=player.getUniqueId();
        if(!accepting||sessions.get(id)!=player||!player.isOnline())return FilterEngine.Result.block("");
        FilterEngine.Result result=engine==null?FilterEngine.Result.block("Moderation is unavailable; please try later.")
                :engine.inspect(id,player.hasPermission("nordchat.moderation.bypass"),plainText.serialize(job.message()),System.currentTimeMillis(),System.nanoTime());
        if(!result.allowed()&&!result.message().isEmpty())notifyMain(player,result.message());
        return result;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onPrivateMessageCommand(PlayerCommandPreprocessEvent event){
        if(!Bukkit.isOwnedByCurrentRegion(event.getPlayer())){event.setCancelled(true);return;}
        String label=PrivateCommands.label(event.getMessage());
        Command resolved=plugin.getServer().getCommandMap().getCommand(label);
        // Core commands remain recognized even when operator settings failed to initialize.
        String message=settings==null?corePrivateBody(event.getMessage())
                :PrivateCommands.extract(event.getMessage(),resolved==null?null:resolved.getName(),settings);
        if(message==null)return;
        var result=inspectMain(new ChatJob(event.getPlayer(),Component.text(message)));
        if(!result.allowed())event.setCancelled(true);
    }
    private String corePrivateBody(String raw){
        String label=PrivateCommands.label(raw);int colon=label.lastIndexOf(':');if(colon>=0)label=label.substring(colon+1);
        String[] first=(raw.startsWith("/")?raw.substring(1):raw).stripLeading().split("\\s+",2);
        if(first.length<2)return null;
        if(Set.of("reply","r","last").contains(label))return first[1];
        if(Set.of("msg","tell","whisper","pm","w").contains(label)){
            String[] parts=first[1].stripLeading().split("\\s+",2);return parts.length==2?parts[1]:null;
        }
        return null;
    }
    private void notifyMain(Player player,String text){
        long now=System.nanoTime();Long previous=noticeAt.get(player.getUniqueId());
        if(previous!=null&&now-previous<250_000_000L)return;
        noticeAt.put(player.getUniqueId(),now);
        player.sendMessage(Component.text(text,NamedTextColor.RED));
    }
    private UUID knownTarget(String text){
        Player online=Bukkit.getPlayerExact(text);if(online!=null)return online.getUniqueId();
        try{
            UUID id=UUID.fromString(text);
            return id.toString().equalsIgnoreCase(text)&&punishmentStore!=null&&punishmentStore.known(id)?id:null;
        }catch(IllegalArgumentException ignored){
            if(!text.matches("[A-Za-z0-9_.-]{1,32}"))return null;
            OfflinePlayer cached=Bukkit.getOfflinePlayerIfCached(text);return cached==null?null:cached.getUniqueId();
        }
    }
    public boolean command(CommandSender sender, String[] args){
        if(sender instanceof Player player && !Bukkit.isOwnedByCurrentRegion(player)){plugin.getLogger().warning("Rejected off-region moderation command callback.");return true;}
        if(!sender.hasPermission("nordchat.admin")){
            sender.sendMessage(Component.text("You do not have permission.",NamedTextColor.RED));return true;
        }
        if(args.length==1&&args[0].equalsIgnoreCase("health")){
            sender.sendMessage(Component.text("NordChat moderation: "+(engine!=null&&punishmentStore.available()?"ready":"blocked")
                    +", chat pending="+chatBridge.size()+", writes pending="+(punishmentStore==null?-1:punishmentStore.pendingCount())
                    +", problem="+(punishmentStore==null?"initialization failure":punishmentStore.problem())));return true;
        }
        if(args.length==1&&args[0].equalsIgnoreCase("reload")){
            requestReload(sender);return true;
        }
        if(args.length==2&&Set.of("status","unmute","reset").contains(args[0].toLowerCase(Locale.ROOT))){
            if(punishmentStore==null){sender.sendMessage(Component.text("Moderation storage unavailable; repair offline/restart."));return true;}
            UUID id=knownTarget(args[1]);
            if(id==null){sender.sendMessage(Component.text("Player has not joined this server before.",NamedTextColor.RED));return true;}
            if(args[0].equalsIgnoreCase("status")){
                var state=punishmentStore.get(id);long remaining=state.muteUntil()>System.currentTimeMillis()?state.muteUntil()-System.currentTimeMillis():0;
                sender.sendMessage(Component.text(args[1]+": level "+state.level()+", mute remaining "+(remaining==0?"none":FilterEngine.formatDuration(remaining))
                        +". Pending writes="+punishmentStore.pendingCount()));return true;
            }
            if(!punishmentStore.available()){sender.sendMessage(Component.text("Storage unhealthy; change rejected."));return true;}
            boolean accepted=args[0].equalsIgnoreCase("reset")?punishmentStore.reset(id)
                    :punishmentStore.put(id,punishmentStore.desired(id).withoutMute());
            sender.sendMessage(Component.text(accepted?"Change queued; existing mute remains until persisted. Verify with status."
                    :"Persistence capacity exceeded; change rejected.",accepted?NamedTextColor.YELLOW:NamedTextColor.RED));return true;
        }
        sender.sendMessage(Component.text("Usage: /nordchat moderation <health|reload|status|unmute|reset> [player]"));return true;
    }
}

