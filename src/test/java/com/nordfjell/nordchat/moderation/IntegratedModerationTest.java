package com.nordfjell.nordchat.moderation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IntegratedModerationTest {
    @TempDir Path directory;

    @Test
    void loadsSeparatedFilterConfigurationAndPersistsPunishments() throws Exception {
        Files.writeString(directory.resolve("filter.yml"), "{}\n");
        Files.writeString(directory.resolve("banwords.yml"), "words: [blockedword]\n");
        FilterSettings settings = FilterSettings.load(directory);
        PunishmentStore store = new PunishmentStore(directory.resolve("punishments.db"));
        FilterEngine engine = new FilterEngine(store, settings);
        UUID player = UUID.randomUUID();

        assertFalse(engine.inspect(player, false, "blockedword", System.currentTimeMillis(), System.nanoTime()).allowed());
        assertTrue(store.pendingCount() > 0);
        assertTrue(store.flush(System.nanoTime(), true));
        assertTrue(new PunishmentStore(directory.resolve("punishments.db")).known(player));
        store.close();
    }

    @Test
    void rejectsMissingWordListWithoutReplacingRuntimeState() throws Exception {
        Files.writeString(directory.resolve("filter.yml"), "{}\n");
        assertThrows(java.io.IOException.class, () -> FilterSettings.load(directory));
    }
}
