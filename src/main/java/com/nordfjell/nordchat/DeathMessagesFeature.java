package com.nordfjell.nordchat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

final class DeathMessagesFeature implements Listener {
    private static final long MAX_AGE=10_000_000_000L;
    private final JavaPlugin plugin;private final File config;
    private volatile Map<String,List<String>> messages=Map.of();private final Map<UUID,RecentDamage> recent=new ConcurrentHashMap<>();
    DeathMessagesFeature(JavaPlugin plugin,File config){this.plugin=plugin;this.config=config;}
    void load(){YamlConfiguration yaml=YamlConfiguration.loadConfiguration(config);Map<String,List<String>> next=new HashMap<>();var section=yaml.getConfigurationSection("messages");if(section!=null)for(String key:section.getKeys(false))next.put(key,List.copyOf(yaml.getStringList("messages."+key)));messages=Map.copyOf(next);}
    void close(){recent.clear();}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void damage(EntityDamageEvent event){if(event.getEntity() instanceof Player player&&event.getFinalDamage()>0)recent.put(player.getUniqueId(),new RecentDamage(classify(event),System.nanoTime()));}
    @EventHandler(priority=EventPriority.NORMAL)public void death(PlayerDeathEvent event){RecentDamage previous=recent.remove(event.getPlayer().getUniqueId());if(!event.getShowDeathMessages()||event.deathMessage()==null)return;DeathContext context=previous!=null&&System.nanoTime()-previous.at<=MAX_AGE?previous.context:new DeathContext("generic","unknown");List<String> options=messages.getOrDefault(context.category,List.of());if(options.isEmpty())options=messages.getOrDefault("generic",List.of());if(options.isEmpty())options=List.of("{player} died.");String text=options.get(ThreadLocalRandom.current().nextInt(options.size())).replace("{player}",event.getPlayer().getName()).replace("{killer}",context.killer);event.deathMessage(Component.text(text,NamedTextColor.RED));}
    @EventHandler public void quit(PlayerQuitEvent event){recent.remove(event.getPlayer().getUniqueId());}
    private DeathContext classify(EntityDamageEvent damage){if(damage instanceof EntityDamageByEntityEvent entity){Entity attacker=entity.getDamageSource().getCausingEntity();if(attacker==null)attacker=entity.getDamager();if(attacker instanceof Player player)return new DeathContext("player",player.getName());return new DeathContext("mob",readable(attacker.getType().getKey().getKey()));}return switch(damage.getCause()){case FALL->new DeathContext("fall","gravity");case FIRE,FIRE_TICK,HOT_FLOOR,CAMPFIRE->new DeathContext("fire","fire");case LAVA->new DeathContext("lava","lava");case DROWNING->new DeathContext("drowning","water");case BLOCK_EXPLOSION,ENTITY_EXPLOSION->new DeathContext("explosion","an explosion");case VOID->new DeathContext("void","the void");case SUFFOCATION->new DeathContext("suffocation","a wall");case FREEZE->new DeathContext("freezing","the cold");default->new DeathContext("generic","unknown");};}
    private String readable(String key){return String.join(" ",key.toLowerCase(Locale.ROOT).split("_"));}
    private record DeathContext(String category,String killer){}private record RecentDamage(DeathContext context,long at){}
}
