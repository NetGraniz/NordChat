package com.nordfjell.nordchat;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PlayerPreferences {
    private volatile boolean chatVisible = true;
    private volatile boolean privateMessagesVisible = true;
    private volatile boolean persistentDeathMessagesVisible = true;
    private final Map<UUID, Long> temporaryIgnores = new ConcurrentHashMap<>();
    private final Set<UUID> hardIgnores = ConcurrentHashMap.newKeySet();
    private final Set<UUID> ignoredDeathMessages = ConcurrentHashMap.newKeySet();

    boolean isChatVisible() {
        return chatVisible;
    }

    boolean toggleChatVisible() {
        chatVisible = !chatVisible;
        return chatVisible;
    }

    void setChatVisible(boolean value) {
        chatVisible = value;
    }

    boolean arePrivateMessagesVisible() {
        return privateMessagesVisible;
    }

    boolean togglePrivateMessagesVisible() {
        privateMessagesVisible = !privateMessagesVisible;
        return privateMessagesVisible;
    }

    void setPrivateMessagesVisible(boolean value) {
        privateMessagesVisible = value;
    }

    boolean arePersistentDeathMessagesVisible() {
        return persistentDeathMessagesVisible;
    }

    boolean togglePersistentDeathMessagesVisible() {
        persistentDeathMessagesVisible = !persistentDeathMessagesVisible;
        return persistentDeathMessagesVisible;
    }

    void setPersistentDeathMessagesVisible(boolean value) {
        persistentDeathMessagesVisible = value;
    }

    Map<UUID, Long> temporaryIgnores() {
        return temporaryIgnores;
    }

    Set<UUID> hardIgnores() {
        return hardIgnores;
    }

    Set<UUID> ignoredDeathMessages() {
        return ignoredDeathMessages;
    }

    boolean isIgnoring(UUID target, long now) {
        if (hardIgnores.contains(target)) {
            return true;
        }
        Long expiresAt = temporaryIgnores.get(target);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt != Long.MAX_VALUE && expiresAt <= now) {
            return false;
        }
        return true;
    }

    Snapshot snapshot() {
        return new Snapshot(chatVisible, privateMessagesVisible, persistentDeathMessagesVisible,
                Map.copyOf(temporaryIgnores), Set.copyOf(hardIgnores), Set.copyOf(ignoredDeathMessages));
    }

    static PlayerPreferences from(Snapshot snapshot) {
        PlayerPreferences preferences = new PlayerPreferences();
        preferences.chatVisible = snapshot.chatVisible();
        preferences.privateMessagesVisible = snapshot.privateMessagesVisible();
        preferences.persistentDeathMessagesVisible = snapshot.deathMessagesVisible();
        preferences.temporaryIgnores.putAll(snapshot.temporaryIgnores());
        preferences.hardIgnores.addAll(snapshot.hardIgnores());
        preferences.ignoredDeathMessages.addAll(snapshot.ignoredDeathMessages());
        return preferences;
    }

    record Snapshot(boolean chatVisible, boolean privateMessagesVisible, boolean deathMessagesVisible,
                    Map<UUID, Long> temporaryIgnores, Set<UUID> hardIgnores, Set<UUID> ignoredDeathMessages) {
        Snapshot {
            temporaryIgnores = Map.copyOf(temporaryIgnores);
            hardIgnores = Set.copyOf(hardIgnores);
            ignoredDeathMessages = Set.copyOf(ignoredDeathMessages);
        }
    }
}

