package dev.nordfjell.tests;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import com.sun.net.httpserver.*;

/** Synthetic, loopback-only integration support. NEVER install on a real server. */
public final class NordSuiteTest extends JavaPlugin implements Listener {
    private HttpsServer https;
    private final AtomicInteger heartbeats=new AtomicInteger();
    private final AtomicInteger failures=new AtomicInteger();
    @Override public void onEnable() {
        try {
            KeyStore keys=KeyStore.getInstance("PKCS12");
            try(var input=Files.newInputStream(getDataFolder().toPath().resolve("local-tls.p12"))){keys.load(input,"local-only-test".toCharArray());}
            var manager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            manager.init(keys,"local-only-test".toCharArray());
            SSLContext context=SSLContext.getInstance("TLS");context.init(manager.getKeyManagers(),null,null);
            https=HttpsServer.create(new java.net.InetSocketAddress("127.0.0.1",25844),0);
            https.setHttpsConfigurator(new HttpsConfigurator(context));
            https.createContext("/heartbeat",exchange -> {
                heartbeats.incrementAndGet();int status=failures.getAndUpdate(n -> Math.max(0,n-1))>0 ? 503 : 200;
                byte[] response="local synthetic heartbeat".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,response.length);try(var output=exchange.getResponseBody()){output.write(response);}
            });
            https.start();
        }catch(Exception error){throw new IllegalStateException("Local TLS test setup failed",error);}
        getServer().getPluginManager().registerEvents(this,this);
        var console=Bukkit.getConsoleSender().addAttachment(this);
        for(String plugin:List.of("nordchat","nordfilter","nordcommands","norddeaths","nordpets","nordphantoms","nordstatus"))
            console.setPermission(plugin+".admin",true);
        getCommand("nsuite").setExecutor((sender,command,label,args) -> {
            if(sender instanceof Player||args.length==0)return true;
            String action=args[0];
            try {
                if(action.equals("status")){getLogger().info("NSSTATUS heartbeats="+heartbeats.get());return true;}
                if(action.equals("httpfail")){failures.set(1);getLogger().info("NSHTTPFAIL");return true;}
                if(action.equals("worldapi")){
                    try {Bukkit.createWorld(new WorldCreator("SyntheticFoliaWorldApiProbe"));getLogger().info("NSWORLDAPI available");}
                    catch(UnsupportedOperationException error){getLogger().info("NSWORLDAPI unsupported");}
                    return true;
                }
                Player player=Bukkit.getPlayerExact(args[1]);if(player==null)throw new IllegalArgumentException("Missing test player");
                player.getScheduler().execute(this,() -> {
                    try {
                        switch(action){
                            case "step" -> {
                                Location from=player.getLocation();
                                Bukkit.getPluginManager().callEvent(new org.bukkit.event.player.PlayerMoveEvent(player,from,from.clone().add(1,0,0)));
                                getLogger().info("NSSTEP "+player.getName());
                            }
                            case "move" -> {
                                World world=Bukkit.getWorld(args[2]);
                                if(world==null)world=Bukkit.getWorlds().stream().filter(w -> w.getKey().toString().equals(args[2])).findFirst().orElse(null);
                                if(world==null)throw new IllegalArgumentException("Unknown world "+args[2]);
                                player.teleportAsync(new Location(world,Double.parseDouble(args[3]),Double.parseDouble(args[4]),Double.parseDouble(args[5])))
                                    .thenAccept(ok -> getLogger().info("NSMOVE "+args[1]+" "+ok));
                            }
                            case "homefast" -> {
                                Object plugin=Bukkit.getPluginManager().getPlugin("NordHomes");
                                var pending=(Map<?,?>)field(plugin,"pendingTeleports");Object teleport=pending.get(player.getUniqueId());
                                if(teleport==null)throw new IllegalStateException("No pending teleport");
                                Field seconds=teleport.getClass().getDeclaredField("secondsRemaining");seconds.setAccessible(true);seconds.setInt(teleport,1);
                                getLogger().info("NSHOMEFAST "+player.getName());
                            }
                            case "pets" -> {
                                Player attacker=Bukkit.getPlayerExact(args[2]);
                                Wolf pet=player.getWorld().spawn(player.getLocation().clone().add(2,0,0),Wolf.class);
                                pet.setOwner(player);pet.setTamed(true);double health=pet.getHealth();
                                pet.damage(2.0,attacker);if(pet.getHealth()!=health)throw new IllegalStateException("Foreign pet was damaged");
                                pet.remove();getLogger().info("NSPETS protected");
                            }
                            case "phantoms" -> {
                                Phantom phantom=player.getWorld().spawn(player.getLocation().clone().add(0,8,0),Phantom.class);
                                if(player.getWorld().getEnvironment()==World.Environment.NORMAL){
                                    if(phantom.isValid())throw new IllegalStateException("Overworld spawn not cancelled");
                                    getLogger().info("NSPHANTOMS overworld-blocked");
                                }else{
                                    if(!phantom.isSilent())throw new IllegalStateException("End phantom not passive/silent");
                                    phantom.setTarget(player);if(phantom.getTarget()!=null)throw new IllegalStateException("Passive phantom targeted player");
                                    phantom.damage(1.0,player);
                                    phantom.getScheduler().runDelayed(this,ignored -> {
                                        try {
                                            if(phantom.isSilent())throw new IllegalStateException("Provoked phantom stayed silent");
                                            getLogger().info("NSPHANTOMS end-passive-provoked");phantom.remove();
                                        }catch(Exception error){getLogger().severe("NSFAIL "+error);}
                                    },null,30L);
                                }
                            }
                            case "pos" -> getLogger().info("NSPOS "+player.getName()+" "+player.getWorld().getName()+" "+player.getLocation().getBlockX()+" "+player.getLocation().getBlockZ());
                            default -> throw new IllegalArgumentException("Unknown test action");
                        }
                    }catch(Exception error){getLogger().severe("NSFAIL "+error);}
                },null,1L);
            }catch(Exception error){getLogger().severe("NSFAIL "+error);}
            return true;
        });
        getLogger().info("NORD_SUITE_READY");
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        Player player=event.getPlayer();player.setGameMode(GameMode.CREATIVE);player.setAllowFlight(true);player.setFlying(true);
        var permission=player.addAttachment(this);
        for(String plugin:List.of("nordchat","nordfilter","nordcommands","norddeaths","nordpets","nordphantoms","nordstatus"))
            permission.setPermission(plugin+".admin",true);
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void death(org.bukkit.event.entity.PlayerDeathEvent event){getLogger().info("NSDEATH "+event.getPlayer().getName());}
    private Object field(Object target,String name)throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    @Override public void onDisable(){if(https!=null)https.stop(0);}
}
