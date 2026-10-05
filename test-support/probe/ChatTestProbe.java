package com.nordfjell.nordchat.test;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.*;
import java.lang.reflect.*;
import java.util.*;

/** LOCAL ONLY. Never included in NordChat release or installed on production. */
public final class ChatTestProbe extends JavaPlugin implements Listener {
    private Object originalPersister;
    private int delay;
    private boolean fail;
    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this,this);
        getCommand("ctest").setExecutor(this);
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        event.getPlayer().addAttachment(this,"nordfilter.bypass",true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void late(AsyncChatEvent event) {
        String text=PlainTextComponentSerializer.plainText().serialize(event.message());
        if(text.startsWith("late-block")) event.setCancelled(true);
        if(text.startsWith("late-clear")) event.viewers().clear();
        if(text.startsWith("late-rewrite")) event.message(Component.text("LATE_REWRITTEN"));
        if(text.startsWith("late-console-only")) event.viewers().removeIf(a->a instanceof Player);
        if(text.startsWith("late-only-beta")) event.viewers().removeIf(a->a instanceof Player p && !p.getName().equals("NCBeta"));
    }
    private Object storage() throws Exception {
        Object plugin=Bukkit.getPluginManager().getPlugin("NordChat");
        Field data=plugin.getClass().getDeclaredField("dataStore"); data.setAccessible(true);
        Object store=data.get(plugin);
        Field storage=store.getClass().getDeclaredField("storage"); storage.setAccessible(true);
        return storage.get(store);
    }
    private Object invoke(Object target,String method) throws Exception {
        Method m=target.getClass().getDeclaredMethod(method);m.setAccessible(true);return m.invoke(target);
    }
    private void faults() throws Exception {
        Object storage=storage();
        Field field=storage.getClass().getDeclaredField("persister");field.setAccessible(true);
        if(originalPersister==null)originalPersister=field.get(storage);
        Class<?> type=field.getType();
        Object replacement=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            if(method.getName().equals("save")){
                if(delay>0)Thread.sleep(delay);
                if(fail)throw new java.io.IOException("LOCAL synthetic storage failure");
                try{method.setAccessible(true);return method.invoke(originalPersister,args);}
                catch(InvocationTargetException e){throw e.getCause();}
            }
            return null;
        });
        field.set(storage,replacement);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(sender instanceof Player){sender.sendMessage("Console only");return true;}
        try {
            switch(args[0]){
                case "fault"->{fail=Boolean.parseBoolean(args[1]);faults();}
                case "slow"->{delay=Integer.parseInt(args[1]);faults();}
                case "state"->{
                    Object s=storage();
                    long writers=Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().equals("NordChat-preference-storage")&&t.isAlive()).count();
                    getLogger().info("CSTATE "+args[1]+" pending="+(s==null?-1:invoke(s,"pending"))+" writers="+writers
                            +" problem="+(s==null?"INITIALIZATION_FAILED":invoke(s,"problem")));
                }
                case "filter"->Bukkit.getPlayerExact(args[1]).addAttachment(this,"nordfilter.bypass",Boolean.parseBoolean(args[2]));
                case "deny"->Bukkit.getPlayerExact(args[1]).addAttachment(this,args[2],false);
                case "hide"->Bukkit.getPlayerExact(args[1]).hidePlayer(this,Bukkit.getPlayerExact(args[2]));
                case "show"->Bukkit.getPlayerExact(args[1]).showPlayer(this,Bukkit.getPlayerExact(args[2]));
                case "permission"->Bukkit.getPlayerExact(args[1]).addAttachment(this,args[2],Boolean.parseBoolean(args[3]));
                case "direct"->{
                    JavaPlugin plugin=(JavaPlugin)Bukkit.getPluginManager().getPlugin("NordChat");
                    plugin.onCommand(Bukkit.getPlayerExact(args[1]),plugin.getCommand(args[2]),args[2],Arrays.copyOfRange(args,3,args.length));
                }
                default->throw new IllegalArgumentException("Unknown test command");
            }
            getLogger().info("CTEST_OK "+String.join(" ",args));
        }catch(Exception e){getLogger().severe("CTEST_FAILED "+e);}
        return true;
    }
}
