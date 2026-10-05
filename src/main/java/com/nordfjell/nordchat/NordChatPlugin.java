package com.nordfjell.nordchat;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.chat.ChatRenderer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NordChatPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final Set<String> PLAYER_TARGET_COMMANDS = Set.of(
            "msg", "ignore", "ignorehard", "ignoredeathmsgs"
    );

    private PlayerDataStore dataStore;
    private record ChatSession(Player player, String name) {}
    private record KnownPlayer(UUID id, String name) {}
    private final Map<UUID, ChatSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastIncoming = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastOutgoing = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPrivateMessageAt = new ConcurrentHashMap<>();
    private final Set<UUID> sessionDeathMessagesHidden = ConcurrentHashMap.newKeySet();

    private volatile long temporaryIgnoreMillis;
    private volatile long privateMessageCooldownMillis;
    private volatile boolean clickableChatNames;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPluginConfiguration();
        dataStore = new PlayerDataStore(this);

        for (Player player : Bukkit.getOnlinePlayers()) {
            sessions.put(player.getUniqueId(), new ChatSession(player, player.getName()));
            if (dataStore.available()) dataStore.load(player.getUniqueId(), player.getName());
        }

        getServer().getPluginManager().registerEvents(this, this);
        registerCommands();
        getLogger().info("NordChat enabled without packet or protocol dependencies.");
    }

    @Override
    public void onDisable() {
        if (dataStore != null) {
            dataStore.saveAll();
        }
    }

    private void registerCommands() {
        for (String name : List.of(
                "msg", "reply", "last", "ignore", "ignorehard", "ignorelist",
                "ignoredeathmsgs", "togglechat", "toggleprivatemsgs",
                "toggledeathmsgs", "toggledeathmsgshard", "kill", "nordchat"
        )) {
            PluginCommand command = getCommand(name);
            if (command == null) {
                throw new IllegalStateException("Command missing from plugin.yml: " + name);
            }
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    private void reloadPluginConfiguration() {
        reloadConfig();
        int days = Math.max(0, getConfig().getInt("temporary-ignore-days", 7));
        temporaryIgnoreMillis = days == 0 ? 0L : Duration.ofDays(days).toMillis();
        privateMessageCooldownMillis = Math.min(60_000L, Math.max(0L,
                getConfig().getLong("private-message-cooldown-millis", 500L)));
        clickableChatNames = getConfig().getBoolean("clickable-chat-names", true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        sessions.put(player.getUniqueId(), new ChatSession(player, player.getName()));
        if (dataStore.available()) dataStore.load(player.getUniqueId(), player.getName());
        sessionDeathMessagesHidden.remove(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        sessions.remove(playerId);
        sessionDeathMessagesHidden.remove(playerId);
        lastIncoming.remove(playerId);
        lastOutgoing.remove(playerId);
        lastIncoming.values().removeIf(playerId::equals);
        lastOutgoing.values().removeIf(playerId::equals);
        lastPrivateMessageAt.remove(playerId);
        dataStore.unload(playerId);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        UUID senderId = sender.getUniqueId();
        ChatSession session = sessions.get(senderId);
        if (!dataStore.available() || session == null || session.player() != sender) {
            event.viewers().clear();
            return;
        }
        long now = System.currentTimeMillis();
        event.viewers().removeIf(audience -> {
            if (!(audience instanceof Player viewer) || viewer.getUniqueId().equals(senderId)) return false;
            PlayerPreferences preferences = dataStore.getLoaded(viewer.getUniqueId());
            return preferences == null || !preferences.isChatVisible() || preferences.isIgnoring(senderId, now);
        });
        boolean clickable = clickableChatNames;
        // Paper owns delivery/acknowledgments. Later filters can still change/cancel the event.
        // Use the supplied display component; never query mutable display state asynchronously.
        event.renderer(ChatRenderer.viewerUnaware((source, displayName, message) -> {
            if (clickable) {
                displayName = displayName.clickEvent(ClickEvent.suggestCommand("/msg " + session.name() + " "))
                        .hoverEvent(HoverEvent.showText(Component.text("Message " + session.name(), NamedTextColor.GRAY)));
            }
            return displayName.append(Component.text(": ", NamedTextColor.DARK_GRAY)).append(message);
        }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!dataStore.available()) { event.deathMessage(null); return; }
        Component deathMessage = event.deathMessage();
        if (deathMessage == null || !event.getShowDeathMessages()) {
            return;
        }

        UUID deadPlayerId = event.getPlayer().getUniqueId();
        event.deathMessage(null);

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            PlayerPreferences preferences = dataStore.getLoaded(viewer.getUniqueId());
            if (preferences == null) {
                viewer.sendMessage(deathMessage);
                continue;
            }
            boolean visible = preferences.arePersistentDeathMessagesVisible()
                    && !sessionDeathMessagesHidden.contains(viewer.getUniqueId())
                    && !preferences.ignoredDeathMessages().contains(deadPlayerId);
            if (visible) {
                viewer.sendMessage(deathMessage);
            }
        }
        Bukkit.getConsoleSender().sendMessage(deathMessage);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        String permission = name.equals("nordchat") ? "nordchat.admin" : "nordchat." + name;
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(error("You do not have permission to use this command."));
            return true;
        }
        if (!dataStore.canChange() && !name.equals("nordchat")) {
            sender.sendMessage(error("Chat preference storage is unavailable; please try again later."));
            return true;
        }
        return switch (name) {
            case "msg" -> handleMessage(sender, args);
            case "reply" -> handleReply(sender, args, true);
            case "last" -> handleReply(sender, args, false);
            case "ignore" -> handleIgnore(sender, args, IgnoreType.TEMPORARY);
            case "ignorehard" -> handleIgnore(sender, args, IgnoreType.PERMANENT);
            case "ignoredeathmsgs" -> handleIgnore(sender, args, IgnoreType.DEATH_MESSAGES);
            case "ignorelist" -> handleIgnoreList(sender);
            case "togglechat" -> handleToggleChat(sender);
            case "toggleprivatemsgs" -> handleTogglePrivateMessages(sender);
            case "toggledeathmsgs" -> handleToggleSessionDeathMessages(sender);
            case "toggledeathmsgshard" -> handleTogglePersistentDeathMessages(sender);
            case "kill" -> handleKill(sender, args);
            case "nordchat" -> handleAdmin(sender, args);
            default -> false;
        };
    }

    private boolean handleMessage(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(error("Usage: /msg <player> <message>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || (sender instanceof Player viewer && !viewer.canSee(target))) {
            sender.sendMessage(error("Player is not online."));
            return true;
        }
        String message = join(args, 1);
        sendPrivateMessage(sender, target, message);
        return true;
    }

    private boolean handleReply(CommandSender sender, String[] args, boolean incoming) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(error("Only players can use this command."));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(error("Usage: /" + (incoming ? "reply" : "last") + " <message>"));
            return true;
        }
        UUID targetId = (incoming ? lastIncoming : lastOutgoing).get(player.getUniqueId());
        if (targetId == null) {
            sender.sendMessage(error(incoming
                    ? "Nobody has messaged you yet."
                    : "You have not messaged anyone yet."));
            return true;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !player.canSee(target)) {
            sender.sendMessage(error("That player is no longer online."));
            return true;
        }
        sendPrivateMessage(sender, target, join(args, 0));
        return true;
    }

    private void sendPrivateMessage(CommandSender sender, Player target, String message) {
        if (message.length() > 2048 || message.codePoints().anyMatch(cp -> Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029)) {
            sender.sendMessage(error("Message is too long or contains control characters."));
            return;
        }
        if (message.isBlank()) {
            sender.sendMessage(error("Message cannot be empty."));
            return;
        }
        if (sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(error("You cannot message yourself."));
            return;
        }

        PlayerPreferences targetPreferences = dataStore.load(target.getUniqueId(), target.getName());
        UUID senderId = sender instanceof Player player ? player.getUniqueId() : null;
        if (!targetPreferences.arePrivateMessagesVisible()) {
            sender.sendMessage(error("That player has private messages disabled."));
            return;
        }
        if (senderId != null && targetPreferences.isIgnoring(senderId, System.currentTimeMillis())) {
            sender.sendMessage(error("That player is not accepting messages from you."));
            return;
        }

        if (senderId != null && privateMessageCooldownMillis > 0L) {
            long now = System.nanoTime();
            Long previous = lastPrivateMessageAt.get(senderId);
            if (previous != null && now - previous < privateMessageCooldownMillis * 1_000_000L) {
                long remaining = Math.max(1L, (privateMessageCooldownMillis * 1_000_000L - (now - previous)) / 1_000_000L);
                sender.sendMessage(error("Please wait " + remaining + " ms before messaging again."));
                return;
            }
            lastPrivateMessageAt.put(senderId, now);
        }

        String senderName = sender instanceof Player player ? player.getName() : "Console";
        Component incoming = Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text(senderName, NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.suggestCommand("/msg " + senderName + " ")))
                .append(Component.text(" -> you] ", NamedTextColor.DARK_GRAY))
                .append(Component.text(message, NamedTextColor.WHITE));
        Component outgoing = Component.text("[you -> ", NamedTextColor.DARK_GRAY)
                .append(Component.text(target.getName(), NamedTextColor.AQUA))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .append(Component.text(message, NamedTextColor.WHITE));

        target.sendMessage(incoming);
        sender.sendMessage(outgoing);
        if (senderId != null) {
            lastIncoming.put(target.getUniqueId(), senderId);
            lastOutgoing.put(senderId, target.getUniqueId());
        }
    }

    private boolean handleIgnore(CommandSender sender, String[] args, IgnoreType type) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(error("Usage: /" + type.command + " <player>"));
            return true;
        }

        KnownPlayer target = resolveKnownPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(error("Player has not joined this server before."));
            return true;
        }
        if (target.id().equals(player.getUniqueId())) {
            sender.sendMessage(error("You cannot ignore yourself."));
            return true;
        }
        dataStore.rememberName(target.id(), target.name());
        PlayerPreferences preferences = dataStore.load(player.getUniqueId(), player.getName());

        boolean nowIgnored;
        long now = System.currentTimeMillis();
        preferences.temporaryIgnores().entrySet().removeIf(entry ->
                entry.getValue() != Long.MAX_VALUE && entry.getValue() <= now);
        switch (type) {
            case TEMPORARY -> {
                if (preferences.hardIgnores().contains(target.id())) {
                    sender.sendMessage(error("That player is already permanently ignored."));
                    return true;
                }
                if (preferences.temporaryIgnores().remove(target.id()) != null) {
                    nowIgnored = false;
                } else {
                    long expiry = temporaryIgnoreMillis == 0L
                            ? Long.MAX_VALUE
                            : System.currentTimeMillis() + temporaryIgnoreMillis;
                    if (preferences.temporaryIgnores().size() >= PreferenceStorage.MAX_IGNORES) {
                        sender.sendMessage(error("Ignore list limit reached.")); return true;
                    }
                    preferences.temporaryIgnores().put(target.id(), expiry);
                    nowIgnored = true;
                }
            }
            case PERMANENT -> {
                if (preferences.hardIgnores().remove(target.id())) {
                    nowIgnored = false;
                } else {
                    if (preferences.hardIgnores().size() >= PreferenceStorage.MAX_IGNORES) {
                        sender.sendMessage(error("Ignore list limit reached.")); return true;
                    }
                    preferences.hardIgnores().add(target.id());
                    preferences.temporaryIgnores().remove(target.id());
                    nowIgnored = true;
                }
            }
            case DEATH_MESSAGES -> {
                if (preferences.ignoredDeathMessages().remove(target.id())) {
                    nowIgnored = false;
                } else {
                    if (preferences.ignoredDeathMessages().size() >= PreferenceStorage.MAX_IGNORES) {
                        sender.sendMessage(error("Ignore list limit reached.")); return true;
                    }
                    preferences.ignoredDeathMessages().add(target.id());
                    nowIgnored = true;
                }
            }
            default -> throw new IllegalStateException("Unexpected ignore type");
        }
        if (!dataStore.save(player.getUniqueId())) {
            sender.sendMessage(error("Preference persistence capacity exceeded; change is not confirmed saved."));
            return true;
        }

        String name = target.name() != null ? target.name() : target.id().toString();
        String subject = type == IgnoreType.DEATH_MESSAGES ? "death messages from " + name : name;
        sender.sendMessage(success(subject + (nowIgnored ? " ignored." : " unignored.") + " Save queued."));
        return true;
    }

    private boolean handleIgnoreList(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        PlayerPreferences preferences = dataStore.load(player.getUniqueId(), player.getName());
        long now = System.currentTimeMillis();
        boolean pruned = preferences.temporaryIgnores().entrySet().removeIf(entry ->
                entry.getValue() != Long.MAX_VALUE && entry.getValue() <= now);
        if (pruned && !dataStore.save(player.getUniqueId()))
            sender.sendMessage(error("Expired-list cleanup could not be queued for persistence."));

        sender.sendMessage(Component.text("Ignore list", NamedTextColor.GOLD));
        sendIgnoreSection(player, "Temporary", preferences.temporaryIgnores().keySet(), "/ignore ");
        sendIgnoreSection(player, "Permanent", preferences.hardIgnores(), "/ignorehard ");
        sendIgnoreSection(player, "Death messages", preferences.ignoredDeathMessages(), "/ignoredeathmsgs ");
        return true;
    }

    private void sendIgnoreSection(Player player, String title, Set<UUID> values, String removeCommand) {
        if (values.isEmpty()) {
            player.sendMessage(Component.text(title + ": none", NamedTextColor.GRAY));
            return;
        }
        player.sendMessage(Component.text(title + ":", NamedTextColor.YELLOW));
        int shown = 0;
        for (UUID targetId : values) {
            if (shown++ >= 100) { player.sendMessage(error("Only the first 100 entries are shown; remove others by name/UUID.")); break; }
            String name = dataStore.knownName(targetId);
            String commandTarget = name.equals(targetId.toString()) ? targetId.toString() : name;
            Component remove = Component.text(" [remove]", NamedTextColor.RED)
                    .clickEvent(ClickEvent.runCommand(removeCommand + commandTarget))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to remove", NamedTextColor.GRAY)));
            player.sendMessage(Component.text("- " + name, NamedTextColor.WHITE).append(remove));
        }
    }

    private boolean handleToggleChat(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return true;
        PlayerPreferences preferences = dataStore.load(player.getUniqueId(), player.getName());
        boolean visible = preferences.toggleChatVisible();
        if (!dataStore.save(player.getUniqueId())) {
            sender.sendMessage(error("Preference persistence capacity exceeded; change is not confirmed saved."));
            return true;
        }
        sender.sendMessage(success("Global chat is now " + onOff(visible) + ". Save queued."));
        return true;
    }

    private boolean handleTogglePrivateMessages(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return true;
        PlayerPreferences preferences = dataStore.load(player.getUniqueId(), player.getName());
        boolean visible = preferences.togglePrivateMessagesVisible();
        if (!dataStore.save(player.getUniqueId())) {
            sender.sendMessage(error("Preference persistence capacity exceeded; change is not confirmed saved."));
            return true;
        }
        sender.sendMessage(success("Private messages are now " + onOff(visible) + ". Save queued."));
        return true;
    }

    private boolean handleToggleSessionDeathMessages(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return true;
        UUID playerId = player.getUniqueId();
        boolean visible;
        if (sessionDeathMessagesHidden.remove(playerId)) {
            visible = true;
        } else {
            sessionDeathMessagesHidden.add(playerId);
            visible = false;
        }
        sender.sendMessage(success("Death messages are now " + onOff(visible) + " until disconnect."));
        return true;
    }

    private boolean handleTogglePersistentDeathMessages(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return true;
        PlayerPreferences preferences = dataStore.load(player.getUniqueId(), player.getName());
        boolean visible = preferences.togglePersistentDeathMessagesVisible();
        if (!dataStore.save(player.getUniqueId())) {
            sender.sendMessage(error("Preference persistence capacity exceeded; change is not confirmed saved."));
            return true;
        }
        sender.sendMessage(success("Persistent death messages are now " + onOff(visible) + ". Save queued."));
        return true;
    }

    private boolean handleKill(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return true;
        if (args.length != 0) {
            sender.sendMessage(error("Usage: /kill"));
            return true;
        }
        player.setHealth(0.0D);
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadPluginConfiguration();
            sender.sendMessage(success("NordChat configuration reloaded."));
        } else {
            sender.sendMessage(error("Usage: /nordchat reload"));
        }
        return true;
    }

    private @Nullable Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(error("Only players can use this command."));
        return null;
    }

    private @Nullable KnownPlayer resolveKnownPlayer(String input) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) return new KnownPlayer(online.getUniqueId(), online.getName());
        try {
            UUID id = UUID.fromString(input);
            return id.toString().equalsIgnoreCase(input) && dataStore.known(id)
                    ? new KnownPlayer(id, dataStore.knownName(id)) : null;
        } catch (IllegalArgumentException ignored) {
            if (!PreferenceStorage.validName(input)) return null;
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
            return cached == null ? null : new KnownPlayer(cached.getUniqueId(), cached.getName());
        }
    }

    private String join(String[] args, int start) {
        StringBuilder builder = new StringBuilder();
        for (int index = start; index < args.length; index++) {
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(args[index]);
        }
        return builder.toString();
    }

    private String onOff(boolean visible) {
        return visible ? "visible" : "hidden";
    }

    private Component success(String message) {
        return Component.text(message, NamedTextColor.GREEN);
    }

    private Component error(String message) {
        return Component.text(message, NamedTextColor.RED);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!sender.hasPermission(name.equals("nordchat") ? "nordchat.admin" : "nordchat." + name)) return List.of();
        if (name.equals("nordchat") && args.length == 1) {
            return prefixMatches(args[0], List.of("reload"));
        }
        if (PLAYER_TARGET_COMMANDS.contains(name) && args.length == 1) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.equals(sender) && (!(sender instanceof Player viewer) || viewer.canSee(player))) {
                    names.add(player.getName());
                }
            }
            return prefixMatches(args[0], names);
        }
        return List.of();
    }

    private List<String> prefixMatches(String prefix, List<String> values) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private enum IgnoreType {
        TEMPORARY("ignore"),
        PERMANENT("ignorehard"),
        DEATH_MESSAGES("ignoredeathmsgs");

        private final String command;

        IgnoreType(String command) {
            this.command = command;
        }
    }
}

