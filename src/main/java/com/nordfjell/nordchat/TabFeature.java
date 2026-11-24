package com.nordfjell.nordchat;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

final class TabFeature implements Listener {
    private final JavaPlugin plugin;
    private final Path config;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private final Set<UUID> ownedNames = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean reloadPending = new AtomicBoolean();
    private volatile TabSettings settings;
    private volatile boolean closing;

    TabFeature(JavaPlugin plugin, Path config) { this.plugin = plugin; this.config = config; }

    void enable() throws IOException {
        settings = TabSettings.load(config);
        Bukkit.getGlobalRegionScheduler().execute(plugin, this::restart);
    }

    void disable() {
        closing = true;
        tasks.values().forEach(ScheduledTask::cancel);
        tasks.clear();
        ownedNames.clear();
    }

    void requestReload(CommandSender sender) {
        if (closing || !reloadPending.compareAndSet(false, true)) {
            sender.sendMessage(Component.text(closing ? "NordChat TAB is stopping." : "TAB reload is already pending."));
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            TabSettings candidate = null; Exception failure = null;
            try { candidate = TabSettings.load(config); } catch (Exception error) { failure = error; }
            TabSettings next = candidate; Exception problem = failure;
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    if (closing) return;
                    if (problem != null) {
                        plugin.getLogger().warning("TAB reload rejected; previous settings retained: " + problem.getClass().getSimpleName());
                        feedback(sender, "TAB reload rejected; previous settings retained.");
                    } else {
                        settings = next;
                        restart();
                        feedback(sender, "NordChat TAB configuration reloaded.");
                    }
                } finally { reloadPending.set(false); }
            });
        });
        sender.sendMessage(Component.text("NordChat TAB reload queued."));
    }

    private void restart() {
        tasks.values().forEach(ScheduledTask::cancel);
        tasks.clear();
        for (Player player : Bukkit.getOnlinePlayers()) start(player);
    }

    private void start(Player player) {
        UUID id = player.getUniqueId();
        ScheduledTask previous = tasks.remove(id);
        if (previous != null) previous.cancel();
        TabSettings current = settings;
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, ignored -> update(player), () -> {
            tasks.remove(id); ownedNames.remove(id);
        }, 1L, current.intervalTicks());
        if (task != null) tasks.put(id, task);
    }

    private void update(Player viewer) {
        TabSettings current = settings;
        double tps = Math.min(20.0D, Bukkit.getTPS()[0]);
        Component header = mini.deserialize(current.header());
        Component footer = mini.deserialize(current.footer(),
                Placeholder.unparsed("tps", String.format(Locale.ROOT, "%.1f", tps)),
                Placeholder.unparsed("online", Integer.toString(Bukkit.getOnlinePlayers().size())),
                Placeholder.unparsed("ping", Integer.toString(Math.max(0, viewer.getPing()))));
        viewer.sendPlayerListHeaderAndFooter(header, footer);
        if (current.plainPlayerNames()) { viewer.playerListName(Component.text(viewer.getName())); ownedNames.add(viewer.getUniqueId()); }
        else if (ownedNames.remove(viewer.getUniqueId())) viewer.playerListName(null);
    }

    @EventHandler public void join(PlayerJoinEvent event) { Bukkit.getGlobalRegionScheduler().execute(plugin, () -> start(event.getPlayer())); }
    @EventHandler public void quit(PlayerQuitEvent event) { UUID id=event.getPlayer().getUniqueId(); ScheduledTask task=tasks.remove(id); if(task!=null)task.cancel(); ownedNames.remove(id); }

    private void feedback(CommandSender sender, String message) {
        if (sender instanceof Player player) player.getScheduler().execute(plugin, () -> { if(player.isOnline())player.sendMessage(Component.text(message)); }, null, 1L);
        else sender.sendMessage(Component.text(message));
    }
}
