package com.nordfjell.nordchat;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TabSettingsTest {
    @TempDir Path directory;

    @Test void loadsStrictBoundedSettings() throws Exception {
        Path file = directory.resolve("tab.yml");
        Files.writeString(file, "header: '<green>Nord Fjell</green>'\nfooter: 'TPS: <tps>'\nupdate-interval-ticks: 40\nplain-player-names: true\n");
        TabSettings settings = TabSettings.load(file);
        assertEquals(40, settings.intervalTicks());
        assertTrue(settings.plainPlayerNames());
    }

    @Test void rejectsUnknownFieldsAndInvalidIntervals() throws Exception {
        Path file = directory.resolve("tab.yml");
        Files.writeString(file, "header: ok\nfooter: ok\nupdate-interval-ticks: 1\nplain-player-names: true\n");
        assertThrows(Exception.class, () -> TabSettings.load(file));
        Files.writeString(file, "header: ok\nfooter: ok\nupdate-interval-ticks: 40\nplain-player-names: true\nunknown: value\n");
        assertThrows(Exception.class, () -> TabSettings.load(file));
    }
}
